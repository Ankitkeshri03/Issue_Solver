package com.gitissuesolver.backend.agent;

/** Carries the AI service's actual error message and status through to the caller. */
public class AiServiceException extends RuntimeException {

    private final int status;

    public AiServiceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /** Gemini quota exhaustion is worth distinguishing: it's transient and user-actionable. */
    public boolean isQuotaExceeded() {
        return status == 429;
    }
}
