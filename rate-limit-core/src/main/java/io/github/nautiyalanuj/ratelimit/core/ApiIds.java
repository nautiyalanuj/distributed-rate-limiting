package io.github.nautiyalanuj.ratelimit.core;

public final class ApiIds {
    private ApiIds() { }

    public static String requireValid(String apiId) {
        if (apiId == null || !apiId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("apiId must contain 1-128 letters, digits, dots, underscores or hyphens");
        }
        return apiId;
    }
}
