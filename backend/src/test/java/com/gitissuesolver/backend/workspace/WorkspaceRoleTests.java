package com.gitissuesolver.backend.workspace;

import com.gitissuesolver.backend.agent.AgentService;
import com.gitissuesolver.backend.auth.*;
import com.gitissuesolver.backend.github.*;
import com.gitissuesolver.backend.issue.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="app.provisioning.key=test-operator-key-long-enough-for-integration-tests")
@ActiveProfiles("test")
class WorkspaceRoleTests {
    @Value("${local.server.port}") int port;
    @Autowired UserRepository users;
    @Autowired WorkspaceService service;
    @Autowired WorkspaceMemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired WorkspaceInvitationRepository invitations;
    @Autowired WorkspaceAccess access;
    @Autowired RepoRepository repos;
    @Autowired TicketRepository tickets;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectAccess projectAccess;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CompanyProvisioningService provisioning;
    @Autowired WorkspaceRepository companies;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @MockitoBean GitHubService github;
    @MockitoBean AgentService agent;
    @MockitoBean InvitationMailService mail;
    private User admin, manager, qa, developer, otherDeveloper, outsider;
    private Long workspaceId, projectId;
    private Repo repo;
    private Ticket ticket;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private User user(String prefix) {
        String name = prefix + UUID.randomUUID();
        return users.save(User.builder().username(name).email(name + "@example.com").passwordHash("unused")
                .role(Role.DEVELOPER).build());
    }
    private void member(User user, Role role) {
        members.save(WorkspaceMember.builder().workspaceId(workspaceId).userId(user.getId()).build());
        service.setProjectRole(projectId, user.getId(), role, admin.getId());
    }
    @BeforeEach
    void setup() {
        admin = user("admin"); manager = user("pm"); qa = user("qa"); developer = user("dev");
        otherDeveloper = user("otherdev"); outsider = user("outsider");
        workspaceId = service.create("Company A", admin.getId()).id();
        projectId = service.createProject(workspaceId, "Project A", "Company project", admin.getId()).id();
        member(manager, Role.PROJECT_MANAGER); member(qa, Role.QA); member(developer, Role.DEVELOPER); member(otherDeveloper, Role.DEVELOPER);
        repo = repos.save(Repo.builder().name("test").githubOwner("example").githubRepo("test")
                .ownerUserId(admin.getId()).projectId(projectId).githubToken("private-test-token")
                .cloneUrl("https://github.com/example/test.git").build());
        ticket = tickets.save(Ticket.builder().repo(repo).title("Bug report").githubIssueNumber(1)
                .createdBy(qa).assignedTo(developer).plan("Existing plan").status(TicketStatus.OPEN).build());
        when(github.fetchIssue("example", "test", 2, "private-test-token"))
                .thenReturn(new GitHubIssueDto(2, "Canonical GitHub title", "Source description", "open", "https://github.com/example/test/issues/2", null));
        when(github.fetchRepositoryItems("example", "test", "private-test-token"))
                .thenReturn(List.of(new GitHubIssueDto(1, "Bug report", "Description", "closed", "https://github.com/example/test/issues/1", null)));
    }
    private HttpResponse<String> send(User user, String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(7));
        if (user != null) builder.header("Authorization", "Bearer " + jwt.generateToken(user));
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String path() { return "/api/tickets/" + ticket.getId(); }
    private String token(WorkspaceService.InvitationCreated invitation) { return invitation.invitationUrl().split("token=")[1]; }
    private Map<String,Object> activation(String token, String email, String username) {
        return Map.of("token", token, "email", email, "username", username, "password", "ChosenPassword123!", "confirmPassword", "ChosenPassword123!");
    }
    private void status(HttpResponse<String> response, int expected) { assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(expected); }

