package io.github.nautiyalanuj.ratelimit.demo;

import io.github.nautiyalanuj.ratelimit.client.LocalRateLimiter;
import io.github.nautiyalanuj.ratelimit.client.QuotaUnavailableException;
import io.github.nautiyalanuj.ratelimit.core.QuotaGrant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DemoControllerTest {
    @Test
    void distinguishesExhaustedQuotaFromUnavailableQuota() {
        var orders = new LocalRateLimiter("orders", 5, (api, request) -> new QuotaGrant(0, 0, 1200, 1000));
        var payments = new LocalRateLimiter("payments", 5, (api, request) -> { throw new QuotaUnavailableException("offline"); });
        var controller = new DemoController(Map.of("orders", orders, "payments", payments), "instance-2", new SimpleMeterRegistry());
        var denied = controller.orders();
        assertThat(denied.getStatusCode().value()).isEqualTo(429);
        assertThat(denied.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(denied.getHeaders().getFirst("X-Service-Instance")).isEqualTo("instance-2");
        assertThat(controller.payments().getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void admittedRequestReportsLocalBalance() {
        var orders = new LocalRateLimiter("orders", 5, (api, request) -> new QuotaGrant(5, 10, 0, 1000));
        var controller = new DemoController(Map.of("orders", orders), "instance-1", new SimpleMeterRegistry());
        var response = controller.orders();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("X-Local-Quota-Remaining")).isEqualTo("4");
        assertThat(response.getHeaders()).doesNotContainKey("Retry-After");
    }
}
