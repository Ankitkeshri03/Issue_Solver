package com.gitissuesolver.backend.github;

import com.gitissuesolver.backend.github.dto.ConnectRepoRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoRepository repoRepository;
    private final GitHubService gitHubService;

    public RepoController(RepoRepository repoRepository, GitHubService gitHubService) {
        this.repoRepository = repoRepository;
        this.gitHubService = gitHubService;
    }

    @GetMapping
    public List<Repo> listRepos() {
        return repoRepository.findAll();
    }

    @PostMapping("/connect")
    public ResponseEntity<Repo> connect(@Valid @RequestBody ConnectRepoRequest request) {
        Repo repo = repoRepository.findByGithubOwnerAndGithubRepo(request.owner(), request.repo())
                .orElseGet(() -> repoRepository.save(Repo.builder()
                        .name(request.repo())
                        .githubOwner(request.owner())
                        .githubRepo(request.repo())
                        .cloneUrl("https://github.com/%s/%s.git".formatted(request.owner(), request.repo()))
                        .build()));
        return ResponseEntity.ok(repo);
    }

    @GetMapping("/{owner}/{repo}/issues")
    public List<GitHubIssueDto> issues(@PathVariable String owner, @PathVariable String repo) {
        return gitHubService.fetchOpenIssues(owner, repo);
    }
}
