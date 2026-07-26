package com.example.rediscache.filter;

import com.example.rediscache.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * General-purpose API throttle: every request gets a budget of N requests per window.
 * Keyed by authenticated userId when available (set earlier in the filter chain by
 * SessionAuthFilter), otherwise by client IP - so an anonymous caller (e.g. hitting
 * /api/auth/login, which has no session yet) is still bounded by IP.
 *
 * Order matters: this must run *after* SessionAuthFilter so that
 * request.getAttribute("authenticatedUserId") is already populated by the time we
 * read it here. For the login endpoint specifically (excluded from session auth),
 * that attribute is simply never set, so this filter naturally falls back to IP-based
 * limiting there - which is exactly the brute-force protection we want.
 */
@Component
@RequiredArgsConstructor
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterService rateLimiterService;

    @Value("${app.ratelimit.api.max-requests}")
    private long maxRequests;

    @Value("${app.ratelimit.api.window-seconds}")
    private long windowSeconds;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String identity = resolveIdentity(request);
        String key = "ratelimit:api:" + identity;
        Duration window = Duration.ofSeconds(windowSeconds);

        boolean allowed = rateLimiterService.tryConsume(key, maxRequests, window);

        response.setHeader("X-RateLimit-Limit", String.valueOf(maxRequests));
        response.setHeader("X-RateLimit-Remaining",
                String.valueOf(rateLimiterService.getRemaining(key, maxRequests)));

        if (!allowed) {
            long resetSeconds = rateLimiterService.getResetSeconds(key);
            response.setHeader("Retry-After", String.valueOf(resetSeconds));
            response.sendError(429, "Too many requests - try again in " + resetSeconds + "s");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveIdentity(HttpServletRequest request) {
        Object userId = request.getAttribute("authenticatedUserId");
        if (userId != null) {
            return "user:" + userId;
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        // Behind a load balancer / API Gateway, prefer the forwarded header if present.
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
