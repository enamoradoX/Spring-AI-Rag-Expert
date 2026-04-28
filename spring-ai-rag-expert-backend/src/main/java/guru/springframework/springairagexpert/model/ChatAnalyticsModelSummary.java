package guru.springframework.springairagexpert.model;

public record ChatAnalyticsModelSummary(
        String provider,
        String model,
        long callCount,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        double estimatedCostUsd,
        double averageLatencyMs
) {
}

