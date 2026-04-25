package guru.springframework.springairagexpert.services;

import guru.springframework.springairagexpert.config.S3ClientFactory;
import guru.springframework.springairagexpert.config.S3RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3DocumentLoaderServiceImpl implements S3DocumentLoaderService {

    private final S3ClientFactory s3ClientFactory;
    private final S3RuntimeConfigService s3RuntimeConfigService;
    private final VectorStore vectorStore;
    private final DocumentLoaderService documentLoaderService;

    @org.springframework.beans.factory.annotation.Value("${sfg.aiapp.chunk-size:800}")
    private int chunkSize;

    private static final Pattern S3_URI_PATTERN = Pattern.compile("s3://([^/]+)/(.+)");

    @Override
    public void loadDocumentFromS3(String bucketName, String key) {
        try (S3Client s3Client = createS3Client()) {
            loadDocumentFromS3Internal(s3Client, bucketName, key, null);
        }
    }

    @Override
    public void loadDocumentsFromS3(String bucketName, List<String> keys) {
        log.info("Loading {} documents from S3 bucket: {}", keys.size(), bucketName);

        int successCount = 0;
        int failureCount = 0;

        try (S3Client s3Client = createS3Client()) {
            for (String key : keys) {
                try {
                    loadDocumentFromS3Internal(s3Client, bucketName, key, null);
                    successCount++;
                } catch (Exception e) {
                    log.error("Failed to load document s3://{}/{}: {}", bucketName, key, e.getMessage());
                    failureCount++;
                }
            }
        }

        log.info("S3 document loading complete. Success: {}, Failures: {}", successCount, failureCount);
    }

    @Override
    public void loadDocumentFromS3WithMetadata(String bucketName, String key, Map<String, Object> metadata) {
        try (S3Client s3Client = createS3Client()) {
            loadDocumentFromS3Internal(s3Client, bucketName, key, metadata);
        }
    }

    @Override
    public void loadDocumentsFromS3Prefix(String bucketName, String prefix) {
        log.info("Loading all documents from S3 prefix: s3://{}/{}", bucketName, prefix);

        try (S3Client s3Client = createS3Client()) {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
            List<S3Object> objects = listResponse.contents();

            log.info("Found {} objects in s3://{}/{}", objects.size(), bucketName, prefix);

            int successCount = 0;
            int failureCount = 0;

            for (S3Object object : objects) {
                String key = object.key();
                if (key.endsWith("/")) {
                    log.debug("Skipping directory: {}", key);
                    continue;
                }

                try {
                    loadDocumentFromS3Internal(s3Client, bucketName, key, null);
                    successCount++;
                } catch (Exception e) {
                    log.error("Failed to load document {}: {}", key, e.getMessage());
                    failureCount++;
                }
            }

            log.info("S3 prefix loading complete. Success: {}, Failures: {}", successCount, failureCount);

        } catch (Exception e) {
            log.error("Failed to list objects from S3 prefix s3://{}/{}: {}", bucketName, prefix, e.getMessage(), e);
            throw new RuntimeException("Failed to load documents from S3 prefix: " + prefix, e);
        }
    }

    @Override
    public void loadDocumentFromS3Uri(String s3Uri) {
        log.info("Parsing S3 URI: {}", s3Uri);

        Matcher matcher = S3_URI_PATTERN.matcher(s3Uri);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid S3 URI format. Expected: s3://bucket-name/path/to/file");
        }

        String bucketName = matcher.group(1);
        String key = matcher.group(2);

        loadDocumentFromS3(bucketName, key);
    }

    private void loadDocumentFromS3Internal(S3Client s3Client, String bucketName, String key, Map<String, Object> metadata) {
        log.info("Loading document from S3: s3://{}/{}", bucketName, key);
        String s3Uri = "s3://" + bucketName + "/" + key;
        Path tempFile = null;
        try {
            tempFile = downloadFromS3(s3Client, bucketName, key);

            // Cache bytes so the document viewer can serve them
            byte[] bytes = Files.readAllBytes(tempFile);
            documentLoaderService.cacheBytes(s3Uri, bytes);

            Map<String, Object> allMetadata = new java.util.HashMap<>();
            allMetadata.put("source", "s3");
            allMetadata.put("bucket", bucketName);
            allMetadata.put("key", key);
            allMetadata.put("document_url", s3Uri);
            if (metadata != null) {
                allMetadata.putAll(metadata);
            }

            List<String> ids = processDocument(tempFile, allMetadata);

            documentLoaderService.registerDocumentIds(s3Uri, ids);
            log.info("Successfully loaded document from S3: {}", s3Uri);

        } catch (Exception e) {
            log.error("Failed to load document from S3: {}: {}", s3Uri, e.getMessage(), e);
            throw new RuntimeException("Failed to load S3 document: " + key, e);
        } finally {
            cleanupTempFile(tempFile);
        }
    }

    /**
     * Download a file from S3 to a temporary location.
     * Validates that the downloaded content matches the expected file type (not an HTML error page).
     */
    private Path downloadFromS3(S3Client s3Client, String bucketName, String key) throws IOException {
        log.debug("Downloading from S3: s3://{}/{}", bucketName, key);

        // Create a unique temp path; delete the empty file so ResponseTransformer.toFile() can write to it
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : ".tmp";
        Path tempFile = Files.createTempFile("s3-doc-", extension);
        Files.delete(tempFile);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        software.amazon.awssdk.services.s3.model.GetObjectResponse s3Response =
                s3Client.getObject(getObjectRequest, ResponseTransformer.toFile(tempFile));

        log.info("S3 response - content-type: {}, content-length: {}, key: {}",
                s3Response.contentType(), s3Response.contentLength(), key);

        // Validate we received the actual file and not an HTML error/auth page
        byte[] header = new byte[16];
        try (var is = Files.newInputStream(tempFile)) {
            int read = is.read(header, 0, header.length);
            if (read > 0) {
                String headerStr = new String(header, 0, read, java.nio.charset.StandardCharsets.ISO_8859_1)
                        .toLowerCase(java.util.Locale.ROOT);
                if (headerStr.contains("<html") || headerStr.contains("<!doc")) {
                    Files.deleteIfExists(tempFile);
                    throw new IOException(
                            "S3 returned an HTML page instead of the file content. " +
                            "Check credentials, endpoint URL, and path-style access settings. " +
                            "S3 content-type was: " + s3Response.contentType());
                }
            }
        }

        log.debug("Downloaded to temporary file: {}", tempFile);
        return tempFile;
    }

    private S3Client createS3Client() {
        return s3ClientFactory.createClient(s3RuntimeConfigService.getConfig());
    }

    /**
     * Process document using TikaDocumentReader and store in vector database.
     * Returns the list of chunk IDs added to the vector store.
     */
    private List<String> processDocument(Path filePath, Map<String, Object> metadata) {
        log.debug("Processing document: {}", filePath);

        TikaDocumentReader documentReader = new TikaDocumentReader(new FileSystemResource(filePath));
        List<Document> documents = documentReader.get();

        documents.forEach(doc -> {
            if (metadata != null) {
                metadata.forEach((key, value) -> doc.getMetadata().put(key, value));
            }
        });

        log.debug("Read {} document(s)", documents.size());

        TextSplitter textSplitter = new TokenTextSplitter(chunkSize, 50, 5, 10000, true);
        List<Document> splitDocuments = textSplitter.apply(documents);

        log.debug("Split into {} chunks", splitDocuments.size());

        vectorStore.add(splitDocuments);

        log.debug("Added to vector store");

        return splitDocuments.stream().map(Document::getId).toList();
    }

    /**
     * Clean up temporary file
     */
    private void cleanupTempFile(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
                log.debug("Cleaned up temporary file: {}", tempFile);
            } catch (IOException e) {
                log.warn("Failed to delete temporary file: {}", tempFile, e);
            }
        }
    }
}
