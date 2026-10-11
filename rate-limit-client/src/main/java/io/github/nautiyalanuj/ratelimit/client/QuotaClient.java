package io.github.nautiyalanuj.ratelimit.client;

import io.github.nautiyalanuj.ratelimit.core.QuotaGrant;
import io.github.nautiyalanuj.ratelimit.core.QuotaRequest;

@FunctionalInterface
public interface QuotaClient {
    QuotaGrant acquire(String apiId, QuotaRequest request) throws QuotaUnavailableException;
}
