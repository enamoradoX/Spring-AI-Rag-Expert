package guru.springframework.springairagexpert.model;

import java.time.Instant;

public record ChatUsageEvent(
        Instant timestamp,
        String provider,
        String model,
        String operation,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long latencyMs,
        double estimatedCostUsd,
        boolean success,
        String finishReason,
        String requestId
) {
}

