package com.gitissuesolver.backend.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Thin client over the GitHub REST API. Requires app.github.token (GITHUB_TOKEN env var)
 * for anything beyond public, low-rate-limit reads.
 */
@Service
public class GitHubService {

    private final RestClient restClient;
    private final String token;

    public GitHubService(@Value("${github.token:}") String token) {
        this.token = token;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    public List<GitHubIssueDto> fetchOpenIssues(String owner, String repo) {
        GitHubIssueDto[] issues = restClient.get()
                .uri("/repos/{owner}/{repo}/issues?state=open&per_page=100", owner, repo)
                .headers(this::authorize)
                .retrieve()
                .body(GitHubIssueDto[].class);
        return issues != null ? List.of(issues) : List.of();
    }

    public String createBranch(String owner, String repo, String branchName, String fromSha) {
        record RefBody(String ref, String sha) {}
        restClient.post()
                .uri("/repos/{owner}/{repo}/git/refs", owner, repo)
                .headers(this::authorize)
                .body(new RefBody("refs/heads/" + branchName, fromSha))
                .retrieve()
                .toBodilessEntity();
        return branchName;
    }

    public String getDefaultBranchSha(String owner, String repo, String branch) {
        record CommitRef(String sha) {}
        CommitRef ref = restClient.get()
                .uri("/repos/{owner}/{repo}/git/refs/heads/{branch}", owner, repo, branch)
                .headers(this::authorize)
                .retrieve()
                .body(CommitRef.class);
        return ref != null ? ref.sha() : null;
    }

    public String createPullRequest(String owner, String repo, String title, String head, String base, String body) {
        record PrBody(String title, String head, String base, String body) {}
        record PrResponse(String html_url) {}
        PrResponse response = restClient.post()
                .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                .headers(this::authorize)
                .body(new PrBody(title, head, base, body))
                .retrieve()
                .body(PrResponse.class);
        return response != null ? response.html_url() : null;
    }

    private void authorize(HttpHeaders headers) {
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
    }

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }
}
