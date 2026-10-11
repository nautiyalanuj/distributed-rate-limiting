package io.github.nautiyalanuj.ratelimit.core;

/** A partial grant is accepted only when it can pay for the current request. */
public record QuotaRequest(int requestedTokens, int minimumTokens) {
    public QuotaRequest {
        if (requestedTokens < 1 || requestedTokens > 1_000_000
                || minimumTokens < 1 || minimumTokens > requestedTokens) {
            throw new IllegalArgumentException("require 1 <= minimumTokens <= requestedTokens <= 1000000");
        }
    }
}
