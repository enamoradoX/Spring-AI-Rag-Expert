package guru.springframework.springairagexpert.config;

import guru.springframework.springairagexpert.model.S3ConfigRequest;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class S3RuntimeConfigService {

    private final AtomicReference<S3RuntimeConfig> configRef;

    public S3RuntimeConfigService(AwsS3Properties awsS3Properties) {
        this.configRef = new AtomicReference<>(S3RuntimeConfig.fromProperties(awsS3Properties));
    }

    public S3RuntimeConfig getConfig() {
        return configRef.get();
    }

    public S3RuntimeConfig updateConfig(S3ConfigRequest request) {
        S3RuntimeConfig existing = configRef.get();

        String accessKeyId = S3RuntimeConfig.sanitize(request.accessKeyId());
        boolean keepExistingSecret = Boolean.TRUE.equals(request.keepSecretAccessKey());
        String requestedSecret = S3RuntimeConfig.sanitize(request.secretAccessKey());
        String secretAccessKey;

        if (accessKeyId == null && requestedSecret == null) {
            secretAccessKey = null;
        } else if (requestedSecret != null) {
            secretAccessKey = requestedSecret;
        } else {
            secretAccessKey = keepExistingSecret ? existing.secretAccessKey() : null;
        }

        S3RuntimeConfig updated = new S3RuntimeConfig(
                request.region(),
                accessKeyId,
                secretAccessKey,
                request.bucketName(),
                request.endpointOverride(),
                Boolean.TRUE.equals(request.pathStyleAccess())
        );

        validate(updated);
        configRef.set(updated);
        return updated;
    }

    public String resolveBucket(String bucketName) {
        String requestedBucket = S3RuntimeConfig.sanitize(bucketName);
        return requestedBucket != null ? requestedBucket : getConfig().bucketName();
    }

    private void validate(S3RuntimeConfig config) {
        boolean hasAccessKey = config.accessKeyId() != null;
        boolean hasSecretAccessKey = config.secretAccessKey() != null;

        if (hasAccessKey != hasSecretAccessKey) {
            throw new IllegalArgumentException("Provide both Access Key ID and Secret Access Key, or leave both blank to use the default AWS credential chain");
        }

        if (config.endpointOverride() != null) {
            URI endpoint = URI.create(config.endpointOverride());
            if (!endpoint.isAbsolute() || endpoint.getHost() == null) {
                throw new IllegalArgumentException("S3 endpoint must be an absolute URI such as http://localhost:9000");
            }
        }
    }
}

