package guru.springframework.springairagexpert.services;

import java.util.List;
import java.util.Map;

public interface S3DocumentLoaderService {

    /**
     * Load a document from S3 bucket into the vector store
     * @param bucketName The S3 bucket name
     * @param key The S3 object key (file path in bucket)
     */
    void loadDocumentFromS3(String bucketName, String key);

    /**
     * Load multiple documents from S3 bucket into the vector store
     * @param bucketName The S3 bucket name
     * @param keys List of S3 object keys
     */
    void loadDocumentsFromS3(String bucketName, List<String> keys);

    /**
     * Load a document from S3 with custom metadata
     * @param bucketName The S3 bucket name
     * @param key The S3 object key
     * @param metadata Custom metadata to attach to document chunks
     */
    void loadDocumentFromS3WithMetadata(String bucketName, String key, Map<String, Object> metadata);

    /**
     * Load all documents from an S3 bucket prefix (folder)
     * @param bucketName The S3 bucket name
     * @param prefix The prefix (folder path) to search
     */
    void loadDocumentsFromS3Prefix(String bucketName, String prefix);

    /**
     * Load a document using S3 URI format: s3://bucket-name/path/to/file.pdf
     * @param s3Uri The S3 URI
     */
    void loadDocumentFromS3Uri(String s3Uri);
}
