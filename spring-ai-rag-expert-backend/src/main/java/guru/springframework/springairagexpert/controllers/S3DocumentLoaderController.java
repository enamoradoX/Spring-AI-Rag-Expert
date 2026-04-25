package guru.springframework.springairagexpert.controllers;

import guru.springframework.springairagexpert.config.S3RuntimeConfigService;
import guru.springframework.springairagexpert.model.DocumentLoadResponse;
import guru.springframework.springairagexpert.model.S3ConfigRequest;
import guru.springframework.springairagexpert.model.S3ConfigResponse;
import guru.springframework.springairagexpert.model.S3DocumentLoadRequest;
import guru.springframework.springairagexpert.services.S3DocumentLoaderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/s3")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(S3DocumentLoaderService.class)
public class S3DocumentLoaderController {

    private final S3DocumentLoaderService s3DocumentLoaderService;
    private final S3RuntimeConfigService s3RuntimeConfigService;

    /** Returns S3 connection metadata so the UI can display connection status. */
    @GetMapping("/config")
    public S3ConfigResponse getConfig() {
        return S3ConfigResponse.from(s3RuntimeConfigService.getConfig());
    }

    @PutMapping("/config")
    public S3ConfigResponse updateConfig(@RequestBody S3ConfigRequest request) {
        return S3ConfigResponse.from(s3RuntimeConfigService.updateConfig(request));
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
            String resolvedBucket = s3RuntimeConfigService.resolveBucket(request.bucketName());

            if (resolvedBucket == null || resolvedBucket.isBlank()) {
                return new DocumentLoadResponse("No S3 bucket configured. Open Configure S3 in the app and save a bucket first.", false);
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
