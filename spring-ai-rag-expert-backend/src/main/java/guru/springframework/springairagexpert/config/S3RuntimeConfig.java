package guru.springframework.springairagexpert.config;

public record S3RuntimeConfig(
        String region,
        String accessKeyId,
        String secretAccessKey,
        String bucketName,
        String endpointOverride,
        boolean pathStyleAccess
) {

    public static final String DEFAULT_REGION = "us-east-1";
    private static final String PLACEHOLDER_PREFIX = "OR_YOUR_OWN_";

    public S3RuntimeConfig {
        region = sanitizeRegion(region);
        accessKeyId = sanitize(accessKeyId);
        secretAccessKey = sanitize(secretAccessKey);
        bucketName = sanitize(bucketName);
        endpointOverride = sanitize(endpointOverride);
    }

    public static S3RuntimeConfig fromProperties(AwsS3Properties properties) {
        return new S3RuntimeConfig(
                properties.getRegion(),
                properties.getAccessKeyId(),
                properties.getSecretAccessKey(),
                properties.getBucketName(),
                properties.getEndpointOverride(),
                properties.isPathStyleAccess()
        );
    }

    public boolean isConfigured() {
        return hasText(bucketName);
    }

    public boolean hasSecretAccessKey() {
        return hasText(secretAccessKey);
    }

    public boolean usesDefaultCredentials() {
        return !hasText(accessKeyId) || !hasText(secretAccessKey);
    }

    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.toUpperCase().startsWith(PLACEHOLDER_PREFIX)) {
            return null;
        }

        return trimmed;
    }

    private static String sanitizeRegion(String region) {
        String sanitized = sanitize(region);
        return sanitized != null ? sanitized : DEFAULT_REGION;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

