package io.github.nautiyalanuj.ratelimit.quota;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nautiyalanuj.ratelimit.core.ApiPolicy;
import io.github.nautiyalanuj.ratelimit.core.QuotaGrant;
import io.github.nautiyalanuj.ratelimit.core.QuotaRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class QuotaService {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Map<String, RateLimitAlgorithm> algorithms;
    private final MeterRegistry metrics;

    public QuotaService(StringRedisTemplate redis, ObjectMapper mapper,
                        List<RateLimitAlgorithm> algorithms, MeterRegistry metrics) {
        this.redis = redis;
        this.mapper = mapper;
        this.algorithms = algorithms.stream().collect(Collectors.toUnmodifiableMap(RateLimitAlgorithm::name, Function.identity()));
        this.metrics = metrics;
    }

    public ApiPolicy configure(String apiId, ApiPolicy policy) {
        algorithm(policy.algorithm()).configure(apiId, policy);
        return policy;
    }

    public ApiPolicy policy(String apiId) {
        Object value = redis.opsForHash().get(RedisKeys.bucket(apiId), "policy");
        if (value == null) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API policy not found"); }
        try {
            return mapper.readValue(value.toString(), ApiPolicy.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid stored policy", e);
        }
    }

    public QuotaGrant acquire(String apiId, QuotaRequest request) {
        var policy = policy(apiId);
        var grant = algorithm(policy.algorithm()).acquire(apiId, request);
        metrics.counter("quota.granted.tokens", "api", apiId).increment(grant.grantedTokens());
        if (grant.grantedTokens() == 0) { metrics.counter("quota.denied.fetches", "api", apiId).increment(); }
        return grant;
    }

    private RateLimitAlgorithm algorithm(String name) {
        var algorithm = algorithms.get(name);
        if (algorithm == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported algorithm: " + name);
        }
        return algorithm;
    }
}
