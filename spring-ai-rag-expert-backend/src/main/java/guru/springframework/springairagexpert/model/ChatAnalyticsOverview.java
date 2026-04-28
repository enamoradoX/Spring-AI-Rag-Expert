package guru.springframework.springairagexpert.model;

public record ChatAnalyticsOverview(
        long totalCalls,
        long successfulCalls,
        long failedCalls,
        long totalPromptTokens,
        long totalCompletionTokens,
        long totalTokens,
        double totalEstimatedCostUsd,
        double averageLatencyMs
) {
}

