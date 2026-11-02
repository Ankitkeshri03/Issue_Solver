package com.gitissuesolver.backend.github.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ConnectRepoRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]+") String owner,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]+") String repo,
        @NotNull Long projectId
) {}
