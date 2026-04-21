//package guru.springframework.springairagexpert.config;
//
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
//import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
//import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
//import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
//import software.amazon.awssdk.regions.Region;
//import software.amazon.awssdk.services.s3.S3Client;
//import software.amazon.awssdk.services.s3.S3ClientBuilder;
//import java.net.URI;
//
//
//@Configuration
//@RequiredArgsConstructor
//@Slf4j
//public class AwsS3Config {
//
//    private final AwsS3Properties awsS3Properties;
//
//    @Bean
//    @ConditionalOnProperty(prefix = "aws.s3", name = "enabled", havingValue = "true", matchIfMissing = false)
//    public S3Client s3Client() {
//        log.info("Configuring AWS S3 Client for region: {}", awsS3Properties.getRegion());
//
//        S3ClientBuilder s3ClientBuilder = S3Client.builder()
//                .region(Region.of(awsS3Properties.getRegion()))
//                .credentialsProvider(getCredentialsProvider());
//
//        // Custom endpoint (for MinIO, LocalStack, etc.)
//        if (awsS3Properties.getEndpointOverride() != null && !awsS3Properties.getEndpointOverride().isBlank()) {
//            log.info("Using custom S3 endpoint: {}", awsS3Properties.getEndpointOverride());
//            s3ClientBuilder.endpointOverride(URI.create(awsS3Properties.getEndpointOverride()));
//        }
//
//        // Path-style access (needed for MinIO and some S3-compatible services)
//        if (awsS3Properties.isPathStyleAccess()) {
//            log.info("Enabling path-style access for S3");
//            s3ClientBuilder.forcePathStyle(true);
//        }
//
//        S3Client s3Client = s3ClientBuilder.build();
//        log.info("AWS S3 Client configured successfully");
//
//        return s3Client;
//    }
//
//    /**
//     * Creates AWS credentials provider based on configuration.
//     * Priority:
//     * 1. Explicit access key and secret key from properties
//     * 2. Default credential chain (environment variables, system properties, IAM role, etc.)
//     */
//    private AwsCredentialsProvider getCredentialsProvider() {
//        if (awsS3Properties.getAccessKeyId() != null && !awsS3Properties.getAccessKeyId().isBlank()
//                && awsS3Properties.getSecretAccessKey() != null && !awsS3Properties.getSecretAccessKey().isBlank()) {
//
//            log.info("Using explicit AWS credentials from configuration");
//            AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(
//                    awsS3Properties.getAccessKeyId(),
//                    awsS3Properties.getSecretAccessKey()
//            );
//            return StaticCredentialsProvider.create(awsCredentials);
//        } else {
//            log.info("Using AWS default credentials chain (environment variables, IAM role, etc.)");
//            return DefaultCredentialsProvider.create();
//        }
//    }
//}
