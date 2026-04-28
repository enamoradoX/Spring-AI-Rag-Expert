package guru.springframework.springairagexpert.model;

import java.util.List;

public record ChatAnalyticsSummaryResponse(
        ChatAnalyticsOverview overview,
        List<ChatAnalyticsOperationSummary> byOperation,
        List<ChatAnalyticsModelSummary> byModel,
        List<ChatUsageEvent> recentCalls
) {
}

