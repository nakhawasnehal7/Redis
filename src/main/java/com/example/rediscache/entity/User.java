package com.example.rediscache.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * The system-of-record row, persisted in the primary database (your "shopping" Postgres
 * instance). Table is named "app_users" (not "users") to avoid colliding with any
 * shopping-domain table of that name that may already exist in the same schema.
 * Redis never replaces this table - it only caches derived views of it.
 */
@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    private String email;

    private String fullName;
}
