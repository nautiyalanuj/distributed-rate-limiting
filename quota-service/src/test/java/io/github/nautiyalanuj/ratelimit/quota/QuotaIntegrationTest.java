package io.github.nautiyalanuj.ratelimit.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nautiyalanuj.ratelimit.client.HttpQuotaClient;
import io.github.nautiyalanuj.ratelimit.client.LocalRateLimiter;
import io.github.nautiyalanuj.ratelimit.core.ApiPolicy;
import io.github.nautiyalanuj.ratelimit.core.QuotaRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/** Runs against real Redis; never flushes the database or touches non-test keys. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "quota.security.admin-token=integration-admin-token", "quota.security.service-token=integration-service-token",
        "spring.data.redis.timeout=5s", "spring.data.redis.connect-timeout=5s"
})
class QuotaIntegrationTest {
    @LocalServerPort int port;
    @Autowired QuotaService quotas;
    @Autowired TokenBucketAlgorithm algorithm;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;
    private final Set<String> apiIds = new HashSet<>();

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.host", () -> System.getenv().getOrDefault("TEST_REDIS_HOST", "localhost"));
        properties.add("spring.data.redis.port", () -> System.getenv().getOrDefault("TEST_REDIS_PORT", "6379"));
    }

    @AfterEach
    void cleanup() { for (String apiId : apiIds) { redis.delete(RedisKeys.bucket(apiId)); } }

    private String configure(int capacity, double rate, int batch) {
        String apiId = "test-" + UUID.randomUUID();
        apiIds.add(apiId);
        quotas.configure(apiId, new ApiPolicy("token_bucket", capacity, rate, batch, 30_000));
        return apiId;
    }

    @Test
    void twoIndependentCentralAllocatorsNeverDoubleAllocate() throws Exception {
        String api = configure(100, 0.001, 10);
        String host = System.getenv().getOrDefault("TEST_REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("TEST_REDIS_PORT", "6379"));
        var otherFactory = new LettuceConnectionFactory(host, redisPort);
        otherFactory.afterPropertiesSet();
        otherFactory.start();
        try {
            var otherRedis = new StringRedisTemplate(otherFactory);
            var replica = new TokenBucketAlgorithm(otherRedis, mapper);
            var calls = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < 100; i++) {
                var target = i % 2 == 0 ? algorithm : replica;
                calls.add(() -> target.acquire(api, new QuotaRequest(10, 1)).grantedTokens());
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                int granted = 0;
                for (var result : executor.invokeAll(calls)) { granted += result.get(); }
                assertThat(granted).isEqualTo(100);
            }
        } finally { otherFactory.destroy(); }
    }

    @Test
    void multipleServiceInstancesShareOneApiQuotaOverHttp() throws Exception {
        String api = configure(100, 0.001, 10);
        try (var client = new HttpQuotaClient(URI.create("http://localhost:" + port), "integration-service-token", Duration.ofSeconds(5))) {
            var limiters = new ArrayList<LocalRateLimiter>();
            for (int i = 0; i < 8; i++) { limiters.add(new LocalRateLimiter(api, 10, client)); }
            var calls = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 160; i++) {
                var limiter = limiters.get(i % limiters.size());
                calls.add(() -> limiter.tryAcquire().allowed());
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                int allowed = 0;
                for (var result : executor.invokeAll(calls)) { if (result.get()) { allowed++; } }
                assertThat(allowed).isEqualTo(100);
            }
        }
    }

    @Test
    void apiBucketsAreIndependentAndGrantsCanBePartial() {
        String orders = configure(7, 0.001, 5);
        String payments = configure(3, 0.001, 3);
        assertThat(quotas.acquire(orders, new QuotaRequest(5, 1)).grantedTokens()).isEqualTo(5);
        assertThat(quotas.acquire(orders, new QuotaRequest(5, 1)).grantedTokens()).isEqualTo(2);
        assertThat(quotas.acquire(orders, new QuotaRequest(5, 1)).grantedTokens()).isZero();
        assertThat(quotas.acquire(payments, new QuotaRequest(5, 1)).grantedTokens()).isEqualTo(3);
    }

    @Test
    void insufficientWeightedGrantDoesNotDebitTheRemainingTokens() {
        String api = configure(5, 0.001, 5);
        quotas.acquire(api, new QuotaRequest(3, 3));
        var denied = quotas.acquire(api, new QuotaRequest(5, 3));
        assertThat(denied.grantedTokens()).isZero();
        assertThat(denied.centralRemainingTokens()).isEqualTo(2);
        assertThat(denied.retryAfterMillis()).isPositive();
        assertThat(quotas.acquire(api, new QuotaRequest(2, 2)).grantedTokens()).isEqualTo(2);
    }

    @Test
    void fractionalTokensArePreservedAndRefillIsCappedAtCapacity() {
        String api = configure(5, 0.001, 5);
        quotas.acquire(api, new QuotaRequest(5, 1));
        redis.opsForHash().put(RedisKeys.bucket(api), "tokens", "0.5");
        for (int i = 0; i < 10; i++) { assertThat(quotas.acquire(api, new QuotaRequest(1, 1)).grantedTokens()).isZero(); }
        assertThat(Double.parseDouble(redis.opsForHash().get(RedisKeys.bucket(api), "tokens").toString())).isBetween(0.5, 0.9);
        redis.opsForHash().put(RedisKeys.bucket(api), "updated_at_us", "0");
        assertThat(quotas.acquire(api, new QuotaRequest(5, 1)).grantedTokens()).isEqualTo(5);
        assertThat(quotas.acquire(api, new QuotaRequest(1, 1)).grantedTokens()).isZero();
    }

    @Test
    void elapsedTimeRefillsAtTheConfiguredRate() {
        String api = configure(10, 2, 10);
        quotas.acquire(api, new QuotaRequest(10, 1));
        long timestamp = Long.parseLong(redis.opsForHash().get(RedisKeys.bucket(api), "updated_at_us").toString());
        redis.opsForHash().put(RedisKeys.bucket(api), "updated_at_us", Long.toString(timestamp - 1_100_000));
        var grant = quotas.acquire(api, new QuotaRequest(10, 1));
        assertThat(grant.grantedTokens()).isBetween(2, 3);
    }

    @Test
    void backwardsClockDoesNotMintQuotaOrMoveBucketTimestampBackwards() {
        String api = configure(5, 1, 5);
        quotas.acquire(api, new QuotaRequest(5, 1));
        long timestamp = Long.parseLong(redis.opsForHash().get(RedisKeys.bucket(api), "updated_at_us").toString());
        String future = Long.toString(timestamp + 10_000_000);
        redis.opsForHash().put(RedisKeys.bucket(api), "updated_at_us", future);
        assertThat(quotas.acquire(api, new QuotaRequest(1, 1)).grantedTokens()).isZero();
        assertThat(redis.opsForHash().get(RedisKeys.bucket(api), "updated_at_us")).isEqualTo(future);
    }

    @Test
    void policyUpdatesPreserveBalanceAndClampReducedCapacity() {
        String api = configure(10, 0.001, 5);
        quotas.acquire(api, new QuotaRequest(5, 1));
        var larger = new ApiPolicy("token_bucket", 100, 0.001, 5, 1000);
        quotas.configure(api, larger);
        quotas.configure(api, larger);
        assertThat(quotas.acquire(api, new QuotaRequest(5, 1)).grantedTokens()).isEqualTo(5);
        assertThat(quotas.acquire(api, new QuotaRequest(5, 1)).grantedTokens()).isZero();
        String another = configure(10, 0.001, 5);
        quotas.configure(another, new ApiPolicy("token_bucket", 3, 0.001, 3, 1000));
        assertThat(quotas.acquire(another, new QuotaRequest(5, 1)).grantedTokens()).isEqualTo(3);
    }

    @Test
    void missingApiUnknownAlgorithmsAndImpossibleCostsAreRejected() {
        assertThatThrownBy(() -> quotas.acquire("test-missing-" + UUID.randomUUID(), new QuotaRequest(1, 1)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        String api = configure(5, 0.001, 2);
        assertThatThrownBy(() -> quotas.acquire(api, new QuotaRequest(3, 3)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> quotas.configure(api, new ApiPolicy("unknown", 5, 1, 2, 1000)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void adminAndServiceCredentialsHaveDifferentPermissions() throws Exception {
        String api = configure(5, 0.001, 5);
        assertThat(send("GET", api, null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", api, null, "integration-service-token").statusCode()).isEqualTo(403);
        assertThat(send("GET", api, null, "integration-admin-token").statusCode()).isEqualTo(200);
        assertThat(send("PUT", api, mapper.writeValueAsString(new ApiPolicy("token_bucket", 5, 1, 5, 1000)), "integration-service-token").statusCode()).isEqualTo(403);
        assertThat(send("POST", api + "/quota", "{\"requestedTokens\":5,\"minimumTokens\":1}", "integration-service-token").statusCode()).isEqualTo(200);
        assertThat(send("POST", api + "/quota", "{\"requestedTokens\":0,\"minimumTokens\":1}", "integration-service-token").statusCode()).isEqualTo(400);
        assertThat(send("PUT", api, "{}", "integration-admin-token").statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/apis/" + path))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
            if (token != null) { request.header("Authorization", "Bearer " + token); }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
