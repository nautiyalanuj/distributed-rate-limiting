package io.github.nautiyalanuj.ratelimit.quota;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nautiyalanuj.ratelimit.core.ApiPolicy;
import io.github.nautiyalanuj.ratelimit.core.QuotaGrant;
import io.github.nautiyalanuj.ratelimit.core.QuotaRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public final class TokenBucketAlgorithm implements RateLimitAlgorithm {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final DefaultRedisScript<Long> configureScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<List> acquireScript = new DefaultRedisScript<>();

    public TokenBucketAlgorithm(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
        configureScript.setLocation(new ClassPathResource("lua/configure-token-bucket.lua"));
        configureScript.setResultType(Long.class);
        acquireScript.setLocation(new ClassPathResource("lua/acquire-token-bucket.lua"));
        acquireScript.setResultType(List.class);
    }

    @Override
    public String name() { return "token_bucket"; }

    @Override
    public void configure(String apiId, ApiPolicy policy) {
        try {
            Long result = redis.execute(configureScript, List.of(RedisKeys.bucket(apiId)),
                    mapper.writeValueAsString(policy), Integer.toString(policy.capacity()),
                    Double.toString(policy.refillTokensPerSecond()), Integer.toString(policy.maxBatchSize()),
                    Long.toString(policy.leaseTtlMillis()), policy.algorithm());
            if (result == null) { throw new IllegalStateException("missing Redis result"); }
            if (result == -1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "an existing API cannot change algorithms in place");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot encode policy", e);
        }
    }

    @Override
    public QuotaGrant acquire(String apiId, QuotaRequest request) {
        List<?> values = redis.execute(acquireScript, List.of(RedisKeys.bucket(apiId)),
                Integer.toString(request.requestedTokens()), Integer.toString(request.minimumTokens()));
        if (values == null || values.isEmpty()) { throw new IllegalStateException("missing Redis result"); }
        long granted = ((Number) values.getFirst()).longValue();
        if (granted == -1) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API policy not found"); }
        if (granted == -2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minimumTokens exceeds the API's maximum batch size");
        }
        if (granted == -3) { throw new ResponseStatusException(HttpStatus.CONFLICT, "API algorithm changed"); }
        return new QuotaGrant(Math.toIntExact(granted), ((Number) values.get(1)).intValue(),
                ((Number) values.get(2)).longValue(), ((Number) values.get(3)).longValue());
    }
}
