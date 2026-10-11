package io.github.nautiyalanuj.ratelimit.core;

/** The shared bucket is scoped to an API, across all its service instances. */
public record ApiPolicy(String algorithm, int capacity, double refillTokensPerSecond,
                        int maxBatchSize, long leaseTtlMillis) {
    public ApiPolicy {
        if (algorithm == null || !algorithm.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("algorithm must be a valid strategy name");
        }
        if (capacity < 1 || capacity > 1_000_000) {
            throw new IllegalArgumentException("capacity must be between 1 and 1000000");
        }
        if (!Double.isFinite(refillTokensPerSecond) || refillTokensPerSecond < 0.001
                || refillTokensPerSecond > 1_000_000) {
            throw new IllegalArgumentException("refillTokensPerSecond must be between 0.001 and 1000000");
        }
        if (maxBatchSize < 1 || maxBatchSize > capacity) {
            throw new IllegalArgumentException("maxBatchSize must be between 1 and capacity");
        }
        if (leaseTtlMillis < 50 || leaseTtlMillis > 30_000) {
            throw new IllegalArgumentException("leaseTtlMillis must be between 50 and 30000");
        }
    }
}
