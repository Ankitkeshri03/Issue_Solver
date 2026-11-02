package com.gitissuesolver.backend.agent;

import com.gitissuesolver.backend.auth.*;
import com.gitissuesolver.backend.github.Repo;
import com.gitissuesolver.backend.github.RepoRepository;
import com.gitissuesolver.backend.github.GitHubService;
import com.gitissuesolver.backend.github.GitHubIssueDto;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.gitissuesolver.backend.issue.*;
import com.gitissuesolver.backend.workspace.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0"
})
@ActiveProfiles("test")
class AgentProgressTests {
    @Value("${local.server.port}") int port;
    @Autowired UserRepository users;
    @Autowired RepoRepository repos;
    @Autowired TicketRepository tickets;
    @Autowired AgentStepRepository steps;
    @Autowired AgentProgressPublisher publisher;
    @Autowired JwtService jwt;
    @Autowired HikariDataSource dataSource;
    @Autowired WorkspaceService workspace;
    @Autowired WorkspaceMemberRepository workspaceMembers;
    @MockitoBean GitHubService github;
    private User owner;
    private User developer;
    private Ticket ticket;
    private final List<InputStream> streams = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString();
        owner = users.save(User.builder().username("qa-" + suffix).email("qa-" + suffix + "@example.com")
                .passwordHash("unused-test-hash").role(Role.QA).build());
        developer = users.save(User.builder().username("dev-" + suffix).email("dev-" + suffix + "@example.com")
                .passwordHash("unused-test-hash").role(Role.DEVELOPER).build());
        var space = workspace.create("Test workspace", owner.getId());
        var project = workspace.createProject(space.id(), "Stream project", "", owner.getId());
        workspace.setProjectRole(project.id(), owner.getId(), Role.QA, owner.getId());
        workspaceMembers.save(WorkspaceMember.builder().workspaceId(space.id()).userId(developer.getId()).build());
        workspace.setProjectRole(project.id(), developer.getId(), Role.DEVELOPER, owner.getId());
        var repo = repos.save(Repo.builder().name("test").githubOwner("example").githubRepo("test")
                .cloneUrl("https://github.com/example/test.git").ownerUserId(owner.getId()).projectId(project.id()).build());
        ticket = tickets.save(Ticket.builder().repo(repo).githubIssueNumber(1).title("Stream test")
                .createdBy(owner).assignedTo(developer).status(TicketStatus.ANALYZING).build());
    }

    @AfterEach
    void cleanup() throws Exception {
        publisher.complete(ticket.getId());
        for (var stream : streams) stream.close();
    }

    private URI url(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private String path() { return "/api/tickets/" + ticket.getId(); }
    private HttpRequest request(String path, User user) {
        var builder = HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(4));
        if (user != null) builder.header("Authorization", "Bearer " + jwt.generateToken(user));
        return builder.build();
    }

    private InputStream open() throws Exception {
        // The real browser uses the query token because EventSource cannot set headers.
        var response = client.send(request(path() + "/stream?token=" + jwt.generateToken(developer), null),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        streams.add(response.body());
        return response.body();
    }

    @Test
    void moreOpenStreamsThanDatabaseConnectionsDoNotExhaustPool() throws Exception {
        for (int i = 0; i < 5; i++) open();
        var response = client.send(request(path(), developer), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(developer.getUsername(), "example/test");
        assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void streamOpensImmediatelyAndFinishesWithExplicitCompletion() throws Exception {
        var stream = open();
        publisher.heartbeat();
        publisher.complete(ticket.getId());
        String body = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("event:connected", ":keep-alive", "event:phase-complete");
    }

    @Test
    void streamStillRequiresAuthenticationAndTicketAccess() throws Exception {
        var anonymous = client.send(request(path() + "/stream", null), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isIn(401, 403);
        var unrelated = users.save(User.builder().username("other-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@example.com").passwordHash("unused").role(Role.DEVELOPER).build());
        var denied = client.send(request(path() + "/stream", unrelated), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(404);
    }

    @Test
    void ticketListAndAssignmentWorkWithoutOpenEntityManagerInView() throws Exception {
        ticket.setStatus(TicketStatus.OPEN); tickets.save(ticket);
        org.mockito.Mockito.when(github.fetchIssue("example", "test", 2, null))
                .thenReturn(new GitHubIssueDto(2, "Second issue", "Test description", "open", "https://github.com/example/test/issues/2", null));
        var list = client.send(request("/api/tickets?repoId=" + ticket.getRepo().getId(), owner), HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(developer.getUsername(), "example/test");
        var assign = HttpRequest.newBuilder(url(path() + "/assign")).timeout(Duration.ofSeconds(4))
                .header("Authorization", "Bearer " + jwt.generateToken(owner))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"developerId\":" + developer.getId() + "}")).build();
        // Admin alone cannot assign work; appoint an explicit project manager role.
        workspace.setProjectRole(ticket.getRepo().getProjectId(), owner.getId(), Role.PROJECT_MANAGER, owner.getId());
        assertThat(client.send(assign, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        workspace.setProjectRole(ticket.getRepo().getProjectId(), owner.getId(), Role.QA, owner.getId());
        var create = HttpRequest.newBuilder(url("/api/tickets")).timeout(Duration.ofSeconds(4))
                .header("Authorization", "Bearer " + jwt.generateToken(owner))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"repoId\":" + ticket.getRepo().getId()
                        + ",\"githubIssueNumber\":2,\"title\":\"Second issue\",\"description\":\"Test description\"}")).build();
        assertThat(client.send(create, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test
    void restartRecoveryPreservesPlanAndMakesAnInterruptedJobRetryable() {
        ticket.setPlan("Saved plan");
        tickets.save(ticket);
        var recovery = new InterruptedJobRecovery(tickets, steps);
        recovery.run(null);
        var updated = tickets.findById(ticket.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TicketStatus.FAILED);
        assertThat(updated.getPlan()).isEqualTo("Saved plan");
        assertThat(steps.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).anySatisfy(step -> {
            assertThat(step.getStepName()).isEqualTo("interrupted");
            assertThat(step.getStatus()).isEqualTo(AgentStepStatus.FAILED);
        });
        assertThat(tickets.transition(ticket.getId(), List.of(TicketStatus.FAILED), TicketStatus.ANALYZING)).isEqualTo(1);
    }
}
