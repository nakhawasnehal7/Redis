package com.example.rediscache.config;

import com.example.rediscache.entity.User;
import com.example.rediscache.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds two demo users on startup. In a real deployment this class wouldn't exist -
 * users would already live in RDS/Aurora. Both demo users log in with password: password123
 */
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("password123");

        userRepository.save(new User(null, "alice", hash, "alice@example.com", "Alice Johnson"));
        userRepository.save(new User(null, "bob", hash, "bob@example.com", "Bob Smith"));
    }
}
