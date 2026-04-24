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

    /**
     * Returns the raw bytes of a document that was previously loaded from an HTTP(S) URL,
     * or null if the URL was not loaded through the service (e.g. classpath resources).
     */
    byte[] getCachedBytes(String documentUrl);

    /**
     * Stores raw bytes in the cache for a given URL (e.g. for S3 documents loaded externally).
     */
    void cacheBytes(String documentUrl, byte[] bytes);

    /**
     * Registers a document URL and its associated vector store chunk IDs
     * so it appears in {@link #listDocuments()} and can be deleted later.
     */
    void registerDocumentIds(String documentUrl, List<String> ids);
}
