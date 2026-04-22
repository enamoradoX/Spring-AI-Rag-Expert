package guru.springframework.springairagexpert.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
@Slf4j
public class DocumentLoaderServiceImpl implements DocumentLoaderService {

    final VectorStore vectorStore;
    final ResourceLoader resourceLoader;

    @Override
    public boolean loadDocument(String documentUrl) {
        log.info("Checking if document already exists: {}", documentUrl);
        String sourceName = resolveSourceName(documentUrl);

        if (isAlreadyLoaded(sourceName)) {
            log.info("Document already exists in vector store, skipping: [{}]", sourceName);
            return false; // skipped
        }

        log.info("Document not found, loading now: [{}]", sourceName);

        try {
            Resource resource = resourceLoader.getResource(documentUrl);
            if (!resource.exists()) {
                log.error("Resource does not exist: {}", documentUrl);
                throw new IllegalArgumentException("Resource not found: " + documentUrl);
            }

            TikaDocumentReader documentReader = new TikaDocumentReader(resource);
            List<Document> documents = documentReader.get();
            documents.forEach(doc -> doc.getMetadata().put("source", sourceName));

            log.debug("Read {} document(s) from {}", documents.size(), documentUrl);

            TextSplitter textSplitter = new TokenTextSplitter();
            List<Document> splitDocuments = textSplitter.apply(documents);
            splitDocuments.forEach(doc -> doc.getMetadata().put("source", sourceName));

            log.debug("Split into {} chunks", splitDocuments.size());

            vectorStore.add(splitDocuments);
            log.info("Successfully loaded document: [{}]", sourceName);
            return true; // loaded
        } catch (Exception e) {
            log.error("Failed to load document from {}: {}", documentUrl, e.getMessage(), e);
            throw new RuntimeException("Failed to load document: " + documentUrl, e);
        }
    }

    @Override
    public void loadDocuments(List<String> documentUrls) {
        log.info("Loading {} documents", documentUrls.size());

        int successCount = 0;
        int skippedCount = 0;
        int failureCount = 0;

        for (String documentUrl : documentUrls) {
            try {
                String sourceName = resolveSourceName(documentUrl);
                if (isAlreadyLoaded(sourceName)) {
                    log.info("Document already exists, skipping: [{}]", sourceName);
                    skippedCount++;
                } else {
                    loadDocument(documentUrl);
                    successCount++;
                }
            } catch (Exception e) {
                log.error("Failed to load document {}: {}", documentUrl, e.getMessage());
                failureCount++;
            }
        }

        log.info("Document loading complete. Loaded: {}, Skipped: {}, Failed: {}",
                successCount, skippedCount, failureCount);
    }

    @Override
    public void loadDocumentWithMetadata(String documentUrl, Map<String, Object> metadata) {
        String sourceName = resolveSourceName(documentUrl);
        log.info("Checking if document already exists: [{}]", sourceName);

        if (isAlreadyLoaded(sourceName)) {
            log.info("Document already exists in vector store, skipping: [{}]", sourceName);
            return;
        }

        log.info("Document not found, loading with custom metadata: [{}]", sourceName);

        try {
            Resource resource = resourceLoader.getResource(documentUrl);

            if (!resource.exists()) {
                log.error("Resource does not exist: {}", documentUrl);
                throw new IllegalArgumentException("Resource not found: " + documentUrl);
            }

            TikaDocumentReader documentReader = new TikaDocumentReader(resource);
            List<Document> documents = documentReader.get();

            documents.forEach(doc -> {
                doc.getMetadata().put("source", sourceName);
                if (metadata != null) {
                    metadata.forEach((key, value) -> doc.getMetadata().put(key, value));
                }
            });

            TextSplitter textSplitter = new TokenTextSplitter();
            List<Document> splitDocuments = textSplitter.apply(documents);

            splitDocuments.forEach(doc -> {
                doc.getMetadata().put("source", sourceName);
                if (metadata != null) {
                    metadata.forEach((key, value) -> doc.getMetadata().put(key, value));
                }
            });

            vectorStore.add(splitDocuments);
            log.info("Successfully loaded document with metadata: [{}]", sourceName);
        } catch (Exception e) {
            log.error("Failed to load document from {}: {}", documentUrl, e.getMessage(), e);
            throw new RuntimeException("Failed to load document: " + documentUrl, e);
        }
    }

    /**
     * Use the full URL as source key for URLs, filename for classpath/file resources.
     * This ensures https://yamahaoutboards.com/getmedia/b6d82df1-... is uniquely identified.
     */
    private String resolveSourceName(String documentUrl) {
        if (documentUrl.startsWith("http://") || documentUrl.startsWith("https://")) {
            return documentUrl; // Full URL is the unique key
        }
        // For classpath/file resources, use just the filename
        Resource resource = resourceLoader.getResource(documentUrl);
        String filename = resource.getFilename();
        return (filename != null && !filename.isBlank()) ? filename : documentUrl;
    }

    /**
     * Check if a document with this source name already exists in the vector store.
     */
    private boolean isAlreadyLoaded(String sourceName) {
        try {
            List<Document> existing = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(sourceName)
                            .topK(1)
                            .filterExpression("source == '" + sourceName + "'")
                            .build()
            );
            return !existing.isEmpty();
        } catch (Exception e) {
            log.warn("Could not check if document exists, will attempt to load: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void deleteDocument(String source) {
        log.info("Finding documents to delete for source: [{}]", source);

        // Step 1: Find all document chunks by source using similarity search
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(source)
                        .topK(1000)
                        .filterExpression("source == '" + source + "'")
                        .build()
        );

        if (documents.isEmpty()) {
            log.warn("No documents found for source: [{}]", source);
            return;
        }

        // Step 2: Extract IDs and delete by ID (avoids Milvus special char issue with URLs)
        List<String> ids = documents.stream()
                .map(Document::getId)
                .toList();

        log.info("Deleting {} chunks for source: [{}]", ids.size(), source);

        vectorStore.delete(ids);

        log.info("Successfully deleted {} chunks for source: [{}]", ids.size(), source);
    }

    @Override
    public List<String> listDocuments() {
        log.info("Listing all documents in vector store");

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("document")
                        .topK(1000)
                        .build()
        );

        return results.stream()
                .map(doc -> (String) doc.getMetadata().get("source"))
                .filter(source -> source != null && !source.isBlank())
                .distinct()
                .sorted()
                .toList();
    }
}