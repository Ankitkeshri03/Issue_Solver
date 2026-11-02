package com.gitissuesolver.backend.auth;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

@Component
@Converter
public class EncryptedTokenConverter implements AttributeConverter<String, String> {
    private static final String PREFIX = "enc:v1:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public EncryptedTokenConverter(@Value("${app.token-encryption-key:}") String encodedKey) {
        if (encodedKey.isBlank()) { key = null; return; }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encodedKey); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("TOKEN_ENCRYPTION_KEY must be a base64-encoded 32-byte key"); }
        if (bytes.length != 32) throw new IllegalArgumentException("TOKEN_ENCRYPTION_KEY must contain 32 bytes");
        key = new SecretKeySpec(bytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String value) {
        if (value == null || value.isBlank() || key == null) return value;
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] joined = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, joined, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(joined);
        } catch (Exception ex) { throw new IllegalStateException("Could not encrypt stored GitHub token", ex); }
    }

    @Override
    public String convertToEntityAttribute(String value) {
        if (value == null || !value.startsWith(PREFIX)) return value;
        if (key == null) throw new IllegalStateException("TOKEN_ENCRYPTION_KEY is required to read stored tokens");
        try {
            byte[] joined = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, joined, 0, 12));
            return new String(cipher.doFinal(joined, 12, joined.length - 12), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Could not decrypt stored GitHub token; check TOKEN_ENCRYPTION_KEY", ex); }
    }
}
