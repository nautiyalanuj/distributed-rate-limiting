package io.github.nautiyalanuj.ratelimit.quota;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class BearerAuthFilter extends OncePerRequestFilter {
    private final SecurityProperties security;

    public BearerAuthFilter(SecurityProperties security) { this.security = security; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/v1/")) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        boolean admin = matches(token, security.adminToken());
        boolean service = matches(token, security.serviceToken());
        if (!admin && !service) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"valid bearer token required\"}");
            return;
        }
        if (!admin && !(request.getMethod().equals("POST") && request.getRequestURI().endsWith("/quota"))) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"admin token required\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean matches(String supplied, String expected) {
        return MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
