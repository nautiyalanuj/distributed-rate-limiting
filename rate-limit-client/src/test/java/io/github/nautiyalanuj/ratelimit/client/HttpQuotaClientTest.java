package io.github.nautiyalanuj.ratelimit.client;

import com.sun.net.httpserver.HttpServer;
import io.github.nautiyalanuj.ratelimit.core.QuotaRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class HttpQuotaClientTest {
    @Test
    void sendsApiIdCredentialsAndRequestedQuota() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/apis/orders/quota", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-token");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"requestedTokens\":5", "\"minimumTokens\":1");
            byte[] body = "{\"grantedTokens\":5,\"centralRemainingTokens\":10,\"retryAfterMillis\":0,\"leaseTtlMillis\":1000}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var client = new HttpQuotaClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-token", Duration.ofSeconds(1))) {
            assertThat(client.acquire("orders", new QuotaRequest(5, 1)).grantedTokens()).isEqualTo(5);
            assertThat(calls).hasValue(1);
        } finally { server.stop(0); }
    }

    @Test
    void doesNotRetryServerErrors() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try (var client = new HttpQuotaClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-token", Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> client.acquire("orders", new QuotaRequest(5, 1))).isInstanceOf(QuotaUnavailableException.class);
            assertThat(calls).hasValue(1);
        } finally { server.stop(0); }
    }

    @Test
    void failsClosedOnMalformedGrant() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var client = new HttpQuotaClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-token", Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> client.acquire("orders", new QuotaRequest(5, 1))).isInstanceOf(QuotaUnavailableException.class);
        } finally { server.stop(0); }
    }
}
