package com.gitissuesolver.backend.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Thin client over the GitHub REST API. Each method accepts an explicit token so callers
 * can use the connecting user's own personal access token (see User.githubToken /
 * Repo.githubToken) instead of a single shared credential. Falls back to app.github.token
 * (GITHUB_TOKEN env var) when no per-user/per-repo token is available -- keeps the
 * locally-seeded dummy repo working without requiring anyone to connect a GitHub account.
 */
@Service
public class GitHubService {

    private final RestClient restClient;
    private final String fallbackToken;

    public GitHubService(@Value("${github.token:}") String fallbackToken) {
        this.fallbackToken = fallbackToken;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** Calls GET /user with the given token and returns the authenticated login, or throws if invalid. */
    public String verifyTokenAndGetLogin(String token) {
        record GhUser(String login) {}
        GhUser user = restClient.get()
                .uri("/user")
                .headers(h -> authorize(h, token))
                .retrieve()
                .body(GhUser.class);
        return user != null ? user.login() : null;
    }

    /** Repos the given token can read/write, most-recently-updated first. */
    public List<GitHubRepoDto> listAccessibleRepos(String token) {
        GitHubRepoDto[] repos = restClient.get()
                .uri("/user/repos?per_page=100&sort=updated")
                .headers(h -> authorize(h, token))
                .retrieve()
                .body(GitHubRepoDto[].class);
        return repos != null ? List.of(repos) : List.of();
    }

    public List<GitHubIssueDto> fetchOpenIssues(String owner, String repo, String token) {
        GitHubIssueDto[] issues = restClient.get()
                .uri("/repos/{owner}/{repo}/issues?state=open&per_page=100", owner, repo)
                .headers(h -> authorize(h, token))
                .retrieve()
                .body(GitHubIssueDto[].class);
        return issues != null ? List.of(issues) : List.of();
    }

    public String createBranch(String owner, String repo, String branchName, String fromSha, String token) {
        record RefBody(String ref, String sha) {}
        restClient.post()
                .uri("/repos/{owner}/{repo}/git/refs", owner, repo)
                .headers(h -> authorize(h, token))
                .body(new RefBody("refs/heads/" + branchName, fromSha))
                .retrieve()
                .toBodilessEntity();
        return branchName;
    }

    public String getDefaultBranchSha(String owner, String repo, String branch, String token) {
        record CommitRef(String sha) {}
        CommitRef ref = restClient.get()
                .uri("/repos/{owner}/{repo}/git/refs/heads/{branch}", owner, repo, branch)
                .headers(h -> authorize(h, token))
                .retrieve()
                .body(CommitRef.class);
        return ref != null ? ref.sha() : null;
    }

    public String createPullRequest(String owner, String repo, String title, String head, String base, String body, String token) {
        record PrBody(String title, String head, String base, String body) {}
        record PrResponse(String html_url) {}
        PrResponse response = restClient.post()
                .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                .headers(h -> authorize(h, token))
                .body(new PrBody(title, head, base, body))
                .retrieve()
                .body(PrResponse.class);
        return response != null ? response.html_url() : null;
    }

    private void authorize(HttpHeaders headers, String token) {
        String effective = (token != null && !token.isBlank()) ? token : fallbackToken;
        if (effective != null && !effective.isBlank()) {
            headers.setBearerAuth(effective);
        }
    }

    public boolean hasFallbackToken() {
        return fallbackToken != null && !fallbackToken.isBlank();
    }
}
