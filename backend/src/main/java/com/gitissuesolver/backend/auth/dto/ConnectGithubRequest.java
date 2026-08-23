package com.gitissuesolver.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ConnectGithubRequest(
        @NotBlank String token
) {}
