package com.example.rediscache.filter;

import com.example.rediscache.model.UserSession;
import com.example.rediscache.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Every protected request looks its session token up directly in Redis (ElastiCache).
 * No sticky sessions, no server-side memory needed - any instance behind the load
 * balancer can authenticate any request, which is the whole point of externalizing
 * session state to a shared cache instead of the JVM's HttpSession.
 */
@Component
@RequiredArgsConstructor
public class SessionAuthFilter extends OncePerRequestFilter {

    private final SessionService sessionService;

    public static final String USER_ID_ATTR = "authenticatedUserId";
    public static final String USERNAME_ATTR = "authenticatedUsername";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/auth/login") || path.startsWith("/h2-console");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer "))
                ? header.substring("Bearer ".length())
                : null;

        if (token == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing session token");
            return;
        }

        Optional<UserSession> session = sessionService.getSession(token);
        if (session.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session expired or invalid");
            return;
        }

        // Sliding expiration - active users don't get logged out mid-use
        sessionService.refreshSession(token);

        request.setAttribute(USER_ID_ATTR, session.get().getUserId());
        request.setAttribute(USERNAME_ATTR, session.get().getUsername());

        filterChain.doFilter(request, response);
    }
}
