package com.gitissuesolver.backend.github;

import com.gitissuesolver.backend.auth.CustomUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repos/{repoId}/test-runner")
public class TestRunnerController {
    private final ProjectAccess access;
    private final GitHubService github;
    public TestRunnerController(ProjectAccess access, GitHubService github) { this.access = access; this.github = github; }
    @GetMapping
    public GitHubService.RunnerStatus status(@PathVariable Long repoId, @AuthenticationPrincipal CustomUserDetails user) {
        return github.runnerStatus(access.managedRepo(repoId, user.getUser().getId()));
    }
    @PostMapping
    public GitHubService.RunnerStatus install(@PathVariable Long repoId, @AuthenticationPrincipal CustomUserDetails user) {
        return github.installRunner(access.managedRepo(repoId, user.getUser().getId()));
    }
}
