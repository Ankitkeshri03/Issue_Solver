package com.gitissuesolver.backend.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepoDto(
        String name,
        @JsonProperty("full_name") String fullName,
        String language,
        @JsonProperty("private") boolean isPrivate,
        @JsonProperty("default_branch") String defaultBranch,
        @JsonProperty("clone_url") String cloneUrl,
        String description
) {}
