package com.gitissuesolver.backend.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateTicketRequest(
        @NotNull Long repoId,
        @NotNull Integer githubIssueNumber,
        @NotBlank String title,
        String description
) {}
