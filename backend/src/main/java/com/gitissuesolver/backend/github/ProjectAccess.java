package com.gitissuesolver.backend.github;

import com.gitissuesolver.backend.issue.Ticket;
import com.gitissuesolver.backend.issue.TicketRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.Objects;
import com.gitissuesolver.backend.auth.Role;
import com.gitissuesolver.backend.workspace.WorkspaceAccess;
import com.gitissuesolver.backend.issue.TicketStatus;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class ProjectAccess {
    private final RepoRepository repos;
    private final TicketRepository tickets;
    private final WorkspaceAccess workspace;
    public ProjectAccess(RepoRepository repos, TicketRepository tickets, WorkspaceAccess workspace) { this.repos = repos; this.tickets = tickets; this.workspace = workspace; }

    public Repo repo(Long id, Long userId) {
        Repo repo = repos.findById(id).orElseThrow(this::notFound);
        if (repo.getProjectId() == null) throw notFound();
        workspace.project(repo.getProjectId(), userId);
        return repo;
    }

    public Repo managedRepo(Long id, Long userId) {
        Repo repo = repo(id, userId);
        var project = workspace.project(repo.getProjectId(), userId);
        workspace.admin(project.getWorkspaceId(), userId);
        return repo;
    }

    public Ticket ticket(Long id, Long userId) {
        Ticket ticket = tickets.findByIdWithRepo(id).orElseThrow(this::notFound);
        if (ticket.isGithubPullRequest() || ticket.getRepo().getProjectId() == null) throw notFound();
        workspace.project(ticket.getRepo().getProjectId(), userId);
        return ticket;
    }

    public Ticket developerTicket(Long id, Long userId) {
        var ticket = ticket(id, userId);
        workspace.requireRole(ticket.getRepo().getProjectId(), userId, Role.DEVELOPER);
        if (ticket.getAssignedTo() == null || !Objects.equals(ticket.getAssignedTo().getId(), userId))
            throw workspace.forbidden("Only the assigned developer can run the agent for this issue");
        return ticket;
    }

    public boolean canAssign(Long projectId, Long userId) {
        var project = workspace.project(projectId, userId);
        return workspace.projectRole(projectId, userId) == Role.PROJECT_MANAGER;
    }
    public Role role(Long projectId, Long userId) { return workspace.projectRole(projectId, userId); }

    @Transactional
    public void prepareAgentRun(Long ticketId, Long actor, boolean implementation) {
        Long projectId = tickets.findProjectId(ticketId).orElseThrow(this::notFound);
        workspace.lockProject(projectId, actor);
        var ticket = developerTicket(ticketId, actor);
        if (implementation && (ticket.getPlan() == null || ticket.getPlan().isBlank()))
            throw new IllegalArgumentException("Analyze the ticket before approving a fix");
        var allowed = implementation ? List.of(TicketStatus.PLAN_READY, TicketStatus.FAILED) : List.of(TicketStatus.OPEN, TicketStatus.FAILED);
        if (tickets.transition(ticketId, allowed, implementation ? TicketStatus.PLAN_APPROVED : TicketStatus.ANALYZING) == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This issue is already running or cannot enter this phase");
    }

    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository or ticket not found"); }
}
