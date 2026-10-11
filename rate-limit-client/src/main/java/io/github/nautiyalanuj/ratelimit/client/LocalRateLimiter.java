package io.github.nautiyalanuj.ratelimit.client;

import io.github.nautiyalanuj.ratelimit.core.ApiIds;
import io.github.nautiyalanuj.ratelimit.core.QuotaRequest;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One shared limiter per configured API per process. Local tokens never refill themselves. */
public final class LocalRateLimiter {
    private final String apiId;
    private final int batchSize;
    private final QuotaClient client;
    private final LongSupplier nanoClock;
    private int available;
    private long expiresAt;
    private long retryAt;
    private LimitDecision.Status deniedStatus;

    public LocalRateLimiter(String apiId, int batchSize, QuotaClient client) {
        this(apiId, batchSize, client, System::nanoTime);
    }

    // Injectable monotonic clock for deterministic tests of expiry and network latency.
    LocalRateLimiter(String apiId, int batchSize, QuotaClient client, LongSupplier nanoClock) {
        this.apiId = ApiIds.requireValid(apiId);
        if (batchSize < 1 || batchSize > 1_000_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000000");
        }
        this.batchSize = batchSize;
        this.client = Objects.requireNonNull(client);
        this.nanoClock = Objects.requireNonNull(nanoClock);
    }

    public LimitDecision tryAcquire() { return tryAcquire(1); }

    /** Synchronization also ensures only one central fetch per API can be in flight. */
    public synchronized LimitDecision tryAcquire(int cost) {
        if (cost < 1 || cost > batchSize) {
            throw new IllegalArgumentException("cost must be between 1 and batchSize");
        }
        long now = nanoClock.getAsLong();
        if (available > 0 && now - expiresAt >= 0) { available = 0; }
        if (available >= cost) {
            available -= cost;
            return new LimitDecision(LimitDecision.Status.ALLOWED, available, 0);
        }
        if (deniedStatus != null && now - retryAt < 0) {
            return new LimitDecision(deniedStatus, available, millisCeiling(retryAt - now));
        }
        // Discard a residual smaller than cost: do not accumulate unbounded leases.
        available = 0;
        long started = now;
        try {
            var grant = client.acquire(apiId, new QuotaRequest(batchSize, cost));
            now = nanoClock.getAsLong();
            if (grant.grantedTokens() == 0) {
                deniedStatus = LimitDecision.Status.RATE_LIMITED;
                // A returned delay is relative to Redis time during the fetch.
                retryAt = now + TimeUnit.MILLISECONDS.toNanos(grant.retryAfterMillis());
                return new LimitDecision(deniedStatus, 0, grant.retryAfterMillis());
            }
            if (grant.grantedTokens() < cost || grant.grantedTokens() > batchSize) {
                throw new QuotaUnavailableException("invalid grant size");
            }
            // Starting at send time conservatively subtracts the entire network round trip.
            expiresAt = started + TimeUnit.MILLISECONDS.toNanos(grant.leaseTtlMillis());
            if (now - expiresAt >= 0) {
                throw new QuotaUnavailableException("quota lease expired in transit");
            }
            deniedStatus = null;
            available = grant.grantedTokens() - cost;
            return new LimitDecision(LimitDecision.Status.ALLOWED, available, 0);
        } catch (QuotaUnavailableException e) {
            deniedStatus = LimitDecision.Status.QUOTA_UNAVAILABLE;
            retryAt = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(100);
            return new LimitDecision(deniedStatus, 0, 100);
        }
    }

    private static long millisCeiling(long nanos) {
        return 1 + (nanos - 1) / 1_000_000;
    }
}
