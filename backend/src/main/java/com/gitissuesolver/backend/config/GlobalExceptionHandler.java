package com.gitissuesolver.backend.config;

import com.gitissuesolver.backend.agent.AiServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    /** Carries the AI service's own error message and status straight through to the UI. */
    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<Map<String, String>> handleAiService(AiServiceException ex) {
        HttpStatus status = ex.isQuotaExceeded() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of("error", ex.getMessage()));
    }

    // Thrown by both the AI-service client and the GitHub client (both use RestClient) --
    // the message is generic on purpose since the real cause (GitHub 404, AI service down,
    // etc.) is already embedded in ex.getMessage().
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> handleUpstream(RestClientException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Upstream request failed: " + ex.getMessage()));
    }
}
