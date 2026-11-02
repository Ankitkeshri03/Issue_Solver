package com.gitissuesolver.backend.workspace;

import com.gitissuesolver.backend.auth.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.EnumSet;

@Service
public class WorkspaceAccess {
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final ProjectRepository projects;
    private final ProjectMemberRepository projectMembers;
    public WorkspaceAccess(WorkspaceRepository workspaces, WorkspaceMemberRepository members,
                           ProjectRepository projects, ProjectMemberRepository projectMembers) {
        this.workspaces = workspaces; this.members = members; this.projects = projects; this.projectMembers = projectMembers;
    }
    public Workspace workspace(Long id, Long userId) {
        if (!members.existsByWorkspaceIdAndUserId(id, userId)) throw notFound();
        return workspaces.findById(id).orElseThrow(this::notFound);
    }
    public boolean isAdmin(Long workspaceId, Long userId) {
        return members.existsByWorkspaceIdAndUserIdAndAdminTrue(workspaceId, userId);
    }
    public Workspace admin(Long id, Long userId) {
        var workspace = workspace(id, userId);
        if (!isAdmin(id, userId)) throw forbidden("Only a workspace admin can manage access and GitHub connections");
        return workspace;
    }
    public Project project(Long id, Long userId) {
        var project = projects.findById(id).orElseThrow(this::notFound);
        workspace(project.getWorkspaceId(), userId);
        if (!isAdmin(project.getWorkspaceId(), userId)
                && projectMembers.findByProjectIdAndUserId(id, userId).isEmpty()) throw notFound();
        return project;
    }
    public Role projectRole(Long id, Long userId) {
        var project = project(id, userId);
        return projectMembers.findRole(id, userId)
                .orElseGet(() -> isAdmin(project.getWorkspaceId(), userId) ? Role.WORKSPACE_ADMIN : null);
    }
    public Project requireRole(Long id, Long userId, Role... roles) {
        var project = project(id, userId);
        if (!EnumSet.copyOf(java.util.List.of(roles)).contains(projectRole(id, userId)))
            throw forbidden("Your project role does not allow this action");
        return project;
    }
    public Project manager(Long id, Long userId) {
        var project = project(id, userId);
        if (projectRole(id, userId) != Role.PROJECT_MANAGER)
            throw forbidden("Only a project manager can assign work and set priority");
        return project;
    }
    public Project teamManager(Long id, Long userId) {
        var project = project(id, userId);
        if (!isAdmin(project.getWorkspaceId(), userId) && projectRole(id, userId) != Role.PROJECT_MANAGER)
            throw forbidden("Only the project's managers or workspace admins can manage its team");
        return project;
    }
    public boolean anyAdmin(Long userId) { return members.existsByUserIdAndAdminTrue(userId); }
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Project lockProject(Long id, Long userId) {
        var project = projects.findById(id).orElseThrow(this::notFound);
        workspaces.lockById(project.getWorkspaceId()).orElseThrow(this::notFound);
        return project(id, userId); // Recheck current membership after acquiring the lock.
    }
    public ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace or project not found"); }
    public ResponseStatusException forbidden(String reason) { return new ResponseStatusException(HttpStatus.FORBIDDEN, reason); }
}
