package com.gitissuesolver.backend.auth;

import com.gitissuesolver.backend.auth.dto.ResetPasswordRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.mail.provider=smtp",
        "app.password-reset.enabled=true", "app.password-reset.frontend-url=https://app.example.com",
        "app.password-reset.from=noreply@example.com", "spring.mail.host=localhost"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordRecoveryTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordResetTokenRepository tokens;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired PasswordResetService recovery;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender sender;
    private User user;

    @BeforeEach
    void setup() {
        tokens.deleteAll();
        jdbc.update("DELETE FROM auth_rate_limits");
        users.deleteAll();
        reset(sender);
        user = users.save(User.builder().username("tester").email("owner@example.com")
                .passwordHash(encoder.encode("Original123!")).role(Role.DEVELOPER).build());
    }

    String requestLink() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"OWNER@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.token").doesNotExist());
        var capture = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, atLeastOnce()).send(capture.capture());
        SimpleMailMessage mail = capture.getAllValues().getLast();
        assertThat(mail.getTo()).containsExactly("owner@example.com");
        var match = Pattern.compile("https://app.example.com/reset-password#token=([A-Za-z0-9_-]{43})").matcher(mail.getText());
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    String resetBody(String email, String token, String password, String confirmation) {
        return "{\"email\":\"" + email + "\",\"token\":\"" + token
                + "\",\"password\":\"" + password + "\",\"confirmPassword\":\"" + confirmation + "\"}";
    }

    void changePassword(String email, String token, int expectedStatus) throws Exception {
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody(email, token, "Replacement123!", "Replacement123!")))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void loginAcceptsEmailAndUsername() throws Exception {
        for (String identifier : new String[]{"tester", "OWNER@example.com"}) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + identifier + "\",\"password\":\"Original123!\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("tester"));
        }
    }

    @Test
    void unknownEmailReturnsRequestedCreateAccountMessage() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"missing@example.com\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value(
                        "No account found with this email. Ask your company admin or project manager for an invitation."));
        verifyNoInteractions(sender);
        assertThat(tokens.count()).isZero();
    }

    @Test
    void requestStoresOnlyHashedTokenAndKeepsPasswordUnchanged() throws Exception {
        String raw = requestLink();
        var token = tokens.findAll().getFirst();
        assertThat(token.getTokenHash()).isEqualTo(RecoveryRateLimiter.hash(raw)).doesNotContain(raw);
        assertThat(token.getExpiresAt()).isAfter(Instant.now()).isBefore(Instant.now().plusSeconds(901));
        assertThat(encoder.matches("Original123!", users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void resetRequiresMatchingEmailAndLink() throws Exception {
        String raw = requestLink();
        users.save(User.builder().username("other").email("other@example.com")
                .passwordHash(encoder.encode("OtherPassword1")).role(Role.QA).build());
        changePassword("other@example.com", raw, 400);
        assertThat(encoder.matches("Original123!", users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
        changePassword("OWNER@example.com", raw, 200);
    }

    @Test
    void resetChangesPasswordOnceAndInvalidatesExistingJwt() throws Exception {
        String oldJwt = jwt.generateToken(user);
        String raw = requestLink();
        changePassword(user.getEmail(), raw, 200);
        changePassword(user.getEmail(), raw, 400);
        User updated = users.findById(user.getId()).orElseThrow();
        assertThat(encoder.matches("Replacement123!", updated.getPasswordHash())).isTrue();
        assertThat(jwt.isTokenValid(oldJwt, updated)).isFalse();
        assertThat(tokens.count()).isZero();
        mvc.perform(get("/api/users/me/github").header("Authorization", "Bearer " + oldJwt))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner@example.com\",\"password\":\"Original123!\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner@example.com\",\"password\":\"Replacement123!\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void expiredLinkCannotChangePassword() throws Exception {
        String raw = requestLink();
        var token = tokens.findAll().getFirst();
        token.setExpiresAt(Instant.now().minusSeconds(1));
        tokens.save(token);
        changePassword(user.getEmail(), raw, 400);
        assertThat(users.findById(user.getId()).orElseThrow().getAuthVersion()).isZero();
    }

    @Test
    void requestingAnotherLinkInvalidatesTheEarlierOne() throws Exception {
        String first = requestLink();
        String second = requestLink();
        assertThat(second).isNotEqualTo(first);
        assertThat(tokens.count()).isEqualTo(1);
        changePassword(user.getEmail(), first, 400);
        changePassword(user.getEmail(), second, 200);
    }

    @Test
    void passwordConfirmationAndValidationDoNotConsumeToken() throws Exception {
        String raw = requestLink();
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(user.getEmail(), raw, "Replacement123!", "Different123!")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Passwords do not match"));
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(user.getEmail(), raw, "short", "short")))
                .andExpect(status().isBadRequest());
        changePassword(user.getEmail(), raw, 200);
    }

    @Test
    void mailFailureRollsBackReplacementAndReturnsSafeError() throws Exception {
        String original = requestLink();
        doThrow(new MailSendException("sensitive provider diagnostics")).when(sender).send(any(SimpleMailMessage.class));
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"owner@example.com\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value(
                        "We could not send your reset email. Please try again later."));
        assertThat(tokens.findById(RecoveryRateLimiter.hash(original))).isPresent();
        changePassword(user.getEmail(), original, 200);
    }

    @Test
    void requestLimitSurvivesUnknownEmailFailures() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"missing@example.com\"}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"missing@example.com\"}"))
                .andExpect(status().isTooManyRequests());
        verifyNoInteractions(sender);
    }

    @Test
    void registeredEmailCannotBeFloodedWithResetEmails() throws Exception {
        for (int i = 0; i < 5; i++) requestLink();
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"owner@example.com\"}"))
                .andExpect(status().isTooManyRequests());
        verify(sender, times(5)).send(any(SimpleMailMessage.class));
    }

    @Test
    void clientAddressLimitCoversManyDifferentEmails() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"missing" + i + "@example.com\"}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"another@example.com\"}"))
                .andExpect(status().isTooManyRequests());
        verifyNoInteractions(sender);
    }

    @Test
    void failedResetAttemptsAreRateLimited() throws Exception {
        String raw = "a".repeat(43);
        for (int i = 0; i < 20; i++) changePassword(user.getEmail(), raw, 400);
        changePassword(user.getEmail(), raw, 429);
    }

    @Test
    void concurrentRequestsCanConsumeALinkOnlyOnce() throws Exception {
        String raw = requestLink();
        Callable<Boolean> reset = () -> {
            try {
                recovery.resetPassword(new ResetPasswordRequest(user.getEmail(), raw,
                        "Replacement123!", "Replacement123!"));
                return true;
            } catch (ResponseStatusException ex) {
                assertThat(ex.getStatusCode().value()).isEqualTo(400);
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(java.util.List.of(reset, reset));
            assertThat(results.get(0).get()).isNotEqualTo(results.get(1).get());
        }
        assertThat(users.findById(user.getId()).orElseThrow().getAuthVersion()).isEqualTo(1);
    }
}
