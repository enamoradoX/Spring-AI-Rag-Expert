//package guru.springframework.springairagexpert.config;
//
//import lombok.Data;
//import org.springframework.boot.context.properties.ConfigurationProperties;
//import org.springframework.context.annotation.Configuration;
//
//@Configuration
//@ConfigurationProperties(prefix = "aws.s3")
//@Data
//public class AwsS3Properties {
//
//    /**
//     * AWS region (e.g., us-east-1, eu-west-1)
//     */
//    private String region = "us-east-1";
//
//    /**
//     * AWS Access Key ID (optional if using IAM roles or default credential chain)
//     */
//    private String accessKeyId;
//
//    /**
//     * AWS Secret Access Key (optional if using IAM roles or default credential chain)
//     */
//    private String secretAccessKey;
//
//    /**
//     * S3 bucket name for document storage
//     */
//    private String bucketName;
//
//    /**
//     * Optional: custom S3 endpoint (for S3-compatible services like MinIO, LocalStack)
//     */
//    private String endpointOverride;
//
//    /**
//     * Whether to use path-style access (needed for MinIO and some S3-compatible services)
//     */
//    private boolean pathStyleAccess = false;
//}
