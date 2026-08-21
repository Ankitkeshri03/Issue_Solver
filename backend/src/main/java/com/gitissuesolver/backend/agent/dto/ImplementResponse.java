package com.gitissuesolver.backend.agent.dto;

public record ImplementResponse(
        boolean success,
        String prUrl,
        String diff,
        String testOutput,
        int attempts,
        String failureReason
) {}
