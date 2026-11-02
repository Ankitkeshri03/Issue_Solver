package com.gitissuesolver.backend.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.gitissuesolver.backend.issue.Severity;

public record CreateTicketRequest(
        @NotNull Long repoId,
        @NotNull Integer githubIssueNumber,
        @NotBlank String title,
        String description,
        @Size(max = 6000) String reproductionSteps,
        @Size(max = 6000) String expectedBehavior,
        @Size(max = 6000) String actualBehavior,
        Severity severity
) {}
