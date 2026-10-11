package io.github.nautiyalanuj.ratelimit.core;

public record QuotaGrant(int grantedTokens, int centralRemainingTokens,
                         long retryAfterMillis, long leaseTtlMillis) {
    public QuotaGrant {
        if (grantedTokens < 0 || centralRemainingTokens < 0 || retryAfterMillis < 0
                || retryAfterMillis > 1_000_000_000_000L
                || leaseTtlMillis < 50 || leaseTtlMillis > 30_000) {
            throw new IllegalArgumentException("invalid quota grant");
        }
        if (grantedTokens == 0 && retryAfterMillis == 0) {
            throw new IllegalArgumentException("an empty grant must include a retry delay");
        }
    }
}
