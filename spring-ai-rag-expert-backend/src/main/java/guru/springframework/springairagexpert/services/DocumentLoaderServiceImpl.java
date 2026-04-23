package guru.springframework.springairagexpert.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Slf4j
public class DocumentLoaderServiceImpl implements DocumentLoaderService {

    static final String DOCUMENT_URL_KEY = "document_url";

    final VectorStore vectorStore;
    final ResourceLoader resourceLoader;
    final MilvusServiceClient milvusClient;
    final RemoteDocumentResolver remoteDocumentResolver;

    @Value("${spring.ai.vectorstore.milvus.collectionName:vector_store}")
    String collectionName;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Tracks loaded document URLs -> their vector store document IDs
    private final ConcurrentHashMap<String, List<String>> loadedDocuments = new ConcurrentHashMap<>();

    // Caches raw bytes for HTTP(S) documents so the viewer can serve them without re-fetching
    private final ConcurrentHashMap<String, byte[]> bytesCache = new ConcurrentHashMap<>();

    /**
     * For HTTP(S) URLs, fetches the raw bytes using a browser-like User-Agent so that
     * servers (e.g. Yamaha brochure URLs) serve the actual file rather than an HTML
     * redirect page.  Classpath / file URLs fall back to Spring's ResourceLoader.
     */
    private Resource fetchResource(String documentUrl) throws Exception {
        if (documentUrl.startsWith("http://") || documentUrl.startsWith("https://")) {
            RemoteDocumentResolver.ResolvedRemoteDocument resolved = remoteDocumentResolver.resolve(documentUrl);
            byte[] bytes = resolved.bytes();
            log.debug("Resolved remote document {} -> {} ({} bytes, {})",
                    documentUrl, resolved.resolvedUrl(), bytes.length, resolved.mimeType());
            bytesCache.put(documentUrl, bytes);
            return new ByteArrayResource(bytes) {
                @Override public String getFilename() {
                    return resolved.filename();
                }
            };
        }
        Resource resource = resourceLoader.getResource(documentUrl);
        if (!resource.exists()) throw new IllegalArgumentException("Resource not found: " + documentUrl);
        return resource;
    }

    @Override
    public boolean isDocumentLoaded(String documentUrl) {
        return loadedDocuments.containsKey(documentUrl);
    }

    @Override
    public List<String> listDocuments() {
        return Collections.unmodifiableList(new ArrayList<>(loadedDocuments.keySet()));
    }

    @Override
    public void deleteDocument(String documentUrl) {
        List<String> ids = loadedDocuments.get(documentUrl);
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Document not found in registry: " + documentUrl);
        }
        vectorStore.delete(ids);
        loadedDocuments.remove(documentUrl);
        bytesCache.remove(documentUrl);
        log.info("Deleted document and {} chunks for: {}", ids.size(), documentUrl);
    }

    @Override
    public void rebuildRegistryFromStore() {
        log.info("Rebuilding document registry from Milvus collection '{}'", collectionName);
        loadedDocuments.clear();

        try {
            QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr("doc_id != \"\"")
                    .withOutFields(List.of("doc_id", "metadata"))
                    .withLimit(16384L)
                    .build();

            var response = milvusClient.query(queryParam);
            if (response.getData() == null) {
                log.info("No documents found in vector store");
                return;
            }

            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            List<QueryResultsWrapper.RowRecord> rows = wrapper.getRowRecords();

            Map<String, List<String>> urlToIds = new HashMap<>();

            for (QueryResultsWrapper.RowRecord row : rows) {
                String docId = (String) row.get("doc_id");
                String metadataJson = (String) row.get("metadata");
                if (docId == null || metadataJson == null) continue;

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = objectMapper.readValue(metadataJson, Map.class);
                    String docUrl = (String) metadata.get(DOCUMENT_URL_KEY);
                    if (docUrl != null) {
                        urlToIds.computeIfAbsent(docUrl, k -> new ArrayList<>()).add(docId);
                    }
                } catch (Exception e) {
                    log.warn("Could not parse metadata for doc_id {}: {}", docId, e.getMessage());
                }
            }

            loadedDocuments.putAll(urlToIds);
            log.info("Registry rebuilt: {} document(s) found in store", urlToIds.size());

        } catch (Exception e) {
            log.warn("Could not rebuild registry from store: {}", e.getMessage());
        }
    }

    @Override
    public void loadDocument(String documentUrl) {
        log.info("Loading document from: {}", documentUrl);

        if (isDocumentLoaded(documentUrl)) {
            log.warn("Document already loaded, skipping: {}", documentUrl);
            throw new IllegalStateException("Document already loaded: " + documentUrl);
        }

        try {
            Resource resource = fetchResource(documentUrl);

            TikaDocumentReader documentReader = new TikaDocumentReader(resource);
            List<Document> documents = documentReader.get();

            log.debug("Read {} document(s) from {}", documents.size(), documentUrl);

            // Tag every chunk with the source URL for later registry reconstruction
            documents.forEach(doc -> doc.getMetadata().put(DOCUMENT_URL_KEY, documentUrl));

            TextSplitter textSplitter = new TokenTextSplitter();
            List<Document> splitDocuments = textSplitter.apply(documents);

            log.debug("Split into {} chunks", splitDocuments.size());

            vectorStore.add(splitDocuments);

            List<String> ids = splitDocuments.stream()
                    .map(Document::getId)
                    .collect(Collectors.toList());
            loadedDocuments.put(documentUrl, ids);

            log.info("Successfully loaded document from: {}", documentUrl);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load document from {}: {}", documentUrl, e.getMessage(), e);
            throw new RuntimeException("Failed to load document: " + documentUrl, e);
        }
    }

    @Override
    public void loadDocuments(List<String> documentUrls) {
        log.info("Loading {} documents", documentUrls.size());
        int successCount = 0;
        int failureCount = 0;
        for (String documentUrl : documentUrls) {
            try {
                loadDocument(documentUrl);
                successCount++;
            } catch (Exception e) {
                log.error("Failed to load document {}: {}", documentUrl, e.getMessage());
                failureCount++;
            }
        }
        log.info("Document loading complete. Success: {}, Failures: {}", successCount, failureCount);
    }

    @Override
    public void loadDocumentWithMetadata(String documentUrl, Map<String, Object> metadata) {
        log.info("Loading document with custom metadata from: {}", documentUrl);

        if (isDocumentLoaded(documentUrl)) {
            log.warn("Document already loaded, skipping: {}", documentUrl);
            throw new IllegalStateException("Document already loaded: " + documentUrl);
        }

        try {
            Resource resource = fetchResource(documentUrl);

            TikaDocumentReader documentReader = new TikaDocumentReader(resource);
            List<Document> documents = documentReader.get();

            documents.forEach(doc -> {
                doc.getMetadata().put(DOCUMENT_URL_KEY, documentUrl);
                if (metadata != null) {
                    metadata.forEach((key, value) -> doc.getMetadata().put(key, value));
                }
            });

            TextSplitter textSplitter = new TokenTextSplitter();
            List<Document> splitDocuments = textSplitter.apply(documents);

            vectorStore.add(splitDocuments);

            List<String> ids = splitDocuments.stream()
                    .map(Document::getId)
                    .collect(Collectors.toList());
            loadedDocuments.put(documentUrl, ids);

            log.info("Successfully loaded document with metadata from: {}", documentUrl);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load document from {}: {}", documentUrl, e.getMessage(), e);
            throw new RuntimeException("Failed to load document: " + documentUrl, e);
        }
    }

    @Override
    public byte[] getCachedBytes(String documentUrl) {
        return bytesCache.get(documentUrl);
    }
}
