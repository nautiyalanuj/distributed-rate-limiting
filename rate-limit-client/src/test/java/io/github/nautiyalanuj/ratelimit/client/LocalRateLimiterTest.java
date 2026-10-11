package io.github.nautiyalanuj.ratelimit.client;

import io.github.nautiyalanuj.ratelimit.core.QuotaGrant;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class LocalRateLimiterTest {
    @Test
    void checksMostRequestsLocallyAndSupportsWeightedCost() {
        var calls = new AtomicInteger();
        var limiter = new LocalRateLimiter("orders", 5, (api, request) -> {
            calls.incrementAndGet();
            return new QuotaGrant(5, 0, 0, 1000);
        }, () -> 0);
        assertThat(limiter.tryAcquire(2).localRemainingTokens()).isEqualTo(3);
        assertThat(limiter.tryAcquire().localRemainingTokens()).isEqualTo(2);
        assertThat(limiter.tryAcquire(2).localRemainingTokens()).isZero();
        assertThat(calls).hasValue(1);
        assertThat(limiter.tryAcquire().allowed()).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void acceptsPartialGrantAndRequestsAtLeastTheCurrentCost() {
        var limiter = new LocalRateLimiter("orders", 5, (api, request) -> {
            assertThat(request.minimumTokens()).isEqualTo(2);
            return new QuotaGrant(3, 0, 0, 1000);
        }, () -> 0);
        assertThat(limiter.tryAcquire(2).localRemainingTokens()).isEqualTo(1);
    }

    @Test
    void discardsExpiredQuotaAndNeverRefillsLocally() {
        var clock = new AtomicLong();
        var calls = new AtomicInteger();
        var limiter = new LocalRateLimiter("orders", 5, (api, request) -> {
            return calls.incrementAndGet() == 1 ? new QuotaGrant(5, 0, 0, 1000) : new QuotaGrant(0, 0, 500, 1000);
        }, clock::get);
        assertThat(limiter.tryAcquire().allowed()).isTrue();
        clock.set(TimeUnit.SECONDS.toNanos(1));
        assertThat(limiter.tryAcquire().status()).isEqualTo(LimitDecision.Status.RATE_LIMITED);
        assertThat(limiter.tryAcquire().localRemainingTokens()).isZero();
        assertThat(calls).hasValue(2);
    }

    @Test
    void subtractsNetworkLatencyFromLeaseLifetime() {
        var clock = new AtomicLong();
        var limiter = new LocalRateLimiter("orders", 5, (api, request) -> {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1100));
            return new QuotaGrant(5, 0, 0, 1000);
        }, clock::get);
        assertThat(limiter.tryAcquire().status()).isEqualTo(LimitDecision.Status.QUOTA_UNAVAILABLE);
    }

    @Test
    void backsOffEmptyGrantsAndRetriesAfterTheDelay() {
        var clock = new AtomicLong();
        var calls = new AtomicInteger();
        var limiter = new LocalRateLimiter("orders", 5, (api, request) -> {
            return calls.incrementAndGet() == 1 ? new QuotaGrant(0, 0, 200, 1000) : new QuotaGrant(5, 0, 0, 1000);
        }, clock::get);
        assertThat(limiter.tryAcquire().retryAfterMillis()).isEqualTo(200);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        assertThat(limiter.tryAcquire().retryAfterMillis()).isEqualTo(100);
        assertThat(calls).hasValue(1);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        assertThat(limiter.tryAcquire().allowed()).isTrue();
    }

    @Test
    void failsClosedAndSuppressesRepeatedFetchesDuringOutage() {
        var calls = new AtomicInteger();
        var limiter = new LocalRateLimiter("orders", 5, (api, request) -> {
            calls.incrementAndGet();
            throw new QuotaUnavailableException("offline");
        }, () -> 0);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire().status()).isEqualTo(LimitDecision.Status.QUOTA_UNAVAILABLE);
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsInvalidGrantFromACustomClient() {
        var limiter = new LocalRateLimiter("orders", 5, (api, request) -> new QuotaGrant(6, 0, 0, 1000), () -> 0);
        assertThat(limiter.tryAcquire().status()).isEqualTo(LimitDecision.Status.QUOTA_UNAVAILABLE);
    }

    @Test
    void serializesConcurrentConsumptionAndFetches() throws Exception {
        var quota = new AtomicInteger(50);
        var calls = new AtomicInteger();
        var limiter = new LocalRateLimiter("orders", 10, (api, request) -> {
            calls.incrementAndGet();
            int granted = quota.getAndUpdate(remaining -> Math.max(0, remaining - 10));
            return new QuotaGrant(Math.min(granted, 10), Math.max(0, granted - 10), granted == 0 ? 1000 : 0, 1000);
        }, () -> 0);
        var requests = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 200; i++) { requests.add(() -> limiter.tryAcquire().allowed()); }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            int allowed = 0;
            for (var result : executor.invokeAll(requests)) { if (result.get()) { allowed++; } }
            assertThat(allowed).isEqualTo(50);
        }
        assertThat(calls).hasValue(6);
    }

    @Test
    void validatesCostBeforeCallingCentralService() {
        var limiter = new LocalRateLimiter("orders", 5, (api, request) -> { throw new AssertionError("must not fetch"); });
        assertThatThrownBy(() -> limiter.tryAcquire(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire(6)).isInstanceOf(IllegalArgumentException.class);
    }
}
