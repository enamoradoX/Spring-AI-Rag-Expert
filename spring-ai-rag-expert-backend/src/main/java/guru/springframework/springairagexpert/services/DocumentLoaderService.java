package guru.springframework.springairagexpert.services;

import java.util.List;

public interface DocumentLoaderService {

    void loadDocument(String documentUrl);

    void loadDocuments(List<String> documentUrls);

    void loadDocumentWithMetadata(String documentUrl, java.util.Map<String, Object> metadata);

    /**
     * Returns the list of all currently loaded document URLs.
     */
    List<String> listDocuments();

    /**
     * Deletes all vector store chunks associated with the given document URL.
     */
    void deleteDocument(String documentUrl);

    /**
     * Returns true if the document URL has already been loaded.
     */
    boolean isDocumentLoaded(String documentUrl);

    /**
     * Queries the vector store and rebuilds the in-memory registry
     * so all persisted documents are visible regardless of when they were loaded.
     */
    void rebuildRegistryFromStore();
}
