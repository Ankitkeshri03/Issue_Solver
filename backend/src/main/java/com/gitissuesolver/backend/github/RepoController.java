package com.gitissuesolver.backend.github;

import com.gitissuesolver.backend.auth.CustomUserDetails;
import com.gitissuesolver.backend.github.dto.ConnectRepoRequest;
import com.gitissuesolver.backend.workspace.WorkspaceAccess;
import com.gitissuesolver.backend.workspace.WorkspaceService;
import com.gitissuesolver.backend.auth.Role;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoRepository repoRepository;
    private final GitHubService gitHubService;
    private final IssueSyncService issueSync;
    private final ProjectAccess access;
    private final WorkspaceAccess workspace;
    private final WorkspaceService workspaceService;
    public record RepoView(Long id, String name, String githubOwner, String githubRepo, String defaultBranch,
                           Long projectId, String projectName, Long workspaceId, Role myRole, boolean workspaceAdmin) {}

    public RepoController(RepoRepository repoRepository, GitHubService gitHubService, IssueSyncService issueSync,
                          ProjectAccess access, WorkspaceAccess workspace, WorkspaceService workspaceService) {
        this.repoRepository = repoRepository;
        this.gitHubService = gitHubService;
        this.issueSync = issueSync;
        this.access = access; this.workspace = workspace; this.workspaceService = workspaceService;
    }

    @GetMapping
    public List<RepoView> listRepos(@RequestParam(required = false) Long projectId, @AuthenticationPrincipal CustomUserDetails principal) {
        if (projectId != null) workspace.project(projectId, principal.getUser().getId());
        return repoRepository.findVisible(principal.getUser().getId()).stream()
                .filter(r -> projectId == null || projectId.equals(r.getProjectId()))
                .map(r -> view(r, principal.getUser().getId())).toList();
    }

    /** Repos the current user's connected GitHub account can read/write. */
    @GetMapping("/github/available")
    public List<GitHubRepoDto> availableRepos(@AuthenticationPrincipal CustomUserDetails principal) {
        if (!workspace.anyAdmin(principal.getUser().getId())) throw workspace.forbidden("Only workspace admins manage GitHub connections");
        String token = principal.getUser().getGithubToken();
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Connect your GitHub account first, then repos you have access to will show up here.");
        }
        return gitHubService.listAccessibleRepos(token);
    }

    @PostMapping("/connect")
    public ResponseEntity<RepoView> connect(@Valid @RequestBody ConnectRepoRequest request,
                                         @AuthenticationPrincipal CustomUserDetails principal) {
        var project = workspace.project(request.projectId(), principal.getUser().getId());
        workspace.admin(project.getWorkspaceId(), principal.getUser().getId());
        String token = principal.getUser().getGithubToken();
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Connect your GitHub account first");
        var metadata = gitHubService.repositoryInfo(request.owner(), request.repo(), token);

        Repo repo = repoRepository.findByProjectIdAndGithubOwnerAndGithubRepo(request.projectId(), request.owner(), request.repo())
                .map(existing -> {
                    if (token != null && !token.isBlank()) {
                        existing.setGithubToken(token);
                        existing.setDefaultBranch(metadata.default_branch());
                        repoRepository.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> repoRepository.save(Repo.builder()
                        .name(request.repo())
                        .githubOwner(request.owner())
                        .githubRepo(request.repo())
                        .ownerUserId(principal.getUser().getId())
                        .projectId(request.projectId())
                        .defaultBranch(metadata.default_branch())
                        .cloneUrl("https://github.com/%s/%s.git".formatted(request.owner(), request.repo()))
                        .githubToken(token)
                        .build()));
        return ResponseEntity.ok(view(repo, principal.getUser().getId()));
    }

    @GetMapping("/{repoId}/issues")
    public List<GitHubIssueDto> issues(@PathVariable Long repoId,
                                        @AuthenticationPrincipal CustomUserDetails principal) {
        Repo connected = access.repo(repoId, principal.getUser().getId());
        if (connected.getGithubToken() == null || connected.getGithubToken().isBlank())
            throw new IllegalArgumentException("Ask a workspace admin to connect this repository's GitHub account");
        return issueSync.sync(connected);
    }

    private RepoView view(Repo repo, Long actor) {
        var project = workspaceService.projectView(repo.getProjectId(), actor);
        return new RepoView(repo.getId(), repo.getName(), repo.getGithubOwner(), repo.getGithubRepo(), repo.getDefaultBranch(),
                project.id(), project.name(), project.workspaceId(), project.myRole(), project.workspaceAdmin());
    }
}
