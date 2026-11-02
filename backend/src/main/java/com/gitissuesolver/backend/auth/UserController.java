package com.gitissuesolver.backend.auth;

import com.gitissuesolver.backend.auth.dto.ConnectGithubRequest;
import com.gitissuesolver.backend.auth.dto.GithubAccountStatus;
import com.gitissuesolver.backend.auth.dto.UserSummary;
import com.gitissuesolver.backend.github.GitHubService;
import com.gitissuesolver.backend.workspace.WorkspaceService;
import com.gitissuesolver.backend.workspace.WorkspaceAccess;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class UserController {

    private final UserRepository userRepository;
    private final GitHubService gitHubService;
    private final WorkspaceService workspaceService;
    private final WorkspaceAccess workspaceAccess;

    public UserController(UserRepository userRepository, GitHubService gitHubService, WorkspaceService workspaceService, WorkspaceAccess workspaceAccess) {
        this.userRepository = userRepository;
        this.gitHubService = gitHubService;
        this.workspaceService = workspaceService; this.workspaceAccess = workspaceAccess;
    }

    @GetMapping("/api/users")
    public List<UserSummary> list(@RequestParam Long projectId, @RequestParam(required = false) Role role,
                                  @AuthenticationPrincipal CustomUserDetails principal) {
        return workspaceService.projectMembers(projectId, principal.getUser().getId()).stream()
                .filter(u -> role == null || u.role() == role)
                .map(u -> new UserSummary(u.userId(), u.username(), u.role()))
                .toList();
    }

    public record CurrentUser(Long id, String username, String email, Role role) {}
    @GetMapping("/api/users/me")
    public CurrentUser me(@AuthenticationPrincipal CustomUserDetails principal) {
        var user = principal.getUser(); return new CurrentUser(user.getId(), user.getUsername(), user.getEmail(), user.getRole());
    }

    @GetMapping("/api/users/me/github")
    public GithubAccountStatus githubStatus(@AuthenticationPrincipal CustomUserDetails principal) {
        User user = principal.getUser();
        if (user.getGithubToken() == null || user.getGithubToken().isBlank()) {
            return new GithubAccountStatus(false, null);
        }
        try {
            String login = gitHubService.verifyTokenAndGetLogin(user.getGithubToken());
            return new GithubAccountStatus(true, login);
        } catch (Exception e) {
            return new GithubAccountStatus(false, null);
        }
    }

    @PostMapping("/api/users/me/github")
    public GithubAccountStatus connectGithub(@AuthenticationPrincipal CustomUserDetails principal,
                                              @Valid @RequestBody ConnectGithubRequest request) {
        if (!workspaceAccess.anyAdmin(principal.getUser().getId())) throw workspaceAccess.forbidden("Only workspace admins manage GitHub connections");
        // Validate the token actually works before saving it, so a typo doesn't silently
        // brick every subsequent GitHub call for this user.
        String login;
        try {
            login = gitHubService.verifyTokenAndGetLogin(request.token());
        } catch (Exception e) {
            throw new IllegalArgumentException("That doesn't look like a valid GitHub token: " + e.getMessage());
        }

        User user = userRepository.findById(principal.getUser().getId()).orElseThrow();
        user.setGithubToken(request.token());
        userRepository.save(user);

        return new GithubAccountStatus(true, login);
    }

    @DeleteMapping("/api/users/me/github")
    public void disconnectGithub(@AuthenticationPrincipal CustomUserDetails principal) {
        if (!workspaceAccess.anyAdmin(principal.getUser().getId())) throw workspaceAccess.forbidden("Only workspace admins manage GitHub connections");
        User user = userRepository.findById(principal.getUser().getId()).orElseThrow();
        user.setGithubToken(null);
        userRepository.save(user);
    }
}
