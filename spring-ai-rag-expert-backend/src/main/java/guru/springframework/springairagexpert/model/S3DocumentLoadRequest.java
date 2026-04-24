package guru.springframework.springairagexpert.model;

import java.util.List;
import java.util.Map;

public record S3DocumentLoadRequest(
        String bucketName,
        String key,
        List<String> keys,
        String prefix,
        String s3Uri,
        Map<String, Object> metadata
) {
}
