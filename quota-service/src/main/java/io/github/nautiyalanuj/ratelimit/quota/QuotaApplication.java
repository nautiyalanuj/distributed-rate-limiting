package io.github.nautiyalanuj.ratelimit.quota;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SecurityProperties.class)
public class QuotaApplication {
    public static void main(String[] args) { SpringApplication.run(QuotaApplication.class, args); }
}
