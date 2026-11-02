package com.gitissuesolver.backend.auth;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Sends through HTTPS with the operator's offline gmail.send authorization. */
@Service
public class GmailApiEmailSender {
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    private final RestClient client;
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    public GmailApiEmailSender(@Value("${app.mail.gmail.client-id:}") String clientId,
            @Value("${app.mail.gmail.client-secret:}") String clientSecret,
            @Value("${app.mail.gmail.refresh-token:}") String refreshToken) {
        this(httpClient(), clientId, clientSecret, refreshToken);
    }

    GmailApiEmailSender(RestClient client, String clientId, String clientSecret, String refreshToken) {
        this.client = client;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
    }

    private static RestClient httpClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !refreshToken.isBlank();
    }

    private synchronized String accessToken() {
        if (cachedToken != null && expiresAt.isAfter(Instant.now().plusSeconds(30))) return cachedToken;
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        Map<?, ?> response = client.post().uri(TOKEN_URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class);
        if (response == null || !(response.get("access_token") instanceof String token) || token.isBlank()
                || !(response.get("expires_in") instanceof Number seconds) || seconds.longValue() <= 0
                || !"Bearer".equalsIgnoreCase(String.valueOf(response.get("token_type")))) {
            throw unavailable();
        }
        cachedToken = token;
        expiresAt = Instant.now().plusSeconds(Math.min(seconds.longValue(), 3600));
        return cachedToken;
    }

    private synchronized void invalidate(String token) {
        if (token != null && token.equals(cachedToken)) { cachedToken = null; expiresAt = Instant.EPOCH; }
    }

    public void send(String from, String recipient, String subject, String text) {
        if (!isConfigured()) throw unavailable();
        String token = null;
        try {
            // Use JavaMail only to encode MIME; there is no SMTP connection.
            if (from.contains("\r") || from.contains("\n") || recipient.contains("\r") || recipient.contains("\n"))
                throw unavailable();
            var message = new MimeMessage(Session.getInstance(new Properties()));
            var sender = new InternetAddress(from, true);
            var receiver = new InternetAddress(recipient, true);
            sender.validate(); receiver.validate();
            message.setFrom(sender);
            message.setRecipient(Message.RecipientType.TO, receiver);
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            message.setText(text, StandardCharsets.UTF_8.name());
            message.saveChanges();
            message.setHeader("Message-ID", "<" + UUID.randomUUID() + "@issue-solver.local>");
            var bytes = new ByteArrayOutputStream();
            message.writeTo(bytes);
            String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            token = accessToken();
            // Do not retry a send automatically: an ambiguous network failure can mean delivery succeeded.
            client.post().uri(SEND_URL).header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("raw", raw))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) invalidate(token);
            throw unavailable();
        } catch (RestClientException | jakarta.mail.MessagingException | java.io.IOException ex) {
            // Google response bodies can contain credential diagnostics. Never return or log them.
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "We could not send your reset email. Please try again later.");
    }
}
