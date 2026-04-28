package guru.springframework.springairagexpert.controllers;

import guru.springframework.springairagexpert.model.ChatAnalyticsSummaryResponse;
import guru.springframework.springairagexpert.services.ChatUsageAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/analytics/chat")
public class ChatAnalyticsController {

    private final ChatUsageAnalyticsService chatUsageAnalyticsService;

    @GetMapping
    public ChatAnalyticsSummaryResponse getChatAnalytics() {
        return chatUsageAnalyticsService.getSummary();
    }
}

