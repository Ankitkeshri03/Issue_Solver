package com.gitissuesolver.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import java.nio.charset.StandardCharsets;
import com.gitissuesolver.backend.auth.GmailApiEmailSender;

@Configuration
@Profile("prod")
public class ProductionAuthConfig {
    public ProductionAuthConfig(@Value("${app.jwt.secret}") String secret,
            @Value("${app.password-reset.frontend-url}") String frontendUrl,
            @Value("${spring.mail.host}") String smtpHost,
            @Value("${spring.mail.username}") String smtpUsername,
            @Value("${spring.mail.password}") String smtpPassword,
            @Value("${spring.mail.properties[mail.smtp.auth]:true}") boolean smtpAuth,
            @Value("${app.mail.provider}") String provider,
            GmailApiEmailSender gmail,
            @Value("${app.token-encryption-key:}") String tokenKey,
            @Value("${ai.service.token:}") String serviceToken) {
        if (tokenKey.isBlank() || serviceToken.length() < 32) {
            throw new IllegalArgumentException("Configure TOKEN_ENCRYPTION_KEY and a shared AI_SERVICE_TOKEN of at least 32 characters");
        }
        if (secret.startsWith("dev-only-") || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Set a unique JWT_SECRET of at least 32 bytes for production");
        }
        boolean mailConfigured = provider.equals("gmail_api") ? gmail.isConfigured()
                : provider.equals("smtp") && !smtpHost.isBlank()
                    && (!smtpAuth || (!smtpUsername.isBlank() && !smtpPassword.isBlank()));
        if (!frontendUrl.startsWith("https://") || !mailConfigured) {
            throw new IllegalArgumentException("Configure an HTTPS FRONTEND_URL and Gmail OAuth credentials (or SMTP settings) for production");
        }
    }
}
