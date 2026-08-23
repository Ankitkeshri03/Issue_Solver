package com.gitissuesolver.backend.github;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "repos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Repo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "github_owner", nullable = false)
    private String githubOwner;

    @Column(name = "github_repo", nullable = false)
    private String githubRepo;

    @Column(name = "clone_url", nullable = false, length = 500)
    private String cloneUrl;

    @Column(name = "default_branch", nullable = false)
    @Builder.Default
    private String defaultBranch = "main";

    @Column(name = "indexed_at")
    private Instant indexedAt;

    // The GitHub token of whichever user connected this repo -- used for all subsequent
    // issue reads, branch pushes, and PR creation against it. See User.githubToken.
    // @JsonIgnore is load-bearing: Repo is serialized directly as an API response
    // (RepoController), so without this the token would leak to any authenticated caller.
    @JsonIgnore
    @Column(name = "github_token")
    private String githubToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    public String fullName() {
        return githubOwner + "/" + githubRepo;
    }
}
