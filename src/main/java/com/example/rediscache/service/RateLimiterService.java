package com.example.rediscache.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * A simple, reusable fixed-window rate limiter built on two Redis primitives:
 * INCR (atomic counter increment) and EXPIRE (TTL). This is the same building
 * block used for API throttling, login brute-force protection, per-user quotas, etc.
 *
 * How it works, for a key like "ratelimit:login:203.0.113.5":
 *   1. INCR the key. Redis creates it at 1 if it doesn't exist yet, or atomically
 *      bumps it if it does - no race condition between two concurrent requests,
 *      because INCR is a single atomic operation on the Redis server.
 *   2. If this INCR just created the key (count == 1), set its TTL to the window
 *      length. This starts the clock on a fresh window. We deliberately only set
 *      the TTL once, on creation - re-setting it on every request would make the
 *      window "slide" forever and a client could keep resetting its own limit.
 *   3. If count > limit, the caller is over budget for this window; reject.
 *
 * This is the "fixed window" algorithm: simple and cheap, at the cost of allowing
 * up to 2x the limit right at a window boundary (e.g. a burst at 0:59 and another
 * at 1:00 could total 2x the per-minute limit). A sliding-window-log or token-bucket
 * algorithm avoids that if you need stricter guarantees - see the note at the
 * bottom of this file for how you'd swap that in.
 */
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * @param key      unique identifier for what's being limited, e.g. "ratelimit:login:{ip}"
     *                 or "ratelimit:api:{userId}"
     * @param limit    max allowed requests within the window
     * @param window   length of the fixed window
     * @return true if this request is allowed, false if the caller is over the limit
     */
    public boolean tryConsume(String key, long limit, Duration window) {
        Long count = redisTemplate.opsForValue().increment(key);

        if (count == null) {
            // Redis call failed to return a value - fail open rather than block all traffic.
            log.warn("Rate limiter got a null increment result for key {} - allowing request", key);
            return true;
        }

        if (count == 1L) {
            // We just created this key -> this is the start of a new window.
            redisTemplate.expire(key, window);
        }

        boolean allowed = count <= limit;
        if (!allowed) {
            log.info("RATE LIMIT EXCEEDED key={} count={} limit={}", key, count, limit);
        }
        return allowed;
    }

    /** How many requests are left in the current window, for surfacing in response headers. */
    public long getRemaining(String key, long limit) {
        Object raw = redisTemplate.opsForValue().get(key);
        long used = raw == null ? 0L : Long.parseLong(raw.toString());
        return Math.max(0, limit - used);
    }

    /** Seconds until the current window resets, useful for a Retry-After header. */
    public long getResetSeconds(String key) {
        Long ttl = redisTemplate.getExpire(key);
        return ttl == null || ttl < 0 ? 0 : ttl;
    }

    // NOTE on stricter alternatives:
    // A sliding-window-log (ZADD timestamp scores into a Sorted Set, ZREMRANGEBYSCORE to
    // drop entries older than the window, ZCARD to count) avoids the boundary-burst issue
    // above at the cost of more memory per key and an extra round trip. For most API
    // throttling and login-attempt use cases, the fixed window here is the right trade-off.
}
