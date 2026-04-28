package guru.springframework.springairagexpert.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "chat.analytics")
@Data
public class ChatAnalyticsProperties {

    private boolean enabled = true;

    private int maxRecentCalls = 25;

    private String provider = "unknown";

    private String model = "unknown";

    private Pricing pricing = new Pricing();

    @Data
    public static class Pricing {
        private double promptCostPer1mTokens;
        private double completionCostPer1mTokens;
    }
}

