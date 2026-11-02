package com.gitissuesolver.backend.auth;

import com.gitissuesolver.backend.config.ProductionAuthConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionAuthConfigTests {
    @Test
    void productionRequiresHttpsSecretAndConfiguredMailProvider() {
        String secret = "a-unique-test-secret-that-is-long-enough-12345";
        var gmail = mock(GmailApiEmailSender.class);
        when(gmail.isConfigured()).thenReturn(true);
        assertThatCode(() -> new ProductionAuthConfig(secret, "https://app.example.com",
                "smtp.example.com", "smtp-user", "smtp-key", true, "smtp", gmail, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", secret)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new ProductionAuthConfig("dev-only-secret-change-me-dev-only-secret-change-me",
                "https://app.example.com", "smtp.example.com", "user", "key", true, "smtp", gmail, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", secret)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductionAuthConfig(secret, "http://app.example.com",
                "smtp.example.com", "user", "key", true, "smtp", gmail, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", secret)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductionAuthConfig(secret, "https://app.example.com",
                "smtp.example.com", "", "", true, "smtp", gmail, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", secret)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ProductionAuthConfig(secret, "https://app.example.com",
                "", "", "", true, "gmail_api", gmail, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", secret)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new ProductionAuthConfig(secret, "https://app.example.com",
                "", "", "", false, "gmail_api", mock(GmailApiEmailSender.class), "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", secret)).isInstanceOf(IllegalArgumentException.class);
    }
}
