package io.github.nautiyalanuj.ratelimit.quota;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("quota.security")
public record SecurityProperties(@NotBlank @Size(min = 16) String adminToken,
                                 @NotBlank @Size(min = 16) String serviceToken) {
    public SecurityProperties {
        if (adminToken != null && adminToken.equals(serviceToken)) {
            throw new IllegalArgumentException("admin and service tokens must be distinct");
        }
    }
}
