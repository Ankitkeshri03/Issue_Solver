package com.gitissuesolver.backend.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssueDto(
        Integer number,
        String title,
        String body,
        String state,
        String html_url
) {}
