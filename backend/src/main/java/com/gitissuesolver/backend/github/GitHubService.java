package com.gitissuesolver.backend.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.core.io.ClassPathResource;

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

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubService(@Value("${github.token:}") String fallbackToken) {
        this(fallbackToken, RestClient.builder().baseUrl("https://api.github.com"));
    }

    GitHubService(String fallbackToken, RestClient.Builder builder) {
        this.fallbackToken = fallbackToken;
        this.restClient = builder
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
        return fetchRepositoryItems(owner, repo, token).stream()
                .filter(item -> !item.isPullRequest() && "open".equals(item.state())).toList();
    }

    /** GitHub includes PRs in /issues. Keep the marker for legacy-ticket repair. */
    public List<GitHubIssueDto> fetchRepositoryItems(String owner, String repo, String token) {
        var items = new ArrayList<GitHubIssueDto>();
        for (int page = 1; ; page++) {
            GitHubIssueDto[] batch = restClient.get()
                    .uri("/repos/{owner}/{repo}/issues?state=all&per_page=100&page={page}", owner, repo, page)
                    .headers(h -> authorize(h, token)).retrieve().body(GitHubIssueDto[].class);
            if (batch == null || batch.length == 0) break;
            items.addAll(List.of(batch));
            if (batch.length < 100) break;
        }
        return items;
    }

    public GitHubIssueDto fetchIssue(String owner, String repo, int number, String token) {
        return restClient.get().uri("/repos/{owner}/{repo}/issues/{number}", owner, repo, number)
                .headers(h -> authorize(h, token))
                .retrieve().body(GitHubIssueDto.class);
    }

    public boolean isPullRequestMerged(Repo repo, int number) {
        record PullRequestInfo(boolean merged) {}
        var response = restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", repo.getGithubOwner(), repo.getGithubRepo(), number)
                .headers(h -> authorize(h, repo.getGithubToken())).retrieve().body(PullRequestInfo.class);
        return response != null && response.merged();
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

    public record RepositoryInfo(String default_branch, Map<String, Boolean> permissions) {}
    public RepositoryInfo repositoryInfo(String owner, String repo, String token) {
        return restClient.get().uri("/repos/{owner}/{repo}", owner, repo)
                .headers(h -> authorize(h, token)).retrieve().body(RepositoryInfo.class);
    }

    public record RunnerStatus(boolean installed, String workflowPath, String message) {}
    private record Content(String sha) {}

    public RunnerStatus runnerStatus(Repo repo) {
        try {
            restClient.get().uri("/repos/{owner}/{repo}/contents/.github/workflows/issue-solver-tests.yml", repo.getGithubOwner(), repo.getGithubRepo())
                    .headers(h -> authorize(h, repo.getGithubToken())).retrieve().toBodilessEntity();
            return new RunnerStatus(true, ".github/workflows/issue-solver-tests.yml", "GitHub Actions test runner is installed");
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) throw ex;
            return new RunnerStatus(false, ".github/workflows/issue-solver-tests.yml", "Install the test workflow before using remote tests");
        }
    }

    public RunnerStatus installRunner(Repo repo) {
        var info = repositoryInfo(repo.getGithubOwner(), repo.getGithubRepo(), repo.getGithubToken());
        if (info.permissions() == null || !Boolean.TRUE.equals(info.permissions().get("push"))) {
            throw new IllegalArgumentException("Your GitHub account needs write access to install the test runner");
        }
        var status = runnerStatus(repo);
        if (status.installed()) return status;
        try {
            String template = new ClassPathResource("github/issue-solver-tests.yml").getContentAsString(StandardCharsets.UTF_8);
            restClient.put().uri("/repos/{owner}/{repo}/contents/.github/workflows/issue-solver-tests.yml", repo.getGithubOwner(), repo.getGithubRepo())
                    .headers(h -> authorize(h, repo.getGithubToken()))
                    .body(Map.of("message", "Add Issue Solver Maven/Gradle test runner", "branch", info.default_branch(),
                            "content", Base64.getEncoder().encodeToString(template.getBytes(StandardCharsets.UTF_8))))
                    .retrieve().toBodilessEntity();
            return new RunnerStatus(true, status.workflowPath(), "Test workflow added to the repository's default branch");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Test runner template is unavailable", ex);
        }
    }
}
