package com.gitissuesolver.backend.issue.dto;

import jakarta.validation.constraints.NotNull;

public record AssignTicketRequest(
        @NotNull Long developerId
) {}
