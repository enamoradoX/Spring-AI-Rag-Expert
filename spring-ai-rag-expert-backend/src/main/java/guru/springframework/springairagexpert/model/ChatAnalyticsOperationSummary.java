package guru.springframework.springairagexpert.model;

public record ChatAnalyticsOperationSummary(
        String operation,
        long callCount,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        double estimatedCostUsd,
        double averageLatencyMs
) {
}

