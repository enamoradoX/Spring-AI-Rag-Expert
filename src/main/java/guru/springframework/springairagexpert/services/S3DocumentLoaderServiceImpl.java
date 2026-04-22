//package guru.springframework.springairagexpert.services;
//
//import guru.springframework.springairagexpert.config.AwsS3Properties;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.ai.document.Document;
//import org.springframework.ai.reader.tika.TikaDocumentReader;
//import org.springframework.ai.transformer.splitter.TextSplitter;
//import org.springframework.ai.transformer.splitter.TokenTextSplitter;
//import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
//import org.springframework.core.io.FileSystemResource;
//import org.springframework.stereotype.Service;
//import software.amazon.awssdk.core.sync.ResponseTransformer;
//import software.amazon.awssdk.services.s3.S3Client;
//import software.amazon.awssdk.services.s3.model.*;
//import java.io.IOException;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.List;
//import java.util.Map;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//@Service
//@RequiredArgsConstructor
//@Slf4j
//@ConditionalOnBean(S3Client.class)
//public class S3DocumentLoaderServiceImpl implements S3DocumentLoaderService {
//
//    private final S3Client s3Client;
//    private final VectorStore vectorStore;
//    private final AwsS3Properties awsS3Properties;
//
//    private static final Pattern S3_URI_PATTERN = Pattern.compile("s3://([^/]+)/(.+)");
//
//    @Override
//    public void loadDocumentFromS3(String bucketName, String key) {
//        log.info("Loading document from S3: s3://{}/{}", bucketName, key);
//
//        Path tempFile = null;
//        try {
//            // Download from S3 to temporary file
//            tempFile = downloadFromS3(bucketName, key);
//
//            // Process the document
//            processDocument(tempFile, Map.of(
//                    "source", "s3",
//                    "bucket", bucketName,
//                    "key", key
//            ));
//
//            log.info("Successfully loaded document from S3: s3://{}/{}", bucketName, key);
//
//        } catch (Exception e) {
//            log.error("Failed to load document from S3: s3://{}/{}: {}", bucketName, key, e.getMessage(), e);
//            throw new RuntimeException("Failed to load S3 document: " + key, e);
//        } finally {
//            cleanupTempFile(tempFile);
//        }
//    }
//
//    @Override
//    public void loadDocumentsFromS3(String bucketName, List<String> keys) {
//        log.info("Loading {} documents from S3 bucket: {}", keys.size(), bucketName);
//
//        int successCount = 0;
//        int failureCount = 0;
//
//        for (String key : keys) {
//            try {
//                loadDocumentFromS3(bucketName, key);
//                successCount++;
//            } catch (Exception e) {
//                log.error("Failed to load document s3://{}/{}: {}", bucketName, key, e.getMessage());
//                failureCount++;
//            }
//        }
//
//        log.info("S3 document loading complete. Success: {}, Failures: {}", successCount, failureCount);
//    }
//
//    @Override
//    public void loadDocumentFromS3WithMetadata(String bucketName, String key, Map<String, Object> metadata) {
//        log.info("Loading document with custom metadata from S3: s3://{}/{}", bucketName, key);
//
//        Path tempFile = null;
//        try {
//            // Download from S3 to temporary file
//            tempFile = downloadFromS3(bucketName, key);
//
//            // Merge default metadata with custom metadata
//            Map<String, Object> allMetadata = new java.util.HashMap<>();
//            allMetadata.put("source", "s3");
//            allMetadata.put("bucket", bucketName);
//            allMetadata.put("key", key);
//            if (metadata != null) {
//                allMetadata.putAll(metadata);
//            }
//
//            // Process the document
//            processDocument(tempFile, allMetadata);
//
//            log.info("Successfully loaded document with metadata from S3: s3://{}/{}", bucketName, key);
//
//        } catch (Exception e) {
//            log.error("Failed to load document from S3: s3://{}/{}: {}", bucketName, key, e.getMessage(), e);
//            throw new RuntimeException("Failed to load S3 document: " + key, e);
//        } finally {
//            cleanupTempFile(tempFile);
//        }
//    }
//
//    @Override
//    public void loadDocumentsFromS3Prefix(String bucketName, String prefix) {
//        log.info("Loading all documents from S3 prefix: s3://{}/{}", bucketName, prefix);
//
//        try {
//            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
//                    .bucket(bucketName)
//                    .prefix(prefix)
//                    .build();
//
//            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
//            List<S3Object> objects = listResponse.contents();
//
//            log.info("Found {} objects in s3://{}/{}", objects.size(), bucketName, prefix);
//
//            int successCount = 0;
//            int failureCount = 0;
//
//            for (S3Object object : objects) {
//                String key = object.key();
//                // Skip directories (keys ending with '/')
//                if (key.endsWith("/")) {
//                    log.debug("Skipping directory: {}", key);
//                    continue;
//                }
//
//                try {
//                    loadDocumentFromS3(bucketName, key);
//                    successCount++;
//                } catch (Exception e) {
//                    log.error("Failed to load document {}: {}", key, e.getMessage());
//                    failureCount++;
//                }
//            }
//
//            log.info("S3 prefix loading complete. Success: {}, Failures: {}", successCount, failureCount);
//
//        } catch (Exception e) {
//            log.error("Failed to list objects from S3 prefix s3://{}/{}: {}", bucketName, prefix, e.getMessage(), e);
//            throw new RuntimeException("Failed to load documents from S3 prefix: " + prefix, e);
//        }
//    }
//
//    @Override
//    public void loadDocumentFromS3Uri(String s3Uri) {
//        log.info("Parsing S3 URI: {}", s3Uri);
//
//        Matcher matcher = S3_URI_PATTERN.matcher(s3Uri);
//        if (!matcher.matches()) {
//            throw new IllegalArgumentException("Invalid S3 URI format. Expected: s3://bucket-name/path/to/file");
//        }
//
//        String bucketName = matcher.group(1);
//        String key = matcher.group(2);
//
//        loadDocumentFromS3(bucketName, key);
//    }
//
//    /**
//     * Download a file from S3 to a temporary location
//     */
//    private Path downloadFromS3(String bucketName, String key) throws IOException {
//        log.debug("Downloading from S3: s3://{}/{}", bucketName, key);
//
//        // Create temp file with appropriate extension
//        String fileName = key.substring(key.lastIndexOf('/') + 1);
//        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : ".tmp";
//        Path tempFile = Files.createTempFile("s3-doc-", extension);
//
//        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
//                .bucket(bucketName)
//                .key(key)
//                .build();
//
//        s3Client.getObject(getObjectRequest, ResponseTransformer.toFile(tempFile));
//
//        log.debug("Downloaded to temporary file: {}", tempFile);
//        return tempFile;
//    }
//
//    /**
//     * Process document using TikaDocumentReader and store in vector database
//     */
//    private void processDocument(Path filePath, Map<String, Object> metadata) {
//        log.debug("Processing document: {}", filePath);
//
//        TikaDocumentReader documentReader = new TikaDocumentReader(new FileSystemResource(filePath));
//        List<Document> documents = documentReader.get();
//
//        // Add metadata to each document
//        documents.forEach(doc -> {
//            if (metadata != null) {
//                metadata.forEach((key, value) -> doc.getMetadata().put(key, value));
//            }
//        });
//
//        log.debug("Read {} document(s)", documents.size());
//
//        TextSplitter textSplitter = new TokenTextSplitter();
//        List<Document> splitDocuments = textSplitter.apply(documents);
//
//        log.debug("Split into {} chunks", splitDocuments.size());
//
//        vectorStore.add(splitDocuments);
//
//        log.debug("Added to vector store");
//    }
//
//    /**
//     * Clean up temporary file
//     */
//    private void cleanupTempFile(Path tempFile) {
//        if (tempFile != null) {
//            try {
//                Files.deleteIfExists(tempFile);
//                log.debug("Cleaned up temporary file: {}", tempFile);
//            } catch (IOException e) {
//                log.warn("Failed to delete temporary file: {}", tempFile, e);
//            }
//        }
//    }
//}
