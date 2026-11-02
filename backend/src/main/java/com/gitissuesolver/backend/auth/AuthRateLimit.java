package com.gitissuesolver.backend.auth;

import jakarta.persistence.*;

/** Shared PostgreSQL counters keep recovery limits effective across backend instances. */
@Entity
@Table(name = "auth_rate_limits", indexes = @Index(name = "idx_auth_limit_window", columnList = "window_start"))
public class AuthRateLimit {
    @Id
    @Column(name = "key_hash", length = 64)
    private String keyHash;
    @Column(name = "window_start", nullable = false)
    private long windowStart;
    @Column(nullable = false)
    private int attempts;
}
