package com.gitissuesolver.backend.auth.dto;

public record GithubAccountStatus(
        boolean connected,
        String githubLogin
) {}
