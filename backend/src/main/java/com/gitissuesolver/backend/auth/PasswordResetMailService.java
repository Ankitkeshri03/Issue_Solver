package com.gitissuesolver.backend.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;

@Service
public class PasswordResetMailService {
    private final ObjectProvider<JavaMailSender> mailSender;
    private final boolean enabled;
    private final String frontendUrl;
    private final String from;
    private final GmailApiEmailSender gmail;
    private final String provider;

    public PasswordResetMailService(ObjectProvider<JavaMailSender> mailSender,
            GmailApiEmailSender gmail,
            @Value("${app.mail.provider:gmail_api}") String provider,
            @Value("${app.password-reset.enabled:false}") boolean enabled,
            @Value("${app.password-reset.frontend-url:http://localhost:5173}") String frontendUrl,
            @Value("${app.password-reset.from:}") String from) {
        this.mailSender = mailSender;
        this.gmail = gmail;
        this.provider = provider;
        if (!provider.equals("smtp") && !provider.equals("gmail_api")) {
            throw new IllegalArgumentException("MAIL_PROVIDER must be gmail_api or smtp");
        }
        this.enabled = enabled;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        this.from = from;
        if (enabled) {
            URI uri = URI.create(this.frontendUrl);
            boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getUserInfo() != null || from.isBlank()
                    || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) {
                throw new IllegalArgumentException("Configure MAIL_FROM and an HTTPS FRONTEND_URL (HTTP allowed only on localhost)");
            }
        }
    }

    public void sendReset(String email, String token, long ttlMinutes) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (!enabled || (provider.equals("smtp") && sender == null)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Password recovery is temporarily unavailable. Please contact support.");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Reset your Issue Solver password");
        // A fragment keeps the reset token out of HTTP request URLs and referrer headers.
        message.setText("A password reset was requested for your Issue Solver account.\n\n"
                + "Open this link and enter your registered email to choose a new password:\n"
                + frontendUrl + "/reset-password#token=" + token + "\n\n"
                + "This link expires in " + ttlMinutes + " minutes and can be used once.\n"
                + "If you did not request this, you can ignore this email. Your password has not changed.");
        if (provider.equals("gmail_api")) {
            gmail.send(from, email, message.getSubject(), message.getText());
            return;
        }
        try {
            sender.send(message);
        } catch (MailException ex) {
            // Do not return SMTP credentials, reset links, or provider diagnostics to clients.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "We could not send your reset email. Please try again later.");
        }
    }
}
