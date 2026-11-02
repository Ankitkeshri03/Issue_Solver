package com.gitissuesolver.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class RecoveryRateLimiter {
    private final JdbcTemplate jdbc;

    public RecoveryRateLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Commit counters even when the calling reset fails or its transaction rolls back.
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ResponseStatusException.class)
    public void check(String key, int maximum, int windowSeconds) {
        checkCounter(key, maximum, windowSeconds, "Too many recovery attempts. Please try again later.");
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ResponseStatusException.class)
    public void checkInvitation(String key, int maximum, int windowSeconds) {
        checkCounter(key, maximum, windowSeconds, "Too many invitation attempts. Please try again later.");
    }
    private void checkCounter(String key, int maximum, int windowSeconds, String message) {
        long now = Instant.now().getEpochSecond();
        long window = now / windowSeconds * windowSeconds;
        Integer count = jdbc.queryForObject("""
                INSERT INTO auth_rate_limits (key_hash, window_start, attempts) VALUES (?, ?, 1)
                ON CONFLICT (key_hash) DO UPDATE SET
                    attempts = CASE WHEN auth_rate_limits.window_start = EXCLUDED.window_start
                                    THEN auth_rate_limits.attempts + 1 ELSE 1 END,
                    window_start = EXCLUDED.window_start
                RETURNING attempts
                """, Integer.class, hash(key), window);
        jdbc.update("DELETE FROM auth_rate_limits WHERE window_start < ?", now - 7200);
        if (count != null && count > maximum) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    message);
        }
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
