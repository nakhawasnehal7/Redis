package com.example.rediscache.service;

import com.example.rediscache.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final RateLimiterService rateLimiterService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${app.ratelimit.login.max-attempts}")
    private long maxLoginAttempts;

    @Value("${app.ratelimit.login.window-seconds}")
    private long loginWindowSeconds;

    /**
     * Verifies credentials against the database and, on success, creates a new
     * Redis-backed session. Brute-force protection is keyed by username (not IP,
     * which the ApiRateLimitFilter already covers separately) so an attacker can't
     * dodge the limit by spraying attempts across many usernames from one IP, or
     * dodge it by rotating IPs against a single username.
     */
    public LoginOutcome login(String username, String rawPassword) {
        String attemptsKey = "ratelimit:login:" + username;

        boolean withinLimit = rateLimiterService.tryConsume(
                attemptsKey, maxLoginAttempts, Duration.ofSeconds(loginWindowSeconds));

        if (!withinLimit) {
            long resetSeconds = rateLimiterService.getResetSeconds(attemptsKey);
            return LoginOutcome.tooManyAttempts(resetSeconds);
        }

        Optional<String> token = userRepository.findByUsername(username)
                .filter(user -> passwordEncoder.matches(rawPassword, user.getPasswordHash()))
                .map(sessionService::createSession);

/*        if (token.isPresent()) {
            // Successful login clears the attempt counter so a legitimate user who
            // mistyped their password a couple of times isn't left partway through
            // a lockout window after they get it right.
            redisTemplate.delete(attemptsKey);
            return LoginOutcome.success(token.get());
        }*/

        if (token.isPresent()) {
            // Every attempt - success or failure - counts toward the limit now,
            // so we deliberately do NOT clear the counter here anymore.
            return LoginOutcome.success(token.get());
        }

        return LoginOutcome.ofInvalidCredentials();
    }

    public void logout(String token) {
        sessionService.invalidateSession(token);
    }

    /** Small result type so the controller can distinguish 401 vs 429 without exceptions. */
    public record LoginOutcome(String sessionToken, boolean tooManyAttempts,
                                boolean invalidCredentials, long retryAfterSeconds) {
        // NOTE: these factory methods must NOT share a name + empty parameter list with
        // any record component's auto-generated accessor (sessionToken(), tooManyAttempts(),
        // invalidCredentials(), retryAfterSeconds()) - that collision is what caused the
        // "not public / cannot be accessed from outside package" compile error, because the
        // clash prevented the public accessor from being generated as expected.
        public static LoginOutcome success(String token) {
            return new LoginOutcome(token, false, false, 0);
        }
        public static LoginOutcome tooManyAttempts(long retryAfterSeconds) {
            return new LoginOutcome(null, true, false, retryAfterSeconds);
        }
        public static LoginOutcome ofInvalidCredentials() {
            return new LoginOutcome(null, false, true, 0);
        }
    }
}
