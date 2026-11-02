package com.gitissuesolver.backend.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.mail.provider=gmail_api", "app.password-reset.enabled=true",
        "app.password-reset.frontend-url=https://app.example.com", "app.password-reset.from=sender@gmail.com"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GmailPasswordRecoveryTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordResetTokenRepository tokens;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GmailApiEmailSender gmail;
    private User user;

    @BeforeEach
    void setup() {
        tokens.deleteAll();
        jdbc.update("DELETE FROM auth_rate_limits");
        users.deleteAll();
        reset(gmail);
        user = users.save(User.builder().username("gmailtester").email("recipient@example.com")
                .passwordHash(encoder.encode("Original123!")).role(Role.DEVELOPER).build());
    }

    private String requestLink() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"RECIPIENT@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").doesNotExist());
        var body = ArgumentCaptor.forClass(String.class);
        verify(gmail).send(eq("sender@gmail.com"), eq("recipient@example.com"),
                eq("Reset your Issue Solver password"), body.capture());
        var match = Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(body.getValue());
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    @Test
    void gmailRecoveryChangesPasswordOnceWithMatchingEmail() throws Exception {
        String raw = requestLink();
        String json = "{\"email\":\"recipient@example.com\",\"token\":\"" + raw
                + "\",\"password\":\"Replacement123!\",\"confirmPassword\":\"Replacement123!\"}";
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
        User updated = users.findById(user.getId()).orElseThrow();
        assertThat(encoder.matches("Replacement123!", updated.getPasswordHash())).isTrue();
        assertThat(updated.getAuthVersion()).isEqualTo(1);
        assertThat(tokens.count()).isZero();
    }

    @Test
    void googleFailurePreservesEarlierLinkAndPassword() throws Exception {
        String original = requestLink();
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "We could not send your reset email. Please try again later."))
                .when(gmail).send(anyString(), anyString(), anyString(), anyString());
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"recipient@example.com\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value(
                        "We could not send your reset email. Please try again later."));
        assertThat(tokens.findById(RecoveryRateLimiter.hash(original))).isPresent();
        assertThat(encoder.matches("Original123!", users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void unknownEmailRequestsInvitationWithoutSending() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"missing@example.com\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value(
                        "No account found with this email. Ask your company admin or project manager for an invitation."));
        verifyNoInteractions(gmail);
    }
}
