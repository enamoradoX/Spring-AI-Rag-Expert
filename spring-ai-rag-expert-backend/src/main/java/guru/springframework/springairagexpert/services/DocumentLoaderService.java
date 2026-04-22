package guru.springframework.springairagexpert.services;

import java.util.List;

public interface DocumentLoaderService {

    List<String> listDocuments();

    /**
     * Load a single document from any source (classpath, file, http/https) into the vector store
     * @param documentUrl The resource URL (e.g., "classpath:/doc.pdf", "https://example.com/doc.pdf", "file:/path/to/doc.pdf")
     */
    boolean loadDocument(String documentUrl);

    /**
     * Load multiple documents from various sources into the vector store
     * @param documentUrls List of resource URLs
     */
    void loadDocuments(List<String> documentUrls);

    /**
     * Load a document with custom metadata
     * @param documentUrl The resource URL
     * @param metadata Custom metadata to attach to the document chunks
     */
    void loadDocumentWithMetadata(String documentUrl, java.util.Map<String, Object> metadata);

    void deleteDocument(String source);

}
