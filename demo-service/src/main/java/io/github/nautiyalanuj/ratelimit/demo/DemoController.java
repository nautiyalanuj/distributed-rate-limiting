package io.github.nautiyalanuj.ratelimit.demo;

import io.github.nautiyalanuj.ratelimit.client.LimitDecision;
import io.github.nautiyalanuj.ratelimit.client.LocalRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DemoController {
    private final Map<String, LocalRateLimiter> limiters;
    private final String instanceId;
    private final MeterRegistry metrics;

    public DemoController(@Qualifier("apiLimiters") Map<String, LocalRateLimiter> limiters,
                          @Value("${instance.id}") String instanceId, MeterRegistry metrics) {
        this.limiters = limiters;
        this.instanceId = instanceId;
        this.metrics = metrics;
    }

    @GetMapping("/api/orders")
    public ResponseEntity<Map<String, Object>> orders() { return handle("orders"); }

    @GetMapping("/api/payments")
    public ResponseEntity<Map<String, Object>> payments() { return handle("payments"); }

    private ResponseEntity<Map<String, Object>> handle(String apiId) {
        LimitDecision decision = limiters.get(apiId).tryAcquire();
        metrics.counter("api.requests", "api", apiId, "decision", decision.status().name()).increment();
        int status = switch (decision.status()) {
            case ALLOWED -> 200;
            case RATE_LIMITED -> 429;
            case QUOTA_UNAVAILABLE -> 503;
        };
        var response = ResponseEntity.status(status).header("X-Service-Instance", instanceId)
                .header("X-Local-Quota-Remaining", Integer.toString(decision.localRemainingTokens()));
        if (!decision.allowed()) {
            response.header("Retry-After", Long.toString(Math.max(1, (decision.retryAfterMillis() + 999) / 1000)));
        }
        return response.body(Map.of("api", apiId, "instance", instanceId, "status", decision.status().name()));
    }
}
