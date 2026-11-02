package com.gitissuesolver.backend.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @JsonIgnore
    @Column(name = "auth_version", nullable = false, columnDefinition = "bigint default 0")
    @Builder.Default
    private long authVersion = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    // The user's own GitHub personal access token, pasted in via "Connect GitHub".
    // Encrypted by the converter when TOKEN_ENCRYPTION_KEY is configured.
    @JsonIgnore
    @Convert(converter = EncryptedTokenConverter.class)
    @Column(name = "github_token", columnDefinition = "TEXT")
    private String githubToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
