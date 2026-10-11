package io.github.nautiyalanuj.ratelimit.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nautiyalanuj.ratelimit.core.ApiIds;
import io.github.nautiyalanuj.ratelimit.core.QuotaGrant;
import io.github.nautiyalanuj.ratelimit.core.QuotaRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Reuses connections. Mutating quota requests are deliberately never retried. */
public final class HttpQuotaClient implements QuotaClient, AutoCloseable {
    private final URI baseUri;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);

    public HttpQuotaClient(URI baseUri, String token, Duration timeout) {
        if (baseUri == null || !("http".equals(baseUri.getScheme()) || "https".equals(baseUri.getScheme()))
                || baseUri.getHost() == null || baseUri.getRawQuery() != null
                || baseUri.getRawFragment() != null || baseUri.getUserInfo() != null
                || !(baseUri.getPath().isEmpty() || baseUri.getPath().equals("/"))) {
            throw new IllegalArgumentException("baseUri must be an HTTP(S) origin");
        }
        if (token == null || token.isBlank() || token.contains("\r") || token.contains("\n")) {
            throw new IllegalArgumentException("quota token is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.toMillis() > 30_000) {
            throw new IllegalArgumentException("timeout must be positive and at most 30 seconds");
        }
        this.baseUri = baseUri;
        this.token = token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public QuotaGrant acquire(String apiId, QuotaRequest request) {
        ApiIds.requireValid(apiId);
        try {
            var message = HttpRequest.newBuilder(baseUri.resolve("/v1/apis/" + apiId + "/quota"))
                    .timeout(timeout).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build();
            var response = http.send(message, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new QuotaUnavailableException("quota service returned HTTP " + response.statusCode());
            }
            var grant = mapper.readValue(response.body(), QuotaGrant.class);
            if (grant.grantedTokens() > request.requestedTokens()
                    || (grant.grantedTokens() > 0 && grant.grantedTokens() < request.minimumTokens())) {
                throw new QuotaUnavailableException("quota service returned an invalid grant size");
            }
            return grant;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QuotaUnavailableException("quota request interrupted", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new QuotaUnavailableException("quota request failed", e);
        }
    }

    @Override
    public void close() { http.close(); }
}
