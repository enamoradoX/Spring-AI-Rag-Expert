package guru.springframework.springairagexpert.services;

import guru.springframework.springairagexpert.config.ChatAnalyticsProperties;
import guru.springframework.springairagexpert.model.ChatAnalyticsModelSummary;
import guru.springframework.springairagexpert.model.ChatAnalyticsOperationSummary;
import guru.springframework.springairagexpert.model.ChatAnalyticsOverview;
import guru.springframework.springairagexpert.model.ChatAnalyticsSummaryResponse;
import guru.springframework.springairagexpert.model.ChatUsageEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ChatUsageAnalyticsService {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;
    private final ChatAnalyticsProperties properties;

    private final UsageAccumulator overall = new UsageAccumulator();
    private final Map<String, UsageAccumulator> operationTotals = new LinkedHashMap<>();
    private final Map<String, ModelUsageAccumulator> modelTotals = new LinkedHashMap<>();
    private final Deque<ChatUsageEvent> recentCalls = new ArrayDeque<>();

    public ChatUsageAnalyticsService(MeterRegistry meterRegistry, ChatAnalyticsProperties properties) {
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    public synchronized void recordUsage(String provider,
                                         String model,
                                         String operation,
                                         Integer promptTokens,
                                         Integer completionTokens,
                                         Integer totalTokens,
                                         long latencyMs,
                                         boolean success,
                                         String finishReason,
                                         String requestId) {
        if (!properties.isEnabled()) {
            return;
        }

        String resolvedProvider = normalize(provider, properties.getProvider());
        String resolvedModel = normalize(model, properties.getModel());
        String resolvedOperation = normalize(operation, UNKNOWN);
        int safePromptTokens = sanitizeNumber(promptTokens);
        int safeCompletionTokens = sanitizeNumber(completionTokens);
        int safeTotalTokens = totalTokens != null
                ? Math.max(totalTokens, 0)
                : safePromptTokens + safeCompletionTokens;
        long safeLatencyMs = Math.max(latencyMs, 0);
        double estimatedCostUsd = calculateEstimatedCostUsd(safePromptTokens, safeCompletionTokens);

        ChatUsageEvent event = new ChatUsageEvent(
                Instant.now(),
                resolvedProvider,
                resolvedModel,
                resolvedOperation,
                safePromptTokens,
                safeCompletionTokens,
                safeTotalTokens,
                safeLatencyMs,
                estimatedCostUsd,
                success,
                blankToNull(finishReason),
                blankToNull(requestId)
        );

        overall.add(event);
        operationTotals.computeIfAbsent(resolvedOperation, key -> new UsageAccumulator()).add(event);

        String modelKey = resolvedProvider + "||" + resolvedModel;
        modelTotals.computeIfAbsent(modelKey, key -> new ModelUsageAccumulator(resolvedProvider, resolvedModel)).add(event);

        if (properties.getMaxRecentCalls() > 0) {
            recentCalls.addFirst(event);
            while (recentCalls.size() > properties.getMaxRecentCalls()) {
                recentCalls.removeLast();
            }
        }

        recordMicrometerMetrics(event);
    }

    public synchronized ChatAnalyticsSummaryResponse getSummary() {
        if (!properties.isEnabled()) {
            return new ChatAnalyticsSummaryResponse(
                    new ChatAnalyticsOverview(0, 0, 0, 0, 0, 0, 0, 0),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        ChatAnalyticsOverview overview = new ChatAnalyticsOverview(
                overall.totalCalls,
                overall.successfulCalls,
                overall.failedCalls,
                overall.promptTokens,
                overall.completionTokens,
                overall.totalTokens,
                overall.estimatedCostUsd,
                overall.averageLatencyMs()
        );

        List<ChatAnalyticsOperationSummary> byOperation = operationTotals.entrySet().stream()
                .map(entry -> new ChatAnalyticsOperationSummary(
                        entry.getKey(),
                        entry.getValue().totalCalls,
                        entry.getValue().promptTokens,
                        entry.getValue().completionTokens,
                        entry.getValue().totalTokens,
                        entry.getValue().estimatedCostUsd,
                        entry.getValue().averageLatencyMs()
                ))
                .sorted(Comparator.comparing(ChatAnalyticsOperationSummary::callCount).reversed()
                        .thenComparing(ChatAnalyticsOperationSummary::operation))
                .toList();

        List<ChatAnalyticsModelSummary> byModel = modelTotals.values().stream()
                .map(value -> new ChatAnalyticsModelSummary(
                        value.provider,
                        value.model,
                        value.totalCalls,
                        value.promptTokens,
                        value.completionTokens,
                        value.totalTokens,
                        value.estimatedCostUsd,
                        value.averageLatencyMs()
                ))
                .sorted(Comparator.comparing(ChatAnalyticsModelSummary::callCount).reversed()
                        .thenComparing(ChatAnalyticsModelSummary::provider)
                        .thenComparing(ChatAnalyticsModelSummary::model))
                .toList();

        return new ChatAnalyticsSummaryResponse(
                overview,
                byOperation,
                byModel,
                new ArrayList<>(recentCalls)
        );
    }

    private void recordMicrometerMetrics(ChatUsageEvent event) {
        String[] commonTags = new String[] {
                "provider", event.provider(),
                "model", event.model(),
                "operation", event.operation()
        };
        String status = event.success() ? "success" : "error";

        Counter.builder("ai.chat.calls")
                .tags("provider", event.provider(), "model", event.model(), "operation", event.operation(), "status", status)
                .register(meterRegistry)
                .increment();

        Timer.builder("ai.chat.latency")
                .tags("provider", event.provider(), "model", event.model(), "operation", event.operation(), "status", status)
                .register(meterRegistry)
                .record(event.latencyMs(), TimeUnit.MILLISECONDS);

        DistributionSummary.builder("ai.chat.tokens.prompt")
                .baseUnit("tokens")
                .tags(commonTags)
                .register(meterRegistry)
                .record(event.promptTokens());

        DistributionSummary.builder("ai.chat.tokens.completion")
                .baseUnit("tokens")
                .tags(commonTags)
                .register(meterRegistry)
                .record(event.completionTokens());

        DistributionSummary.builder("ai.chat.tokens.total")
                .baseUnit("tokens")
                .tags(commonTags)
                .register(meterRegistry)
                .record(event.totalTokens());

        DistributionSummary.builder("ai.chat.cost.usd")
                .baseUnit("usd")
                .tags(commonTags)
                .register(meterRegistry)
                .record(event.estimatedCostUsd());
    }

    private double calculateEstimatedCostUsd(int promptTokens, int completionTokens) {
        ChatAnalyticsProperties.Pricing pricing = properties.getPricing();
        if (pricing == null) {
            return 0;
        }

        double promptCost = (promptTokens / 1_000_000d) * pricing.getPromptCostPer1mTokens();
        double completionCost = (completionTokens / 1_000_000d) * pricing.getCompletionCostPer1mTokens();
        return promptCost + completionCost;
    }

    private static int sanitizeNumber(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    private static String normalize(String value, String fallback) {
        String trimmedValue = blankToNull(value);
        if (trimmedValue != null) {
            return trimmedValue;
        }
        String trimmedFallback = blankToNull(fallback);
        return trimmedFallback != null ? trimmedFallback : UNKNOWN;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static class UsageAccumulator {
        long totalCalls;
        long successfulCalls;
        long failedCalls;
        long promptTokens;
        long completionTokens;
        long totalTokens;
        long totalLatencyMs;
        double estimatedCostUsd;

        void add(ChatUsageEvent event) {
            totalCalls++;
            if (event.success()) {
                successfulCalls++;
            }
            else {
                failedCalls++;
            }
            promptTokens += event.promptTokens();
            completionTokens += event.completionTokens();
            totalTokens += event.totalTokens();
            totalLatencyMs += event.latencyMs();
            estimatedCostUsd += event.estimatedCostUsd();
        }

        double averageLatencyMs() {
            return totalCalls == 0 ? 0 : totalLatencyMs / (double) totalCalls;
        }
    }

    private static final class ModelUsageAccumulator extends UsageAccumulator {
        private final String provider;
        private final String model;

        private ModelUsageAccumulator(String provider, String model) {
            this.provider = provider.toLowerCase(Locale.ROOT);
            this.model = model;
        }
    }
}

