package guru.springframework.springairagexpert.model;

import java.util.List;
import java.util.Map;

public record DocumentLoadRequest(
        String documentUrl,
        List<String> documentUrls,
        Map<String, Object> metadata
) {
}
