package io.github.nautiyalanuj.ratelimit.client;

public final class QuotaUnavailableException extends RuntimeException {
    public QuotaUnavailableException(String message) { super(message); }
    public QuotaUnavailableException(String message, Throwable cause) { super(message, cause); }
}
