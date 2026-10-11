package io.github.nautiyalanuj.ratelimit.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class ModelsTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a/b", "{orders}", "a b", "../orders", "a?b"})
    void rejectsUnsafeApiIds(String value) {
        assertThatThrownBy(() -> ApiIds.requireValid(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidPolicies() {
        assertThatThrownBy(() -> new ApiPolicy("token_bucket", 0, 1, 1, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiPolicy("token_bucket", 10, Double.NaN, 1, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiPolicy("token_bucket", 10, Double.POSITIVE_INFINITY, 1, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiPolicy("token_bucket", 10, 1, 11, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiPolicy("token_bucket", 10, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsImpossibleRequestsAndGrants() {
        assertThatThrownBy(() -> new QuotaRequest(5, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaRequest(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaGrant(0, 0, 0, 1000)).isInstanceOf(IllegalArgumentException.class);
    }
}
