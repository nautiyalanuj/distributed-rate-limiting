package io.github.nautiyalanuj.ratelimit.quota;

import io.github.nautiyalanuj.ratelimit.core.QuotaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiErrorsTest {
    @Test
    void redisOutageReturnsServiceUnavailableInsteadOfAnEmptyGrant() throws Exception {
        var quotas = mock(QuotaService.class);
        when(quotas.acquire(eq("orders"), eq(new QuotaRequest(5, 1))))
                .thenThrow(new RedisConnectionFailureException("simulated outage"));
        var mvc = MockMvcBuilders.standaloneSetup(new QuotaController(quotas)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(post("/v1/apis/orders/quota").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestedTokens\":5,\"minimumTokens\":1}"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("quota storage unavailable"));
    }
}
