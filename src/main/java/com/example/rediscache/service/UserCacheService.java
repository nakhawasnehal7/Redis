package com.example.rediscache.service;

import com.example.rediscache.dto.UserProfileDTO;
import com.example.rediscache.entity.User;
import com.example.rediscache.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.NoSuchElementException;

/**
 * Two caching strategies against the same Redis-cached entity (user profile),
 * so you can see both patterns side by side against the same data.
 *
 * LAZY LOADING (Cache-Aside)  -> getUserProfile()
 *   Read path only populates the cache on a miss. Cheap, simple, and self-healing
 *   (a stale/evicted cache entry just gets refetched next read) but the first
 *   request after a miss always pays the DB round-trip ("cold" read).
 *
 * WRITE-THROUGH -> updateUserProfile()
 *   Every write goes to the DB *and* the cache in the same request, synchronously.
 *   Reads are therefore always warm after a write - no stale window - at the cost
 *   of every write being slightly slower (two systems to update instead of one).
 */
@Service
@RequiredArgsConstructor
public class UserCacheService {

    private static final Logger log = LoggerFactory.getLogger(UserCacheService.class);
    private static final String PROFILE_KEY_PREFIX = "user:profile:";

    private final UserRepository userRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.cache.user-profile-ttl-seconds}")
    private long profileTtlSeconds;

    private String profileKey(Long userId) {
        return PROFILE_KEY_PREFIX + userId;
    }

    // ---------------------------------------------------------------------
    // LAZY LOADING / CACHE-ASIDE
    // ---------------------------------------------------------------------
    public UserProfileDTO getUserProfile(Long userId) {
        String key = profileKey(userId);

        // 1. Try the cache first
        UserProfileDTO cached = (UserProfileDTO) redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.info("CACHE HIT  for {} - served from Redis, no DB hit", key);
            return cached;
        }

        // 2. Miss -> fall through to the database
        log.info("CACHE MISS for {} - querying database", key);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        UserProfileDTO profile = toDto(user);

        // 3. Populate the cache for next time (with TTL so stale entries self-expire)
        redisTemplate.opsForValue().set(key, profile, Duration.ofSeconds(profileTtlSeconds));
        log.info("CACHE POPULATED {} (ttl={}s)", key, profileTtlSeconds);

        return profile;
    }

    // ---------------------------------------------------------------------
    // WRITE-THROUGH
    // ---------------------------------------------------------------------
    public UserProfileDTO updateUserProfile(Long userId, String email, String fullName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));

        // 1. Write to the database first (system of record)
        user.setEmail(email);
        user.setFullName(fullName);
        User saved = userRepository.save(user);
        log.info("DB WRITE   user {} updated in database", userId);

        // 2. Immediately write the same change through to the cache, synchronously.
        //    Unlike lazy loading, we don't wait for the next read to repopulate -
        //    the cache is never allowed to go stale relative to this write.
        UserProfileDTO profile = toDto(saved);
        String key = profileKey(userId);
        redisTemplate.opsForValue().set(key, profile, Duration.ofSeconds(profileTtlSeconds));
        log.info("CACHE WRITE-THROUGH {} updated in Redis in the same request", key);

        return profile;
    }

    public void evictUserProfile(Long userId) {
        redisTemplate.delete(profileKey(userId));
        log.info("CACHE EVICT {}", profileKey(userId));
    }

    private UserProfileDTO toDto(User user) {
        return new UserProfileDTO(user.getId(), user.getUsername(), user.getEmail(), user.getFullName());
    }
}
