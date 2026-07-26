package com.example.rediscache.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginResponse {
    private String sessionToken;
    private long expiresInSeconds;
    private String message;
}
