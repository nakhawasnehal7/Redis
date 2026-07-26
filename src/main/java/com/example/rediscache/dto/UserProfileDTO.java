package com.example.rediscache.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Public-facing projection of a User. This is the object we actually store in Redis -
 * intentionally excludes the password hash so sensitive data never sits in the cache.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDTO implements Serializable {
    private Long id;
    private String username;
    private String email;
    private String fullName;
}
