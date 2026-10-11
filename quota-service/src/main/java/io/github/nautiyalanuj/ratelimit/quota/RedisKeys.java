package io.github.nautiyalanuj.ratelimit.quota;

import io.github.nautiyalanuj.ratelimit.core.ApiIds;

final class RedisKeys {
    private RedisKeys() { }
    static String bucket(String apiId) {
        // All per-API state stays in a single Redis Cluster hash slot.
        return "drl:{" + ApiIds.requireValid(apiId) + "}:bucket";
    }
}
