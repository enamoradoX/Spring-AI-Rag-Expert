package guru.springframework.springairagexpert.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

@Component
@Slf4j
public class S3ClientFactory {

    public S3Client createClient(S3RuntimeConfig config) {
        log.info("Creating runtime S3 client for region: {}", config.region());

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(config.region()))
                .credentialsProvider(getCredentialsProvider(config));

        if (config.endpointOverride() != null) {
            log.info("Using custom S3 endpoint: {}", config.endpointOverride());
            builder.endpointOverride(URI.create(config.endpointOverride()));
        }

        if (config.pathStyleAccess()) {
            log.info("Enabling path-style access for S3");
            builder.forcePathStyle(true);
        }

        return builder.build();
    }

    private AwsCredentialsProvider getCredentialsProvider(S3RuntimeConfig config) {
        if (config.accessKeyId() != null && config.secretAccessKey() != null) {
            log.info("Using explicit AWS credentials from runtime configuration");
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey()));
        }

        log.info("Using AWS default credentials chain (environment variables, system properties, IAM role, etc.)");
        return DefaultCredentialsProvider.create();
    }
}

