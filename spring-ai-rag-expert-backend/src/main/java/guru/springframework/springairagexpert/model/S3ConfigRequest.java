package guru.springframework.springairagexpert.model;

public record S3ConfigRequest(
        String bucketName,
        String region,
        String accessKeyId,
        String secretAccessKey,
        String endpointOverride,
        Boolean pathStyleAccess,
        Boolean keepSecretAccessKey
) {
}

