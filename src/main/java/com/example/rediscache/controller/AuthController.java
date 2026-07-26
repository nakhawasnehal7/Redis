package com.example.rediscache.controller;

import com.example.rediscache.dto.LoginRequest;
import com.example.rediscache.dto.LoginResponse;
import com.example.rediscache.service.AuthService;
import com.example.rediscache.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginOutcome outcome = authService.login(request.getUsername(), request.getPassword());

        if (outcome.tooManyAttempts()) {
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(outcome.retryAfterSeconds()))
                    .body("Too many login attempts - try again in " + outcome.retryAfterSeconds() + "s");
        }

        if (outcome.invalidCredentials()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid username or password");
        }

        return ResponseEntity.ok(
                new LoginResponse(outcome.sessionToken(), sessionService.getTtlSeconds(), "Login successful"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        authService.logout(token);
        return ResponseEntity.noContent().build();
    }
}
