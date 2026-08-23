package com.gitissuesolver.backend.auth;

import com.gitissuesolver.backend.auth.dto.ConnectGithubRequest;
import com.gitissuesolver.backend.auth.dto.GithubAccountStatus;
import com.gitissuesolver.backend.auth.dto.UserSummary;
import com.gitissuesolver.backend.github.GitHubService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class UserController {

    private final UserRepository userRepository;
    private final GitHubService gitHubService;

    public UserController(UserRepository userRepository, GitHubService gitHubService) {
        this.userRepository = userRepository;
        this.gitHubService = gitHubService;
    }

    @GetMapping("/api/users")
    public List<UserSummary> list(@RequestParam(required = false) Role role) {
        return userRepository.findAll().stream()
                .filter(u -> role == null || u.getRole() == role)
                .map(UserSummary::from)
                .toList();
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
        User user = userRepository.findById(principal.getUser().getId()).orElseThrow();
        user.setGithubToken(null);
        userRepository.save(user);
    }
}
