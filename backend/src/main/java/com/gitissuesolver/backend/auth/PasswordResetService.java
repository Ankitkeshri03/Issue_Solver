package com.gitissuesolver.backend.auth;

import com.gitissuesolver.backend.auth.dto.ResetPasswordRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;

@Service
public class PasswordResetService {
    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final PasswordResetMailService mail;
    private final long ttlMinutes;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
            PasswordEncoder encoder, PasswordResetMailService mail,
            @Value("${app.password-reset.ttl-minutes:15}") long ttlMinutes) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.mail = mail;
        if (ttlMinutes < 1 || ttlMinutes > 60) throw new IllegalArgumentException("Reset TTL must be between 1 and 60 minutes");
        this.ttlMinutes = ttlMinutes;
    }

    @Transactional
    public void requestReset(String email) {
        String normalized = normalize(email);
        User user = users.findByEmailForUpdate(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No account found with this email. Ask your company admin or project manager for an invitation."));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.deleteForUser(user.getId());
        PasswordResetToken token = new PasswordResetToken();
        token.setTokenHash(RecoveryRateLimiter.hash(raw));
        token.setUser(user);
        token.setExpiresAt(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES));
        tokens.saveAndFlush(token);
        mail.sendReset(user.getEmail(), raw, ttlMinutes);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("Passwords do not match");
        }
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must be at most 72 bytes");
        }
        // Lock the account for both issuing and consuming tokens to prevent concurrent reuse.
        User user = users.findByEmailForUpdate(normalize(request.email())).orElseThrow(this::invalidLink);
        PasswordResetToken token = tokens.findById(RecoveryRateLimiter.hash(request.token())).orElseThrow(this::invalidLink);
        if (!token.getUser().getId().equals(user.getId()) || !token.getExpiresAt().isAfter(Instant.now())) {
            throw invalidLink();
        }
        user.setPasswordHash(encoder.encode(request.password()));
        user.setAuthVersion(user.getAuthVersion() + 1);
        users.save(user);
        tokens.deleteForUser(user.getId());
    }

    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    private ResponseStatusException invalidLink() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This reset link is invalid, expired, already used, or does not match your email. Request a new link.");
    }
}
