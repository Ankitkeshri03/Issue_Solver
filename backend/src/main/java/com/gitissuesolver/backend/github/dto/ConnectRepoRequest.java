package com.gitissuesolver.backend.github.dto;

import jakarta.validation.constraints.NotBlank;

public record ConnectRepoRequest(
        @NotBlank String owner,
        @NotBlank String repo
) {}
