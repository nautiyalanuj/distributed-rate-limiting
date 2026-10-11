package io.github.nautiyalanuj.ratelimit.client;

public record LimitDecision(Status status, int localRemainingTokens, long retryAfterMillis) {
    public enum Status { ALLOWED, RATE_LIMITED, QUOTA_UNAVAILABLE }
    public boolean allowed() { return status == Status.ALLOWED; }
}
