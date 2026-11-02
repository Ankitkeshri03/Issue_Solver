package com.gitissuesolver.backend.auth;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GmailApiEmailSenderTests {
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    private MockRestServiceServer server;
    private GmailApiEmailSender sender;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new GmailApiEmailSender(builder.build(), "client-id", "secret+&value", "refresh-token");
    }

    void refresh(String token, int expires) {
        server.expect(requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("client_id=client-id&client_secret=secret%2B%26value&refresh_token=refresh-token&grant_type=refresh_token"))
                .andRespond(withSuccess("{\"access_token\":\"" + token + "\",\"expires_in\":" + expires + ",\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
    }

    void sendSuccess(String token) {
        server.expect(requestTo(SEND_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + token))
                .andRespond(withSuccess("{\"id\":\"message-id\"}", MediaType.APPLICATION_JSON));
    }

    @Test
    void sendsBase64UrlMimeWithUtf8AndCorrectAddresses() {
        refresh("access-token", 3600);
        server.expect(requestTo(SEND_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    try {
                        String json = ((MockClientHttpRequest) request).getBodyAsString();
                        String raw = JsonMapper.builder().build().readTree(json).get("raw").asString();
                        assertThat(raw).doesNotContain("+", "/", "=");
                        var mail = new MimeMessage(Session.getInstance(new Properties()),
                                new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
                        assertThat(mail.getFrom()[0].toString()).isEqualTo("sender@gmail.com");
                        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo("recipient@example.com");
                        assertThat(mail.getSubject()).isEqualTo("Réinitialiser le mot de passe");
                        assertThat(mail.getContent().toString()).contains("https://app.example.com/reset-password#token=abc", "您好");
                        assertThat(json).doesNotContain("client-id", "refresh-token", "secret+&value");
                    } catch (Exception ex) { throw new AssertionError(ex); }
                }).andRespond(withSuccess("{\"id\":\"message-id\"}", MediaType.APPLICATION_JSON));
        sender.send("sender@gmail.com", "recipient@example.com", "Réinitialiser le mot de passe",
                "您好\nhttps://app.example.com/reset-password#token=abc");
        server.verify();
    }

    @Test
    void reusesAccessTokenUntilItNeedsRefreshing() {
        refresh("cached", 3600);
        sendSuccess("cached");
        sendSuccess("cached");
        for (int i = 0; i < 2; i++) sender.send("sender@gmail.com", "recipient@example.com", "Reset", "Link");
        server.verify();
    }

    @Test
    void refreshesTokenNearExpiry() {
        refresh("old", 1);
        sendSuccess("old");
        refresh("new", 3600);
        sendSuccess("new");
        for (int i = 0; i < 2; i++) sender.send("sender@gmail.com", "recipient@example.com", "Reset", "Link");
        server.verify();
    }

    @Test
    void invalidGrantReturnsSafeErrorWithoutSending() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withBadRequest().body("sensitive-refresh-token-diagnostic"));
        assertSafeFailure();
        server.verify();
    }

    @Test
    void rejectedAccessTokenIsReplacedOnNextRequestWithoutRetryingEmail() {
        refresh("old", 3600);
        server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private diagnostic"));
        refresh("new", 3600);
        sendSuccess("new");
        assertSafeFailure();
        sender.send("sender@gmail.com", "recipient@example.com", "Reset", "Link");
        server.verify();
    }

    @Test
    void gmailQuotaFailureReturnsSafeErrorWithoutRetrying() {
        refresh("access", 3600);
        server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("sensitive diagnostic"));
        assertSafeFailure();
        server.verify();
    }

    @Test
    void invalidTokenResponseCannotSendEmail() {
        refresh("access", -1);
        assertSafeFailure();
        server.verify();
    }

    @Test
    void missingConfigurationAndHeaderInjectionMakeNoHttpRequests() {
        assertThat(new GmailApiEmailSender(RestClient.create(), "id", "secret", "").isConfigured()).isFalse();
        assertThatThrownBy(() -> new GmailApiEmailSender(RestClient.create(), "id", "secret", "")
                .send("sender@gmail.com", "recipient@example.com", "Reset", "Link"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> sender.send("sender@gmail.com", "recipient@example.com\r\nBcc: other@example.com", "Reset", "Link"))
                .isInstanceOf(ResponseStatusException.class);
        server.verify();
    }

    @Test
    void recoveryUsesGmailApiWithoutAnSmtpSender() {
        refresh("access", 3600);
        sendSuccess("access");
        var noSmtp = new StaticListableBeanFactory().getBeanProvider(org.springframework.mail.javamail.JavaMailSender.class);
        var recoveryMail = new PasswordResetMailService(noSmtp, sender, "gmail_api", true,
                "https://app.example.com", "sender@gmail.com");
        recoveryMail.sendReset("recipient@example.com", "a".repeat(43), 15);
        server.verify();
    }

    private void assertSafeFailure() {
        assertThatThrownBy(() -> sender.send("sender@gmail.com", "recipient@example.com", "Reset", "Link"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getReason()).isEqualTo("We could not send your reset email. Please try again later.");
                    assertThat(ex.getCause()).isNull();
                });
    }
}
