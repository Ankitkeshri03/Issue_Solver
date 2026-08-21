package com.gitissuesolver.backend.agent.dto;

public record AnalyzeRequest(
        Long ticketId,
        Integer issueNumber,
        String issueTitle,
        String issueDescription,
        String repoCloneUrl,
        Long repoId
) {}
