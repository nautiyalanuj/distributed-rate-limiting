package io.github.nautiyalanuj.ratelimit.quota;

import io.github.nautiyalanuj.ratelimit.core.ApiPolicy;
import io.github.nautiyalanuj.ratelimit.core.QuotaGrant;
import io.github.nautiyalanuj.ratelimit.core.QuotaRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/apis/{apiId}")
public class QuotaController {
    private final QuotaService quotas;
    public QuotaController(QuotaService quotas) { this.quotas = quotas; }

    @PutMapping
    public ApiPolicy configure(@PathVariable String apiId, @RequestBody ApiPolicy policy) {
        return quotas.configure(apiId, policy);
    }

    @GetMapping
    public ApiPolicy policy(@PathVariable String apiId) { return quotas.policy(apiId); }

    @PostMapping("/quota")
    public QuotaGrant acquire(@PathVariable String apiId, @RequestBody QuotaRequest request) {
        return quotas.acquire(apiId, request);
    }
}
