package io.github.nautiyalanuj.ratelimit.demo;

import io.github.nautiyalanuj.ratelimit.client.HttpQuotaClient;
import io.github.nautiyalanuj.ratelimit.client.LocalRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) { SpringApplication.run(DemoApplication.class, args); }

    @Bean(destroyMethod = "close")
    HttpQuotaClient quotaClient(@Value("${quota.url}") URI url, @Value("${quota.token}") String token,
                               @Value("${quota.timeout-millis}") long timeoutMillis) {
        return new HttpQuotaClient(url, token, Duration.ofMillis(timeoutMillis));
    }

    @Bean
    Map<String, LocalRateLimiter> apiLimiters(HttpQuotaClient client, @Value("${quota.batch-size}") int batchSize) {
        // Create these once, not on every incoming request.
        return Map.of("orders", new LocalRateLimiter("orders", batchSize, client),
                "payments", new LocalRateLimiter("payments", batchSize, client));
    }
}
