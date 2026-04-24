package guru.springframework.springairagexpert.controllers;

import guru.springframework.springairagexpert.config.AwsS3Properties;
import guru.springframework.springairagexpert.model.DocumentLoadResponse;
import guru.springframework.springairagexpert.model.S3DocumentLoadRequest;
import guru.springframework.springairagexpert.services.S3DocumentLoaderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/s3")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(S3DocumentLoaderService.class)
public class S3DocumentLoaderController {

    private final S3DocumentLoaderService s3DocumentLoaderService;
    private final AwsS3Properties awsS3Properties;

    /** Returns S3 connection metadata so the UI can display connection status. */
    @GetMapping("/config")
    public Map<String, String> getConfig() {
        return Map.of(
            "bucket",   awsS3Properties.getBucketName()   != null ? awsS3Properties.getBucketName()   : "",
            "endpoint", awsS3Properties.getEndpointOverride() != null ? awsS3Properties.getEndpointOverride() : "AWS S3",
            "region",   awsS3Properties.getRegion()       != null ? awsS3Properties.getRegion()       : "us-east-1",
            "status",   "connected"
        );
    }

    @PostMapping("/load")
    public DocumentLoadResponse loadFromS3(@RequestBody S3DocumentLoadRequest request) {
        try {
            // Load using S3 URI format (s3://bucket/key)
            if (request.s3Uri() != null && !request.s3Uri().isBlank()) {
                s3DocumentLoaderService.loadDocumentFromS3Uri(request.s3Uri());
                return new DocumentLoadResponse("Document loaded successfully from: " + request.s3Uri(), true);
            }

            // Resolve bucket: use request value if provided, otherwise fall back to configured bucket
            String resolvedBucket = (request.bucketName() != null && !request.bucketName().isBlank())
                    ? request.bucketName()
                    : awsS3Properties.getBucketName();

            if (resolvedBucket == null || resolvedBucket.isBlank()) {
                return new DocumentLoadResponse("No bucket configured. Set aws.s3.bucketName in application config.", false);
            }

            // Load all documents from a prefix (folder)
            if (request.prefix() != null && !request.prefix().isBlank()) {
                s3DocumentLoaderService.loadDocumentsFromS3Prefix(resolvedBucket, request.prefix());
                return new DocumentLoadResponse("Documents loaded from prefix: s3://" + resolvedBucket + "/" + request.prefix(), true);
            }

            // Load single document with optional metadata
            if (request.key() != null && !request.key().isBlank()) {
                if (request.metadata() != null && !request.metadata().isEmpty()) {
                    s3DocumentLoaderService.loadDocumentFromS3WithMetadata(resolvedBucket, request.key(), request.metadata());
                } else {
                    s3DocumentLoaderService.loadDocumentFromS3(resolvedBucket, request.key());
                }
                return new DocumentLoadResponse("Document loaded from: s3://" + resolvedBucket + "/" + request.key(), true);
            }

            // Load multiple documents
            if (request.keys() != null && !request.keys().isEmpty()) {
                s3DocumentLoaderService.loadDocumentsFromS3(resolvedBucket, request.keys());
                return new DocumentLoadResponse("Loaded " + request.keys().size() + " documents from S3", true);
            }

            return new DocumentLoadResponse("No valid S3 parameters provided", false);

        } catch (Exception e) {
            log.error("Error loading document from S3: {}", e.getMessage(), e);
            return new DocumentLoadResponse("Failed to load from S3: " + e.getMessage(), false);
        }
    }

    @PostMapping("/load-simple")
    public DocumentLoadResponse loadSimple(@RequestParam String bucket, @RequestParam String key) {
        try {
            s3DocumentLoaderService.loadDocumentFromS3(bucket, key);
            return new DocumentLoadResponse("Document loaded from s3://" + bucket + "/" + key, true);
        } catch (Exception e) {
            log.error("Error loading document from S3: {}", e.getMessage(), e);
            return new DocumentLoadResponse("Failed to load from S3: " + e.getMessage(), false);
        }
    }

    @PostMapping("/load-uri")
    public DocumentLoadResponse loadByUri(@RequestParam String uri) {
        try {
            s3DocumentLoaderService.loadDocumentFromS3Uri(uri);
            return new DocumentLoadResponse("Document loaded from: " + uri, true);
        } catch (Exception e) {
            log.error("Error loading document from S3: {}", e.getMessage(), e);
            return new DocumentLoadResponse("Failed to load from S3: " + e.getMessage(), false);
        }
    }
}