    @Test
    void operatorAuthorizationAndInvitationOnlySignupCannotBeBypassedByAnAdminJwt() throws Exception {
        var body = Map.of("companyName", "Company", "adminEmail", "admin@example.com", "requestId", UUID.randomUUID());
        status(send(null, "POST", "/api/operator/companies", body), 401);
        status(send(admin, "POST", "/api/operator/companies", body), 401);
        var disabled = new CompanyProvisioningController(provisioning, mail, "");
        assertThatThrownBy(() -> disabled.provision("anything", new CompanyProvisioningController.CompanyRequest("Company", "admin@example.com", UUID.randomUUID(), false)))
                .isInstanceOf(ResponseStatusException.class).extracting(ex -> ((ResponseStatusException)ex).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void operatorProvisionsCompanyAndFirstAdminSetsOwnPasswordThenUsesCommonLogin() throws Exception {
        String email = UUID.randomUUID() + "@example.com", username = "activated" + UUID.randomUUID();
        var requestId = UUID.randomUUID();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/operator/companies"))
                .header("Content-Type", "application/json").header("X-Provisioning-Key", "test-operator-key-long-enough-for-integration-tests")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("companyName", "New company", "adminEmail", email, "requestId", requestId, "sendEmail", false)))).build();
        var provision = client.send(request, HttpResponse.BodyHandlers.ofString()); status(provision, 200);
        assertThat(provision.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var result = json.readTree(provision.body()); Long companyId = result.get("companyId").asLong();
        String inviteToken = result.get("invitationUrl").asText().split("token=")[1];
        assertThat(companies.findById(companyId).orElseThrow().getOwnerUserId()).isNull();
        assertThat(service.list(outsider.getId())).isEmpty();
        assertThatThrownBy(() -> provisioning.provision("Duplicate", email, requestId)).isInstanceOf(ResponseStatusException.class);
        var preview = send(null,"POST","/api/auth/invitations/preview",Map.of("token",inviteToken)); status(preview,200);
        assertThat(json.readTree(preview.body()).get("role").asText()).isEqualTo("WORKSPACE_ADMIN");
        var activated = send(null,"POST","/api/auth/invitations/activate",activation(inviteToken,email,username)); status(activated,200);
        assertThat(activated.body()).doesNotContain("token");
        var user = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(passwords.matches("ChosenPassword123!",user.getPasswordHash())).isTrue();
        assertThat(companies.findById(companyId).orElseThrow().getOwnerUserId()).isEqualTo(user.getId());
        assertThat(service.get(companyId,user.getId()).admin()).isTrue();
        status(send(null,"POST","/api/auth/login",Map.of("username",email,"password","ChosenPassword123!")),200);
        status(send(null,"POST","/api/auth/invitations/activate",activation(inviteToken,email,username)),400);
        assertThatThrownBy(() -> provisioning.reissue(companyId,email)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void existingAccountAcceptsBootstrapWithoutPasswordResetOrNewAccount() throws Exception {
        var bootstrap = provisioning.provision("Existing user's company", outsider.getEmail(), UUID.randomUUID());
        String inviteToken = token(bootstrap.invitation()), hash = outsider.getPasswordHash();
        var preview = send(null,"POST","/api/auth/invitations/preview",Map.of("token",inviteToken)); status(preview,200);
        assertThat(json.readTree(preview.body()).get("accountExists").asBoolean()).isTrue();
        status(send(null,"POST","/api/auth/invitations/activate",activation(inviteToken,outsider.getEmail(),"overwrite")),409);
        assertThat(users.findById(outsider.getId()).orElseThrow().getPasswordHash()).isEqualTo(hash);
        status(send(admin,"POST","/api/invitations/accept",Map.of("token",inviteToken)),403);
        status(send(outsider,"POST","/api/invitations/accept",Map.of("token",inviteToken)),200);
        assertThat(service.get(bootstrap.companyId(),outsider.getId()).admin()).isTrue();
        assertThat(users.findById(outsider.getId()).orElseThrow().getPasswordHash()).isEqualTo(hash);
    }

    @Test
    void activationRejectsWrongEmailExpiredAndReplacedLinksWithoutCreatingUsers() throws Exception {
        String email = UUID.randomUUID()+"@example.com";
        var bootstrap=provisioning.provision("Activation validation",email,UUID.randomUUID());
        String original=token(bootstrap.invitation());
        status(send(null,"POST","/api/auth/invitations/activate",activation(original,"different@example.com","wrong-email")),403);
        var replacement=provisioning.reissue(bootstrap.companyId(),email);
        status(send(null,"POST","/api/auth/invitations/activate",activation(original,email,"old-link")),400);
        jdbc.update("UPDATE workspace_invitations SET expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),replacement.invitation().id());
        status(send(null,"POST","/api/auth/invitations/activate",activation(token(replacement.invitation()),email,"expired-link")),400);
        assertThat(users.existsByEmailIgnoreCase(email)).isFalse();
        assertThat(companies.findById(bootstrap.companyId()).orElseThrow().getOwnerUserId()).isNull();
    }

    @Test
    void managersManageContributorsOnlyInTheirProjectsAndCannotPromoteOrDemoteManagers() throws Exception {
        var other = service.createProject(workspaceId,"Other project","",admin.getId());
        service.setProjectRole(other.id(),developer.getId(),Role.DEVELOPER,admin.getId());
        String team = "/api/projects/"+projectId+"/members/";
        status(send(manager,"PUT",team+developer.getId(),Map.of("role","QA")),204);
        status(send(manager,"PUT",team+developer.getId(),Map.of("role","PROJECT_MANAGER")),403);
        status(send(manager,"DELETE",team+manager.getId(),null),403);
        status(send(manager,"PUT",team+admin.getId(),Map.of("role","QA")),403);
        status(send(manager,"PUT","/api/projects/"+other.id()+"/members/"+developer.getId(),Map.of("role","QA")),404);
        for (String role : List.of("WORKSPACE_ADMIN","PROJECT_MANAGER")) {
            var body = new HashMap<String,Object>(Map.of("email",outsider.getEmail(),"role",role));
            if (!role.equals("WORKSPACE_ADMIN")) body.put("projectId",projectId);
            status(send(manager,"POST","/api/workspaces/"+workspaceId+"/invitations",body),403);
        }
        status(send(manager,"POST","/api/workspaces/"+workspaceId+"/invitations",Map.of("email",outsider.getEmail(),"role","QA","projectId",other.id())),404);
        var candidates=send(manager,"GET","/api/projects/"+projectId+"/available-members",null); status(candidates,200);
        assertThat(candidates.body()).doesNotContain("email","password","token",admin.getUsername(),manager.getUsername());
        status(send(qa,"GET","/api/projects/"+projectId+"/available-members",null),403);
        status(send(manager,"GET","/api/workspaces/"+workspaceId+"/members",null),403);
        status(send(manager,"DELETE",team+developer.getId(),null),204);
        assertThat(projectMembers.findRole(other.id(),developer.getId())).contains(Role.DEVELOPER);
    }

    @Test
    void managerInvitationActivatesOnlyInvitedRoleAndStopsWorkingAfterManagerLosesAuthority() throws Exception {
        String email=UUID.randomUUID()+"@example.com";
        var invitation=service.invite(workspaceId,projectId,email,Role.QA,manager.getId());
        var body=new HashMap<>(activation(token(invitation),email,"qa-"+UUID.randomUUID())); body.put("role","WORKSPACE_ADMIN");
        status(send(null,"POST","/api/auth/invitations/activate",body),200);
        var activated=users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(projectMembers.findRole(projectId,activated.getId())).contains(Role.QA);
        assertThat(access.isAdmin(workspaceId,activated.getId())).isFalse();
        var pending=service.invite(workspaceId,projectId,UUID.randomUUID()+"@example.com",Role.DEVELOPER,manager.getId());
        service.setProjectRole(projectId,manager.getId(),Role.DEVELOPER,admin.getId());
        status(send(null,"POST","/api/auth/invitations/preview",Map.of("token",token(pending))),400);
        status(send(null,"POST","/api/auth/invitations/activate",activation(token(pending),pending.email(),"no-authority")),400);
    }

    @Test
    void managerCanUnassignButAdminAndContributorsCannotAndRunningJobsAreProtected() throws Exception {
        for(var actor:List.of(admin,qa,developer)) status(send(actor,"DELETE",path()+"/assignment",null),403);
        ticket.setStatus(TicketStatus.IMPLEMENTING); tickets.save(ticket);
        status(send(manager,"DELETE",path()+"/assignment",null),409);
        ticket.setStatus(TicketStatus.OPEN); tickets.save(ticket);
        status(send(manager,"DELETE",path()+"/assignment",null),200);
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getAssignedTo()).isNull();
        assertThat(send(manager,"GET",path()+"/activity",null).body()).contains("UNASSIGNED");
    }

    @Test
    void simultaneousActivationConsumesLinkOnlyOnce() throws Exception {
        String email=UUID.randomUUID()+"@example.com";
        var bootstrap=provisioning.provision("Concurrent activation",email,UUID.randomUUID());
        var body=activation(token(bootstrap.invitation()),email,"concurrent"+UUID.randomUUID());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=pool.invokeAll(List.<Callable<Integer>>of(
                    ()->send(null,"POST","/api/auth/invitations/activate",body).statusCode(),
                    ()->send(null,"POST","/api/auth/invitations/activate",body).statusCode()));
            assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(200,400);
        }
        assertThat(service.list(users.findByEmailIgnoreCase(email).orElseThrow().getId())).hasSize(1);
    }

    @Test
    void publicRegistrationCannotClaimAdminOrManagerPrivileges() throws Exception {
        for (String role : List.of("WORKSPACE_ADMIN", "PROJECT_MANAGER", "QA", "DEVELOPER")) {
            var response = send(null, "POST", "/api/auth/register", Map.of("username", "signup" + UUID.randomUUID(),
                    "email", UUID.randomUUID() + "@example.com", "password", "Password123!", "role", role));
            status(response, 403); assertThat(response.body()).contains("invitation-only");
        }
    }

    @Test
    void publicWorkspaceCreationCannotBypassCompanyProvisioning() throws Exception {
        var response = send(outsider, "POST", "/api/workspaces", Map.of("name", "Independent workspace"));
        status(response, 403);
        assertThat(service.list(outsider.getId())).isEmpty();
        status(send(outsider, "GET", "/api/workspaces/" + workspaceId, null), 404);
        status(send(outsider, "GET", "/api/projects/" + projectId, null), 404);
    }

    @Test
    void crossWorkspaceAccountsCannotReadReposTicketsTeamsOrActivity() throws Exception {
        service.create("Company B", outsider.getId());
        for (String path : List.of("/api/projects/" + projectId, path(), path() + "/activity",
                "/api/repos/" + repo.getId() + "/issues", "/api/workspaces/" + workspaceId + "/members"))
            status(send(outsider, "GET", path, null), 404);
        assertThat(send(outsider, "GET", "/api/repos", null).body()).isEqualTo("[]");
        assertThat(send(outsider, "GET", "/api/tickets", null).body()).isEqualTo("[]");
    }

    @Test
    void onlyWorkspaceAdminsCreateProjectsInvitePeopleAndManageGithub() throws Exception {
        for (var actor : List.of(manager, qa, developer)) {
            status(send(actor, "POST", "/api/workspaces/" + workspaceId + "/projects", Map.of("name", "Unauthorized")), 403);
            status(send(actor, "POST", "/api/workspaces/" + workspaceId + "/invitations",
                    Map.of("email", outsider.getEmail(), "role", "QA", "projectId", projectId)), actor == manager ? 200 : 403);
            status(send(actor, "POST", "/api/repos/connect", Map.of("owner", "example", "repo", "test", "projectId", projectId)), 403);
        }
        status(send(admin, "POST", "/api/workspaces/" + workspaceId + "/projects", Map.of("name", "Authorized")), 200);
    }

    @Test
    void managerAssignsProjectDevelopersAndSetsPriorityButQaCannotAssign() throws Exception {
        status(send(manager, "POST", path() + "/assign", Map.of("developerId", otherDeveloper.getId())), 200);
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getAssignedTo().getId()).isEqualTo(otherDeveloper.getId());
        status(send(manager, "PUT", path() + "/priority", Map.of("priority", "CRITICAL")), 200);
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getPriority()).isEqualTo(Priority.CRITICAL);
        for (var actor : List.of(admin, qa, developer)) {
            status(send(actor, "POST", path() + "/assign", Map.of("developerId", developer.getId())), 403);
            status(send(actor, "PUT", path() + "/priority", Map.of("priority", "LOW")), 403);
        }
        status(send(manager, "POST", path() + "/assign", Map.of("developerId", qa.getId())), 403);
        status(send(manager, "POST", path() + "/assign", Map.of("developerId", outsider.getId())), 404);
    }

    @Test
    void onlyQaImportsReportsAndReportDetailsAreVisibleToAssignedDeveloper() throws Exception {
        var body = Map.of("repoId", repo.getId(), "githubIssueNumber", 2, "title", "Untrusted client title",
                "reproductionSteps", "Upload a broken invoice", "expectedBehavior", "Show validation error",
                "actualBehavior", "Server error", "severity", "HIGH");
        status(send(manager, "POST", "/api/tickets", body), 403);
        status(send(developer, "POST", "/api/tickets", body), 403);
        var response = send(qa, "POST", "/api/tickets", body); status(response, 200);
        assertThat(response.body()).contains("Canonical GitHub title", "Upload a broken invoice", "HIGH").doesNotContain("Untrusted client title");
        long id = json.readTree(response.body()).get("id").asLong();
        assertThat(send(developer, "GET", "/api/tickets/" + id, null).body()).contains("Show validation error");
        status(send(qa, "POST", "/api/tickets", body), 409);
    }

    @Test
    void onlyAssignedDeveloperCanAnalyzeOrApproveEvenIfOthersCanRead() throws Exception {
        for (var actor : List.of(admin, manager, qa, otherDeveloper)) {
            status(send(actor, "GET", path(), null), 200);
            status(send(actor, "POST", path() + "/analyze", null), 403);
            status(send(actor, "POST", path() + "/approve", null), 403);
        }
        status(send(developer, "POST", path() + "/analyze", null), 202);
        verify(agent).analyzeAsync(ticket.getId());
        ticket.setStatus(TicketStatus.PLAN_READY); tickets.save(ticket);
        status(send(developer, "POST", path() + "/approve", null), 202);
        verify(agent).implementAsync(ticket.getId());
    }

    @Test
    void mergedOrClosedIssueRequiresQaVerificationAndReopenedBugStaysActive() throws Exception {
        ticket.setStatus(TicketStatus.PR_CREATED); tickets.save(ticket);
        status(send(manager, "GET", "/api/repos/" + repo.getId() + "/issues", null), 200);
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getStatus()).isEqualTo(TicketStatus.AWAITING_QA);
        for (var actor : List.of(admin, manager, developer))
            status(send(actor, "POST", path() + "/verify", Map.of("notes", "Not QA")), 403);
        status(send(qa, "POST", path() + "/verify", Map.of("notes", "")), 400);
        status(send(qa, "POST", path() + "/verify", Map.of("notes", "Retested the original issue and regression scenarios")), 200);
        var verified = tickets.findById(ticket.getId()).orElseThrow();
        assertThat(verified.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(verified.getQaVerifiedBy()).isEqualTo(qa.getId());
        assertThat(verified.getQaVerifiedAt()).isNotNull();
        status(send(qa, "POST", path() + "/reopen", Map.of("notes", "The error still occurs for an empty invoice")), 200);
        status(send(qa, "GET", "/api/repos/" + repo.getId() + "/issues", null), 200);
        var reopened = tickets.findById(ticket.getId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(reopened.getQaVerifiedAt()).isNull();
        assertThat(reopened.getPlan()).isEqualTo("Existing plan");
        assertThat(send(manager, "GET", path() + "/activity", null).body()).contains("QA_VERIFIED", "QA_REOPENED", qa.getUsername());
    }

    @Test
    void projectRolesCanDifferForTheSamePerson() {
        var second = service.createProject(workspaceId, "Second project", "", admin.getId());
        service.setProjectRole(second.id(), qa.getId(), Role.PROJECT_MANAGER, admin.getId());
        assertThat(access.projectRole(projectId, qa.getId())).isEqualTo(Role.QA);
        assertThat(access.projectRole(second.id(), qa.getId())).isEqualTo(Role.PROJECT_MANAGER);
    }

    @Test
    void concurrentInvitationAcceptanceGrantsMembershipOnlyOnce() throws Exception {
        var invitation = service.invite(workspaceId, projectId, outsider.getEmail(), Role.QA, admin.getId());
        String token = invitation.invitationUrl().split("#token=")[1];
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> accept = () -> {
                start.await();
                try { service.accept(token, outsider.getId()); return true; }
                catch (ResponseStatusException ex) { assertThat(ex.getStatusCode().value()).isEqualTo(400); return false; }
            };
            var first = pool.submit(accept); var second = pool.submit(accept); start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void roleRevocationCannotRaceAnAgentStartAndRemoveItsDeveloper() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                projectAccess.prepareAgentRun(ticket.getId(), developer.getId(), false);
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock timed out"); }
                catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return null;
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var revoke = pool.submit(() -> service.setProjectRole(projectId, developer.getId(), Role.QA, admin.getId()));
            try { assertThatThrownBy(() -> revoke.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            start.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> revoke.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ResponseStatusException.class);
            assertThat(access.projectRole(projectId, developer.getId())).isEqualTo(Role.DEVELOPER);
        } finally { release.countDown(); }
    }

    @Test
    void invitationsAreEmailBoundHashedSingleUseAndCannotTargetAnotherWorkspace() throws Exception {
        var otherSpace = service.create("Other company", outsider.getId());
        var otherProject = service.createProject(otherSpace.id(), "Other project", "", outsider.getId());
        status(send(admin, "POST", "/api/workspaces/" + workspaceId + "/invitations", Map.of("email", outsider.getEmail(), "role", "QA", "projectId", otherProject.id())), 400);
        var created = service.invite(workspaceId, projectId, outsider.getEmail(), Role.PROJECT_MANAGER, admin.getId());
        String token = created.invitationUrl().split("#token=")[1];
        var stored = invitations.findById(created.id()).orElseThrow();
        assertThat(stored.getTokenHash()).hasSize(64).doesNotContain(token);
        status(send(qa, "POST", "/api/invitations/accept", Map.of("token", token)), 403);
        status(send(outsider, "POST", "/api/invitations/accept", Map.of("token", token)), 200);
        assertThat(access.projectRole(projectId, outsider.getId())).isEqualTo(Role.PROJECT_MANAGER);
        assertThat(access.isAdmin(workspaceId, outsider.getId())).isFalse();
        status(send(outsider, "POST", "/api/invitations/accept", Map.of("token", token)), 400);
    }

    @Test
    void expiredAndRevokedInvitationsDoNotGrantAccessAndEmailSendingIsExplicit() throws Exception {
        var expired = service.invite(workspaceId, projectId, outsider.getEmail(), Role.QA, admin.getId());
        var stored = invitations.findById(expired.id()).orElseThrow(); stored.setExpiresAt(Instant.now().minusSeconds(1)); invitations.save(stored);
        status(send(outsider, "POST", "/api/invitations/accept", Map.of("token", expired.invitationUrl().split("#token=")[1])), 400);
        var revoked = service.invite(workspaceId, projectId, outsider.getEmail(), Role.QA, admin.getId());
        service.revokeInvitation(workspaceId, revoked.id(), admin.getId());
        status(send(outsider, "POST", "/api/invitations/accept", Map.of("token", revoked.invitationUrl().split("#token=")[1])), 400);
        var response = send(admin, "POST", "/api/workspaces/" + workspaceId + "/invitations", Map.of("email", outsider.getEmail(), "role", "QA", "projectId", projectId));
        status(response, 200); assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-store");
        verifyNoInteractions(mail);
        status(send(admin, "POST", "/api/workspaces/" + workspaceId + "/invitations", Map.of("email", outsider.getEmail(), "role", "QA", "projectId", projectId, "sendEmail", true)), 200);
        verify(mail).send(any());
    }

    @Test
    void removedMembershipRevokesAccessAndUnassignsWorkWithoutDeletingTheAccount() throws Exception {
        status(send(admin, "DELETE", "/api/workspaces/" + workspaceId + "/members/" + developer.getId(), null), 204);
        status(send(developer, "GET", path(), null), 404);
        status(send(developer, "POST", path() + "/analyze", null), 404);
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getAssignedTo()).isNull();
        assertThat(users.findById(developer.getId())).isPresent();
        status(send(admin, "PUT", "/api/workspaces/" + workspaceId + "/members/" + admin.getId(), Map.of("admin", false)), 400);
        status(send(admin, "DELETE", "/api/workspaces/" + workspaceId + "/members/" + admin.getId(), null), 400);
    }

    @Test
    void activeDeveloperJobsProtectAgainstRoleChangesAndReassignment() throws Exception {
        ticket.setStatus(TicketStatus.IMPLEMENTING); tickets.save(ticket);
        status(send(admin, "PUT", "/api/projects/" + projectId + "/members/" + developer.getId(), Map.of("role", "QA")), 409);
        status(send(admin, "DELETE", "/api/workspaces/" + workspaceId + "/members/" + developer.getId(), null), 409);
        status(send(manager, "POST", path() + "/assign", Map.of("developerId", otherDeveloper.getId())), 409);
    }

    @Test
    void repositoryResponsesNeverExposeSharedGithubTokensAndUserDirectoryIsProjectScoped() throws Exception {
        for (var actor : List.of(admin, manager, qa, developer)) {
            var response = send(actor, "GET", "/api/repos", null); status(response, 200);
            assertThat(response.body()).contains("projectId").doesNotContain("private-test-token", "githubToken");
        }
        var directory = send(manager, "GET", "/api/users?projectId=" + projectId + "&role=DEVELOPER", null);
        status(directory, 200); assertThat(directory.body()).contains(developer.getUsername()).doesNotContain(outsider.getUsername(), qa.getUsername());
        status(send(outsider, "GET", "/api/users?projectId=" + projectId, null), 404);
    }

    @Test
    void legacyMigrationPreservesOwnershipPlansAndMembersAndDoesNotRegrantRemovedRoles() throws Exception {
        var legacyOwner = user("legacyqa"); legacyOwner.setRole(Role.QA); users.save(legacyOwner);
        var legacyDev = user("legacydev");
        var legacyRepo = repos.save(Repo.builder().name("Legacy project").githubOwner("legacy").githubRepo("test")
                .ownerUserId(legacyOwner.getId()).cloneUrl("https://github.com/legacy/test.git").githubToken("legacy-token").build());
        var legacyTicket = tickets.save(Ticket.builder().repo(legacyRepo).githubIssueNumber(8).title("Legacy issue")
                .createdBy(legacyOwner).assignedTo(legacyDev).status(TicketStatus.RESOLVED).plan("Preserve this plan")
                .prUrl("https://github.com/legacy/test/pull/9").build());
        String migration = Files.readString(Path.of("../database/migrations/004_workspace_roles.sql"));
        applyMigration(migration);
        var migratedRepo = repos.findById(legacyRepo.getId()).orElseThrow();
        assertThat(migratedRepo.getGithubToken()).isEqualTo("legacy-token");
        Long legacyProject = migratedRepo.getProjectId();
        assertThat(access.projectRole(legacyProject, legacyOwner.getId())).isEqualTo(Role.QA);
        assertThat(service.projectView(legacyProject, legacyOwner.getId()).workspaceAdmin()).isTrue();
        assertThat(access.projectRole(legacyProject, legacyDev.getId())).isEqualTo(Role.DEVELOPER);
        var migratedTicket = tickets.findById(legacyTicket.getId()).orElseThrow();
        assertThat(migratedTicket.getPlan()).isEqualTo("Preserve this plan");
        assertThat(migratedTicket.getStatus()).isEqualTo(TicketStatus.AWAITING_QA);
        service.removeProjectMember(legacyProject, legacyDev.getId(), legacyOwner.getId());
        applyMigration(migration);
        assertThat(projectMembers.findByProjectIdAndUserId(legacyProject, legacyDev.getId())).isEmpty();
        String onboardingMigration = Files.readString(Path.of("../database/migrations/005_company_onboarding.sql"));
        applyMigration(onboardingMigration); applyMigration(onboardingMigration);
        assertThat(service.projectView(legacyProject, legacyOwner.getId()).workspaceAdmin()).isTrue();
        assertThat(projectMembers.findByProjectIdAndUserId(legacyProject, legacyDev.getId())).isEmpty();
        assertThat(users.findById(legacyOwner.getId()).orElseThrow().getPasswordHash()).isEqualTo(legacyOwner.getPasswordHash());
        var company = provisioning.provision("After migration", UUID.randomUUID()+"@example.com", UUID.randomUUID());
        assertThat(companies.findById(company.companyId()).orElseThrow().isProvisioningPending()).isTrue();
    }
    private void applyMigration(String sql) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                try { statement.execute(sql); }
                catch (java.sql.SQLException ex) { statement.execute("ROLLBACK"); throw ex; }
            }
            return null;
        });
    }
}
