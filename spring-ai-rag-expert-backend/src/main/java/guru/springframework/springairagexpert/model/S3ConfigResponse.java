package guru.springframework.springairagexpert.model;

import guru.springframework.springairagexpert.config.S3RuntimeConfig;

public record S3ConfigResponse(
        String bucketName,
        String region,
        String accessKeyId,
        String endpointOverride,
        boolean pathStyleAccess,
        boolean configured,
        boolean hasSecretAccessKey,
        boolean usesDefaultCredentials,
        String status
) {

    public static S3ConfigResponse from(S3RuntimeConfig config) {
        return new S3ConfigResponse(
                config.bucketName(),
                config.region(),
                config.accessKeyId(),
                config.endpointOverride(),
                config.pathStyleAccess(),
                config.isConfigured(),
                config.hasSecretAccessKey(),
                config.usesDefaultCredentials(),
                config.isConfigured() ? "configured" : "not_configured"
        );
    }
}

