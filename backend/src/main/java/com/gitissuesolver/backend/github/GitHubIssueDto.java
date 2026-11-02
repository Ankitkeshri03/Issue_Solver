package com.gitissuesolver.backend.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssueDto(
        Integer number,
        String title,
        String body,
        String state,
        String html_url,
        @JsonProperty("pull_request") Map<String, Object> pullRequest
) {
    @JsonIgnore
    public boolean isPullRequest() { return pullRequest != null; }
}
