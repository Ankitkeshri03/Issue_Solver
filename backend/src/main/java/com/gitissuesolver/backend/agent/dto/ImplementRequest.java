package com.gitissuesolver.backend.agent.dto;

public record ImplementRequest(
        Long ticketId,
        Integer issueNumber,
        String issueTitle,
        String issueDescription,
        String plan,
        String repoCloneUrl,
        Long repoId,
        String branchName,
        String baseBranch,
        String githubToken
) {}
