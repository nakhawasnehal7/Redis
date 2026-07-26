package com.example.rediscache.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/**
 * What gets written into Redis at login time under key "session:{token}".
 * Redis (ElastiCache) is the *only* place this lives - there's no DB table for sessions,
 * which is exactly why ElastiCache is a good fit: fast, TTL-native, disposable state.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserSession implements Serializable {
    private Long userId;
    private String username;
    private Instant loginAt;
}
