package guru.springframework.springairagexpert.services;

import guru.springframework.springairagexpert.config.ChatAnalyticsProperties;
import guru.springframework.springairagexpert.model.ChatAnalyticsSummaryResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUsageAnalyticsServiceTest {

    @Test
    void recordUsageAggregatesOverviewOperationModelAndRecentCalls() {
        ChatAnalyticsProperties properties = new ChatAnalyticsProperties();
        properties.setProvider("openai");
        properties.setModel("gpt-4-turbo");
        properties.setMaxRecentCalls(10);
        properties.getPricing().setPromptCostPer1mTokens(10.0);
        properties.getPricing().setCompletionCostPer1mTokens(30.0);

        ChatUsageAnalyticsService service = new ChatUsageAnalyticsService(new SimpleMeterRegistry(), properties);

        service.recordUsage("openai", "gpt-4-turbo", "answer", 4000, 100, 4100, 1500, true, "STOP", "req-1");
        service.recordUsage("openai", "gpt-4-turbo", "highlight", 500, 20, 520, 300, true, "STOP", "req-2");

        ChatAnalyticsSummaryResponse summary = service.getSummary();

        assertEquals(2, summary.overview().totalCalls());
        assertEquals(2, summary.overview().successfulCalls());
        assertEquals(0, summary.overview().failedCalls());
        assertEquals(4500, summary.overview().totalPromptTokens());
        assertEquals(120, summary.overview().totalCompletionTokens());
        assertEquals(4620, summary.overview().totalTokens());
        assertEquals(900.0, summary.overview().averageLatencyMs(), 0.001);
        assertEquals(0.0486, summary.overview().totalEstimatedCostUsd(), 0.0000001);

        assertEquals(2, summary.byOperation().size());
        assertEquals(1, summary.byModel().size());
        assertEquals("openai", summary.byModel().getFirst().provider());
        assertEquals("gpt-4-turbo", summary.byModel().getFirst().model());
        assertEquals(2, summary.byModel().getFirst().callCount());

        assertEquals(2, summary.recentCalls().size());
        assertEquals("highlight", summary.recentCalls().getFirst().operation());
        assertEquals("answer", summary.recentCalls().get(1).operation());
        assertTrue(summary.recentCalls().getFirst().success());
        assertNotNull(summary.recentCalls().getFirst().requestId());
    }

    @Test
    void failedUsageStillCountsCallsAndLatencyWithoutTokens() {
        ChatAnalyticsProperties properties = new ChatAnalyticsProperties();
        properties.setProvider("ollama");
        properties.setModel("mistral");
        properties.setMaxRecentCalls(5);

        ChatUsageAnalyticsService service = new ChatUsageAnalyticsService(new SimpleMeterRegistry(), properties);

        service.recordUsage("ollama", "mistral", "answer", null, null, null, 2500, false, null, null);

        ChatAnalyticsSummaryResponse summary = service.getSummary();

        assertEquals(1, summary.overview().totalCalls());
        assertEquals(0, summary.overview().successfulCalls());
        assertEquals(1, summary.overview().failedCalls());
        assertEquals(0, summary.overview().totalPromptTokens());
        assertEquals(0, summary.overview().totalCompletionTokens());
        assertEquals(0, summary.overview().totalTokens());
        assertEquals(2500.0, summary.overview().averageLatencyMs(), 0.001);
        assertEquals(0.0, summary.overview().totalEstimatedCostUsd(), 0.0000001);
        assertFalse(summary.recentCalls().isEmpty());
        assertFalse(summary.recentCalls().getFirst().success());
    }
}

