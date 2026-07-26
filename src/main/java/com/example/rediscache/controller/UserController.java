package com.example.rediscache.controller;

import com.example.rediscache.dto.UserProfileDTO;
import com.example.rediscache.service.UserCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserCacheService userCacheService;

    /** LAZY LOADING: first call after a cache miss/eviction/TTL expiry hits the DB; every call after that hits Redis. */
    @GetMapping("/{id}")
    public UserProfileDTO getUser(@PathVariable Long id) {
        return userCacheService.getUserProfile(id);
    }

    /** WRITE-THROUGH: DB and Redis are updated together, so the very next GET is guaranteed to be warm and correct. */
    @PutMapping("/{id}")
    public UserProfileDTO updateUser(@PathVariable Long id, @RequestBody UpdateProfileRequest body) {
        return userCacheService.updateUserProfile(id, body.email(), body.fullName());
    }

    @DeleteMapping("/{id}/cache")
    public void evictCache(@PathVariable Long id) {
        userCacheService.evictUserProfile(id);
    }

    public record UpdateProfileRequest(String email, String fullName) {}
}
