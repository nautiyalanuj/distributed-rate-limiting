package io.github.nautiyalanuj.ratelimit.quota;

import io.github.nautiyalanuj.ratelimit.core.ApiPolicy;
import io.github.nautiyalanuj.ratelimit.core.QuotaGrant;
import io.github.nautiyalanuj.ratelimit.core.QuotaRequest;

/** Implement as a Spring bean. Allocation and policy updates must be atomic in shared storage. */
public interface RateLimitAlgorithm {
    String name();
    void configure(String apiId, ApiPolicy policy);
    QuotaGrant acquire(String apiId, QuotaRequest request);
}
