package com.example.ratelimiter.web;

import com.example.ratelimiter.config.RateLimiterRegistry;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies the active rate limiter to every /api/** request.
 * Client identity = X-API-Key header if present, otherwise the remote IP.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final RateLimiterRegistry registry;

    public RateLimitFilter(RateLimiterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        RateLimiter limiter = registry.active();
        RateLimitResult result = limiter.tryAcquire(resolveKey(request));

        response.setHeader("X-RateLimit-Algorithm", limiter.name());
        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));

        if (result.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, (result.retryAfterMillis() + 999) / 1000);
        response.setStatus(429); // Too Many Requests
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit of %d requests exceeded\",\"retryAfterMs\":%d}",
                result.limit(), result.retryAfterMillis()));
    }

    private String resolveKey(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey.trim();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
