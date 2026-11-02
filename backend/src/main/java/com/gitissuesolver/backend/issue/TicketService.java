package com.gitissuesolver.backend.issue;

import com.gitissuesolver.backend.auth.User;
import com.gitissuesolver.backend.auth.UserRepository;
import com.gitissuesolver.backend.github.Repo;
import com.gitissuesolver.backend.github.RepoRepository;
import com.gitissuesolver.backend.github.ProjectAccess;
import com.gitissuesolver.backend.github.GitHubService;
import com.gitissuesolver.backend.issue.dto.CreateTicketRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.gitissuesolver.backend.auth.Role;
import com.gitissuesolver.backend.workspace.WorkspaceAccess;
import java.time.Instant;

import java.util.List;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final ProjectAccess access;
    private final GitHubService github;
    private final WorkspaceAccess workspace;
    private final TicketActivityRepository activities;

    public TicketService(TicketRepository ticketRepository, RepoRepository repoRepository, UserRepository userRepository, ProjectAccess access, GitHubService github, WorkspaceAccess workspace, TicketActivityRepository activities) {
        this.ticketRepository = ticketRepository;
        this.repoRepository = repoRepository;
        this.userRepository = userRepository;
        this.access = access;
        this.github = github;
        this.workspace = workspace;
        this.activities = activities;
    }

    public Ticket create(CreateTicketRequest request, Long createdByUserId) {
        Repo repo = access.repo(request.repoId(), createdByUserId);
        workspace.requireRole(repo.getProjectId(), createdByUserId, Role.QA);
        var issue = github.fetchIssue(repo.getGithubOwner(), repo.getGithubRepo(), request.githubIssueNumber(), repo.getGithubToken());
        if (issue == null || issue.isPullRequest())
            throw new IllegalArgumentException("Pull requests are proposed fixes, not issues. Select a GitHub issue instead.");
        if (ticketRepository.findByRepoIdAndGithubIssueNumber(repo.getId(), request.githubIssueNumber()).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This issue already has a ticket");
        User createdBy = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + createdByUserId));

        Ticket ticket = Ticket.builder()
                .repo(repo)
                .githubIssueNumber(request.githubIssueNumber())
                .title(issue.title())
                .description(issue.body())
                .reproductionSteps(request.reproductionSteps()).expectedBehavior(request.expectedBehavior())
                .actualBehavior(request.actualBehavior()).severity(request.severity() == null ? Severity.MEDIUM : request.severity())
                .githubIssueClosed("closed".equals(issue.state()))
                .status("closed".equals(issue.state()) ? TicketStatus.AWAITING_QA : TicketStatus.OPEN)
                .createdBy(createdBy)
                .build();
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket assign(Long ticketId, Long developerId, Long actor) {
        Ticket ticket = locked(ticketId, actor);
        workspace.manager(ticket.getRepo().getProjectId(), actor);
        requireIdle(ticket);
        workspace.requireRole(ticket.getRepo().getProjectId(), developerId, Role.DEVELOPER);
        User developer = userRepository.findById(developerId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + developerId));
        ticket.setAssignedTo(developer);
        ticketRepository.save(ticket);
        activity(ticketId, actor, "ASSIGNED", "Assigned to " + developer.getUsername());
        // Merge can return lazy association proxies; reload the response graph after commit.
        return getOrThrow(ticketId);
    }

    @Transactional
    public Ticket setPriority(Long ticketId, Priority priority, Long actor) {
        var ticket = locked(ticketId, actor);
        workspace.manager(ticket.getRepo().getProjectId(), actor);
        ticket.setPriority(priority);
        activity(ticketId, actor, "PRIORITY_CHANGED", "Priority set to " + priority);
        return ticketRepository.save(ticket);
    }
    @Transactional
    public Ticket unassign(Long ticketId, Long actor) {
        var ticket = locked(ticketId, actor);
        workspace.manager(ticket.getRepo().getProjectId(), actor);
        requireIdle(ticket);
        ticket.setAssignedTo(null);
        activity(ticketId, actor, "UNASSIGNED", "Project manager unassigned the issue");
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket updateReport(Long ticketId, String steps, String expected, String actual, Severity severity, Long actor) {
        var ticket = locked(ticketId, actor);
        workspace.requireRole(ticket.getRepo().getProjectId(), actor, Role.QA);
        requireIdle(ticket);
        ticket.setReproductionSteps(steps); ticket.setExpectedBehavior(expected); ticket.setActualBehavior(actual); ticket.setSeverity(severity);
        activity(ticketId, actor, "REPORT_UPDATED", "QA updated reproduction steps, expected behavior, actual behavior, and severity");
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket verify(Long ticketId, String notes, Long actor) {
        var ticket = locked(ticketId, actor);
        workspace.requireRole(ticket.getRepo().getProjectId(), actor, Role.QA);
        if (ticket.getStatus() != TicketStatus.AWAITING_QA)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only issues awaiting QA verification can be marked solved");
        ticket.setStatus(TicketStatus.RESOLVED); ticket.setQaVerifiedAt(Instant.now()); ticket.setQaVerifiedBy(actor);
        ticket.setQaNotes(notes); ticket.setQaReopened(false);
        activity(ticketId, actor, "QA_VERIFIED", notes);
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket reopen(Long ticketId, String notes, Long actor) {
        var ticket = locked(ticketId, actor);
        workspace.requireRole(ticket.getRepo().getProjectId(), actor, Role.QA);
        if (ticket.getStatus() != TicketStatus.RESOLVED && ticket.getStatus() != TicketStatus.AWAITING_QA)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only solved issues or fixes awaiting QA can be reopened");
        ticket.setStatus(TicketStatus.OPEN); ticket.setQaVerifiedAt(null); ticket.setQaVerifiedBy(null);
        ticket.setQaNotes(notes); ticket.setQaReopened(true);
        activity(ticketId, actor, "QA_REOPENED", notes);
        return ticketRepository.save(ticket);
    }

    private Ticket locked(Long ticketId, Long actor) {
        Long projectId = ticketRepository.findProjectId(ticketId).orElseThrow(() -> new IllegalArgumentException("Issue not found"));
        workspace.lockProject(projectId, actor);
        ticketRepository.lockById(ticketId).orElseThrow(() -> new IllegalArgumentException("Issue not found"));
        return access.ticket(ticketId, actor);
    }
    private void requireIdle(Ticket ticket) {
        if (List.of(TicketStatus.ANALYZING, TicketStatus.PLAN_APPROVED, TicketStatus.IMPLEMENTING, TicketStatus.TESTING).contains(ticket.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for the agent job to finish before changing this issue");
    }
    private void activity(Long ticketId, Long actor, String action, String message) {
        activities.save(TicketActivity.builder().ticketId(ticketId).actorUserId(actor).action(action).message(message).build());
    }

    public Ticket getOrThrow(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));
    }

    public List<Ticket> list(Long repoId, Long assignedToId, Long userId) {
        return ticketRepository.findVisible(userId, repoId, assignedToId);
    }
}
