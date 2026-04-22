package guru.springframework.springairagexpert.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
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
    public void loadDocument(String documentUrl) {
        log.info("Loading document from: {}", documentUrl);

        try {
            Resource resource = resourceLoader.getResource(documentUrl);

            if (!resource.exists()) {
                log.error("Resource does not exist: {}", documentUrl);
                throw new IllegalArgumentException("Resource not found: " + documentUrl);
            }

            TikaDocumentReader documentReader = new TikaDocumentReader(resource);
            List<Document> documents = documentReader.get();

            log.debug("Read {} document(s) from {}", documents.size(), documentUrl);

            TextSplitter textSplitter = new TokenTextSplitter();
            List<Document> splitDocuments = textSplitter.apply(documents);

            log.debug("Split into {} chunks", splitDocuments.size());

            vectorStore.add(splitDocuments);

            log.info("Successfully loaded document from: {}", documentUrl);
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

        try {
            Resource resource = resourceLoader.getResource(documentUrl);

            if (!resource.exists()) {
                log.error("Resource does not exist: {}", documentUrl);
                throw new IllegalArgumentException("Resource not found: " + documentUrl);
            }

            TikaDocumentReader documentReader = new TikaDocumentReader(resource);
            List<Document> documents = documentReader.get();

            // Add custom metadata to each document
            documents.forEach(doc -> {
                if (metadata != null) {
                    metadata.forEach((key, value) -> doc.getMetadata().put(key, value));
                }
            });

            TextSplitter textSplitter = new TokenTextSplitter();
            List<Document> splitDocuments = textSplitter.apply(documents);

            vectorStore.add(splitDocuments);

            log.info("Successfully loaded document with metadata from: {}", documentUrl);
        } catch (Exception e) {
            log.error("Failed to load document from {}: {}", documentUrl, e.getMessage(), e);
            throw new RuntimeException("Failed to load document: " + documentUrl, e);
        }
    }
}
