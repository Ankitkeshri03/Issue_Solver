package com.gitissuesolver.backend.github;

import com.gitissuesolver.backend.auth.*;
import com.gitissuesolver.backend.issue.*;
import com.gitissuesolver.backend.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RepoIssueTests {
    @Value("${local.server.port}") int port;
    @Autowired UserRepository users;
    @Autowired RepoRepository repos;
    @Autowired TicketRepository tickets;
    @Autowired JwtService jwt;
    @Autowired WorkspaceService workspace;
    @Autowired WorkspaceMemberRepository workspaceMembers;
    @MockitoBean GitHubService github;
    private User owner, developer;
    private Repo repo;
    private Ticket original, importedPr;
    private final HttpClient client = HttpClient.newHttpClient();

    private GitHubIssueDto issue(String state) {
        return new GitHubIssueDto(1, "Original bug", "Reported bug", state, "https://github.com/example/test/issues/1", null);
    }
    private GitHubIssueDto pr(String state) {
        return new GitHubIssueDto(2, "Fix issue #1", "Proposed fix", state, "https://github.com/example/test/pull/2", Map.of());
    }

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString();
        owner = users.save(User.builder().username("owner-" + suffix).email(suffix + "@example.com")
                .passwordHash("unused").role(Role.QA).build());
        developer = users.save(User.builder().username("dev-" + suffix).email("dev-" + suffix + "@example.com")
                .passwordHash("unused").role(Role.DEVELOPER).build());
        var space = workspace.create("Issue workspace", owner.getId());
        var project = workspace.createProject(space.id(), "Issue project", "", owner.getId());
        workspace.setProjectRole(project.id(), owner.getId(), Role.QA, owner.getId());
        workspaceMembers.save(WorkspaceMember.builder().workspaceId(space.id()).userId(developer.getId()).build());
        workspace.setProjectRole(project.id(), developer.getId(), Role.DEVELOPER, owner.getId());
        repo = repos.save(Repo.builder().name("test").githubOwner("example").githubRepo("test")
                .cloneUrl("https://github.com/example/test.git").githubToken("unused-test-token").ownerUserId(owner.getId()).projectId(project.id()).build());
        original = tickets.save(Ticket.builder().repo(repo).githubIssueNumber(1).title("Original bug")
                .createdBy(owner).assignedTo(developer).plan("Preserved plan")
                .prUrl("https://github.com/example/test/pull/2").status(TicketStatus.PR_CREATED).build());
        importedPr = tickets.save(Ticket.builder().repo(repo).githubIssueNumber(2).title("Fix issue #1")
                .createdBy(owner).assignedTo(developer).status(TicketStatus.OPEN).build());
        when(github.fetchRepositoryItems("example", "test", "unused-test-token")).thenReturn(List.of(issue("open"), pr("open")));
        when(github.fetchIssue("example", "test", 2, "unused-test-token")).thenReturn(pr("open"));
    }

    private HttpResponse<String> get(String path, User user) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + jwt.generateToken(user)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private void sync() throws Exception {
        var response = get("/api/repos/" + repo.getId() + "/issues", owner);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Original bug").doesNotContain("Fix issue #1");
    }

    @Test
    void repairsImportedPullRequestAndExcludesItForOwnerAndDeveloperWithoutDeletingHistory() throws Exception {
        sync();
        for (var user : List.of(owner, developer)) {
            var response = get("/api/tickets?repoId=" + repo.getId(), user);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("Original bug").doesNotContain("Fix issue #1");
            assertThat(get("/api/tickets/" + importedPr.getId(), user).statusCode()).isEqualTo(404);
        }
        assertThat(tickets.findById(importedPr.getId()).orElseThrow().isGithubPullRequest()).isTrue();
        assertThat(tickets.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(TicketStatus.PR_CREATED);
    }

    @Test
    void cannotCreateTicketFromPullRequestEvenWhenPostingDirectly() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/tickets"))
                .header("Authorization", "Bearer " + jwt.generateToken(owner)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"repoId\":" + repo.getId() + ",\"githubIssueNumber\":2,\"title\":\"Pretend issue\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Pull requests are proposed fixes, not issues");
        assertThat(tickets.findByRepoId(repo.getId())).hasSize(2);
    }

    @Test
    void closedIssueMovesToSolvedAndReopeningItMakesItActiveAgain() throws Exception {
        when(github.fetchRepositoryItems("example", "test", "unused-test-token")).thenReturn(List.of(issue("closed"), pr("open")));
        sync();
        var closed = tickets.findById(original.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(TicketStatus.AWAITING_QA);
        assertThat(closed.getPlan()).isEqualTo("Preserved plan");
        when(github.fetchRepositoryItems("example", "test", "unused-test-token")).thenReturn(List.of(issue("open"), pr("open")));
        sync();
        assertThat(tickets.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(TicketStatus.OPEN);
    }

    @Test
    void closedUnmergedPullRequestDoesNotSolveIssueButMergedPullRequestDoes() throws Exception {
        when(github.fetchRepositoryItems("example", "test", "unused-test-token")).thenReturn(List.of(issue("open"), pr("closed")));
        when(github.isPullRequestMerged(any(), eq(2))).thenReturn(false);
        sync();
        assertThat(tickets.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(TicketStatus.PR_CREATED);
        when(github.isPullRequestMerged(any(), eq(2))).thenReturn(true);
        sync();
        assertThat(tickets.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(TicketStatus.AWAITING_QA);
    }

    @Test
    void syncCannotOverwriteAnImplementationThatStartedAfterItsSnapshot() {
        assertThat(tickets.transition(original.getId(), List.of(TicketStatus.PR_CREATED), TicketStatus.IMPLEMENTING)).isEqualTo(1);
        assertThat(tickets.syncGitHubState(original.getId(), TicketStatus.PR_CREATED, TicketStatus.RESOLVED, false, true)).isZero();
        assertThat(tickets.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(TicketStatus.IMPLEMENTING);
    }
}
