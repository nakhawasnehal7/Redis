package com.example.rediscache.service;

import com.example.rediscache.entity.User;
import com.example.rediscache.model.UserSession;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis IS the session store here - there's no fallback database table.
 * This is the classic AWS ElastiCache use case: ephemeral, high-throughput,
 * TTL-driven state that doesn't need durability guarantees a relational DB gives you.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final String SESSION_KEY_PREFIX = "session:";

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.session.ttl-seconds}")
    private long sessionTtlSeconds;

    public String createSession(User user) {
        String token = UUID.randomUUID().toString();
        UserSession session = new UserSession(user.getId(), user.getUsername(), Instant.now());

        redisTemplate.opsForValue().set(
                SESSION_KEY_PREFIX + token,
                session,
                Duration.ofSeconds(sessionTtlSeconds));

        log.info("SESSION CREATED session:{} for user '{}' (ttl={}s)", token, user.getUsername(), sessionTtlSeconds);
        return token;
    }

    public Optional<UserSession> getSession(String token) {
        UserSession session = (UserSession) redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + token);
        return Optional.ofNullable(session);
    }

    /** Sliding expiration: touch the TTL on each authenticated request so active users stay logged in. */
    public void refreshSession(String token) {
        redisTemplate.expire(SESSION_KEY_PREFIX + token, Duration.ofSeconds(sessionTtlSeconds));
    }

    public void invalidateSession(String token) {
        redisTemplate.delete(SESSION_KEY_PREFIX + token);
        log.info("SESSION INVALIDATED session:{}", token);
    }

    public long getTtlSeconds() {
        return sessionTtlSeconds;
    }
}
