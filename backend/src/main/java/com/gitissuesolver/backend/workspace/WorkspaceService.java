package com.gitissuesolver.backend.workspace;

import com.gitissuesolver.backend.auth.*;
import com.gitissuesolver.backend.issue.TicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class WorkspaceService {
    public record WorkspaceView(Long id, String name, boolean admin) {}
    public record ProjectView(Long id, Long workspaceId, String workspaceName, String name, String description,
                              Role myRole, boolean workspaceAdmin) {}
    public record MemberView(Long userId, String username, String email, boolean admin) {}
    public record ProjectMemberView(Long userId, String username, Role role, boolean admin) {}
    public record InvitationView(Long id, String email, Role role, Long projectId, Instant expiresAt, boolean accepted, boolean revoked) {}
    public record InvitationCreated(Long id, String email, String workspaceName, String invitationUrl, Instant expiresAt) {}
    public record InvitationDetails(String workspaceName, String projectName, String email, Role role) {}
    public record InvitationPreview(String workspaceName, String projectName, String email, Role role,
                                    boolean accountExists, Instant expiresAt) {}
    public record MemberCandidate(Long userId, String username) {}

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final ProjectRepository projects;
    private final ProjectMemberRepository projectMembers;
    private final WorkspaceInvitationRepository invitations;
    private final UserRepository users;
    private final TicketRepository tickets;
    private final WorkspaceAccess access;
    private final String frontendUrl;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();

    public WorkspaceService(WorkspaceRepository workspaces, WorkspaceMemberRepository members, ProjectRepository projects,
                            ProjectMemberRepository projectMembers, WorkspaceInvitationRepository invitations,
                            UserRepository users, TicketRepository tickets, WorkspaceAccess access,
                            @Value("${app.password-reset.frontend-url:http://localhost:5173}") String frontendUrl,
                            PasswordEncoder passwords) {
        this.workspaces = workspaces; this.members = members; this.projects = projects;
        this.projectMembers = projectMembers; this.invitations = invitations; this.users = users;
        this.tickets = tickets; this.access = access; this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        this.passwords = passwords;
        var uri = java.net.URI.create(this.frontendUrl);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme())))
            throw new IllegalArgumentException("FRONTEND_URL must be HTTPS (HTTP is allowed on localhost)");
    }

    public List<WorkspaceView> list(Long userId) {
        return workspaces.findVisible(userId).stream().map(w -> new WorkspaceView(w.getId(), w.getName(), access.isAdmin(w.getId(), userId))).toList();
    }
    @Transactional
    public WorkspaceView create(String name, Long userId) {
        // Internal creation for migrations/tests. The public API cannot self-provision companies.
        var workspace = workspaces.save(Workspace.builder().name(name.trim()).ownerUserId(userId).build());
        members.save(WorkspaceMember.builder().workspaceId(workspace.getId()).userId(userId).admin(true).build());
        return new WorkspaceView(workspace.getId(), workspace.getName(), true);
    }
    public WorkspaceView get(Long id, Long userId) {
        var workspace = access.workspace(id, userId);
        return new WorkspaceView(id, workspace.getName(), access.isAdmin(id, userId));
    }
    public List<ProjectView> projects(Long workspaceId, Long userId) {
        if (workspaceId != null) access.workspace(workspaceId, userId);
        return projects.findVisible(workspaceId, userId).stream().map(p -> projectView(p.getId(), userId)).toList();
    }
    public ProjectView projectView(Long projectId, Long userId) {
        var project = access.project(projectId, userId);
        var workspace = access.workspace(project.getWorkspaceId(), userId);
        return new ProjectView(projectId, workspace.getId(), workspace.getName(), project.getName(), project.getDescription(),
                access.projectRole(projectId, userId), access.isAdmin(workspace.getId(), userId));
    }
    @Transactional
    public ProjectView createProject(Long workspaceId, String name, String description, Long userId) {
        access.admin(workspaceId, userId);
        workspaces.lockById(workspaceId).orElseThrow(access::notFound);
        access.admin(workspaceId, userId);
        var project = projects.save(Project.builder().workspaceId(workspaceId).name(name.trim()).description(description).build());
        return projectView(project.getId(), userId);
    }
    public List<MemberView> members(Long workspaceId, Long userId) {
        access.admin(workspaceId, userId);
        return members.findByWorkspaceId(workspaceId).stream().map(m -> {
            var user = users.findById(m.getUserId()).orElseThrow();
            return new MemberView(user.getId(), user.getUsername(), user.getEmail(), m.isAdmin());
        }).toList();
    }
    public List<ProjectMemberView> projectMembers(Long projectId, Long userId) {
        var project = access.project(projectId, userId);
        return projectMembers.findByProjectId(projectId).stream()
                .filter(m -> members.existsByWorkspaceIdAndUserId(project.getWorkspaceId(), m.getUserId()))
                .map(m -> new ProjectMemberView(m.getUserId(), users.findById(m.getUserId()).orElseThrow().getUsername(), m.getRole(), access.isAdmin(project.getWorkspaceId(), m.getUserId()))).toList();
    }
    public List<MemberCandidate> availableProjectMembers(Long projectId, Long actor) {
        var project = access.teamManager(projectId, actor);
        boolean admin = access.isAdmin(project.getWorkspaceId(), actor);
        return members.findByWorkspaceId(project.getWorkspaceId()).stream()
                .filter(member -> admin || !member.isAdmin() && projectMembers.findRole(projectId, member.getUserId()).orElse(null) != Role.PROJECT_MANAGER)
                .map(member -> new MemberCandidate(member.getUserId(), users.findById(member.getUserId()).orElseThrow().getUsername())).toList();
    }
    @Transactional
    public void setAdmin(Long workspaceId, Long targetUser, boolean admin, Long actor) {
        access.admin(workspaceId, actor);
        var workspace = workspaces.lockById(workspaceId).orElseThrow(access::notFound);
        access.admin(workspaceId, actor);
        if (Objects.equals(workspace.getOwnerUserId(), targetUser) && !admin)
            throw new IllegalArgumentException("The workspace owner must remain an admin");
        var member = members.findByWorkspaceIdAndUserId(workspaceId, targetUser).orElseThrow(access::notFound);
        member.setAdmin(admin); members.save(member);
        revokePending(workspaceId, targetUser);
    }
    @Transactional
    public void removeMember(Long workspaceId, Long targetUser, Long actor) {
        access.admin(workspaceId, actor);
        var workspace = workspaces.lockById(workspaceId).orElseThrow(access::notFound);
        access.admin(workspaceId, actor);
        if (Objects.equals(workspace.getOwnerUserId(), targetUser)) throw new IllegalArgumentException("The workspace owner cannot be removed");
        var member = members.findByWorkspaceIdAndUserId(workspaceId, targetUser).orElseThrow(access::notFound);
        for (var project : projects.findByWorkspaceId(workspaceId)) {
            requireNoActiveJob(project.getId(), targetUser);
            tickets.unassignProjectMember(project.getId(), targetUser);
            projectMembers.deleteByProjectIdAndUserId(project.getId(), targetUser);
        }
        members.delete(member); revokePending(workspaceId, targetUser);
    }
    @Transactional
    public void setProjectRole(Long projectId, Long targetUser, Role role, Long actor) {
        validateProjectRole(role);
        var project = access.teamManager(projectId, actor);
        workspaces.lockById(project.getWorkspaceId()).orElseThrow(access::notFound);
        access.teamManager(projectId, actor);
        requireManageableMember(project, targetUser, role, actor);
        if (!members.existsByWorkspaceIdAndUserId(project.getWorkspaceId(), targetUser))
            throw new IllegalArgumentException("Invite this person to the workspace before adding them to a project");
        requireNoActiveJob(projectId, targetUser);
        var member = projectMembers.findByProjectIdAndUserId(projectId, targetUser)
                .orElseGet(() -> ProjectMember.builder().projectId(projectId).userId(targetUser).build());
        member.setRole(role); projectMembers.save(member);
        if (role != Role.DEVELOPER) tickets.unassignProjectMember(projectId, targetUser);
        revokePendingProject(project.getWorkspaceId(), projectId, targetUser);
    }
    @Transactional
    public void removeProjectMember(Long projectId, Long targetUser, Long actor) {
        var project = access.teamManager(projectId, actor);
        workspaces.lockById(project.getWorkspaceId()).orElseThrow(access::notFound);
        access.teamManager(projectId, actor);
        requireManageableMember(project, targetUser, null, actor);
        requireNoActiveJob(projectId, targetUser);
        tickets.unassignProjectMember(projectId, targetUser);
        projectMembers.deleteByProjectIdAndUserId(projectId, targetUser);
        revokePendingProject(project.getWorkspaceId(), projectId, targetUser);
    }
    private void requireManageableMember(Project project, Long targetUser, Role requestedRole, Long actor) {
        if (access.isAdmin(project.getWorkspaceId(), actor)) return;
        Role currentRole = projectMembers.findRole(project.getId(), targetUser).orElse(null);
        if (requestedRole == Role.PROJECT_MANAGER || requestedRole == Role.WORKSPACE_ADMIN
                || currentRole == Role.PROJECT_MANAGER || access.isAdmin(project.getWorkspaceId(), targetUser))
            throw access.forbidden("Project managers can manage QA and Developer members only; admins appoint managers");
    }
    private void revokePendingProject(Long workspaceId, Long projectId, Long targetUser) {
        String email = users.findById(targetUser).orElseThrow().getEmail();
        for (var invitation : invitations.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId)) {
            if (Objects.equals(invitation.getProjectId(), projectId) && invitation.getEmail().equalsIgnoreCase(email)
                    && invitation.getAcceptedAt() == null) {
                invitation.setRevoked(true); invitations.save(invitation);
            }
        }
    }
    private void requireNoActiveJob(Long projectId, Long userId) {
        if (tickets.countRunningForMember(projectId, userId) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for this developer's active agent job to finish before changing access");
    }
    private void revokePending(Long workspaceId, Long targetUser) {
        String email = users.findById(targetUser).orElseThrow().getEmail();
        for (var invitation : invitations.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId)) {
            if (invitation.getEmail().equalsIgnoreCase(email) && invitation.getAcceptedAt() == null) {
                invitation.setRevoked(true); invitations.save(invitation);
            }
        }
    }
    public List<InvitationView> invitations(Long workspaceId, Long actor) {
        access.admin(workspaceId, actor);
        return invitations.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream().map(i -> new InvitationView(
                i.getId(), i.getEmail(), i.getRole(), i.getProjectId(), i.getExpiresAt(), i.getAcceptedAt() != null, i.isRevoked())).toList();
    }
    public List<InvitationView> projectInvitations(Long projectId, Long actor) {
        var project = access.teamManager(projectId, actor);
        boolean admin = access.isAdmin(project.getWorkspaceId(), actor);
        return invitations.findByWorkspaceIdOrderByCreatedAtDesc(project.getWorkspaceId()).stream()
                .filter(i -> Objects.equals(i.getProjectId(), projectId))
                .filter(i -> admin || i.getRole() == Role.QA || i.getRole() == Role.DEVELOPER)
                .map(i -> new InvitationView(i.getId(), i.getEmail(), i.getRole(), i.getProjectId(), i.getExpiresAt(), i.getAcceptedAt() != null, i.isRevoked())).toList();
    }
    @Transactional
    public InvitationCreated invite(Long workspaceId, Long projectId, String email, Role role, Long actor) {
        var workspace = access.workspace(workspaceId, actor);
        workspaces.lockById(workspaceId).orElseThrow(access::notFound);
        requireInvitationAuthority(workspaceId, projectId, role, actor);
        if (role == Role.WORKSPACE_ADMIN) {
            if (projectId != null) throw new IllegalArgumentException("Workspace admin invitations must not specify a project");
        } else {
            validateProjectRole(role);
            if (projectId == null || !projects.findById(projectId).map(p -> p.getWorkspaceId().equals(workspaceId)).orElse(false))
                throw new IllegalArgumentException("Select a project in this workspace");
            users.findByEmailIgnoreCase(email.trim()).ifPresent(target ->
                    requireManageableMember(projects.findById(projectId).orElseThrow(access::notFound), target.getId(), role, actor));
        }
        return saveInvitation(workspace, projectId, email, role, actor, false);
    }
    private InvitationCreated saveInvitation(Workspace workspace, Long projectId, String email, Role role, Long actor, boolean bootstrap) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var invitation = invitations.save(WorkspaceInvitation.builder().workspaceId(workspace.getId()).projectId(projectId)
                .email(normalizedEmail).role(role).tokenHash(hash(token)).createdBy(actor).bootstrap(bootstrap)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS)).build());
        return new InvitationCreated(invitation.getId(), normalizedEmail, workspace.getName(),
                frontendUrl + "/invitations#token=" + token, invitation.getExpiresAt());
    }
    InvitationCreated bootstrapInvitation(Workspace workspace, String email) {
        if (!workspace.isProvisioningPending() || workspace.getOwnerUserId() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This company already has an activated admin");
        for (var existing : invitations.findByWorkspaceIdOrderByCreatedAtDesc(workspace.getId())) {
            if (existing.isBootstrap() && existing.getAcceptedAt() == null) {
                existing.setRevoked(true); invitations.save(existing);
            }
        }
        return saveInvitation(workspace, null, email, Role.WORKSPACE_ADMIN, null, true);
    }
    private void requireInvitationAuthority(Long workspaceId, Long projectId, Role role, Long actor) {
        if (access.isAdmin(workspaceId, actor)) return;
        if (projectId == null || role != Role.QA && role != Role.DEVELOPER)
            throw access.forbidden("Only workspace admins appoint managers and admins");
        var project = access.teamManager(projectId, actor);
        if (!project.getWorkspaceId().equals(workspaceId)) throw access.notFound();
    }
    @Transactional
    public void revokeInvitation(Long workspaceId, Long invitationId, Long actor) {
        access.workspace(workspaceId, actor);
        workspaces.lockById(workspaceId).orElseThrow(access::notFound);
        var invitation = invitations.findById(invitationId).filter(i -> i.getWorkspaceId().equals(workspaceId))
                .orElseThrow(access::notFound);
        requireInvitationAuthority(workspaceId, invitation.getProjectId(), invitation.getRole(), actor);
        invitation.setRevoked(true); invitations.save(invitation);
    }
    @Transactional
    public InvitationDetails invitationDetails(String token, Long userId) {
        var invitation = validInvitation(token, userId);
        return new InvitationDetails(workspaces.findById(invitation.getWorkspaceId()).orElseThrow(access::notFound).getName(),
                invitation.getProjectId() == null ? null : projects.findById(invitation.getProjectId()).orElseThrow(access::notFound).getName(),
                invitation.getEmail(), invitation.getRole());
    }
    @Transactional
    public InvitationPreview preview(String token) {
        var invitation = validInvitation(token, null);
        var details = new InvitationDetails(workspaces.findById(invitation.getWorkspaceId()).orElseThrow(access::notFound).getName(),
                invitation.getProjectId() == null ? null : projects.findById(invitation.getProjectId()).orElseThrow(access::notFound).getName(),
                invitation.getEmail(), invitation.getRole());
        return new InvitationPreview(details.workspaceName(), details.projectName(), details.email(), details.role(),
                users.existsByEmailIgnoreCase(invitation.getEmail()), invitation.getExpiresAt());
    }
    @Transactional
    public WorkspaceView activate(String token, String username, String email, String password, String confirmation) {
        lockInvitationWorkspace(token);
        var invitation = validInvitation(token, null);
        if (!invitation.getEmail().equalsIgnoreCase(email.trim()))
            throw access.forbidden("Use the email address that received this invitation");
        if (users.existsByEmailIgnoreCase(invitation.getEmail()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account already exists. Sign in to accept the invitation; its password has not changed.");
        username = username.trim();
        if (username.length() < 3 || username.length() > 100 || username.contains("@"))
            throw new IllegalArgumentException("Username must have 3–100 characters and cannot contain @");
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Password must have at least 8 characters and at most 72 bytes");
        if (!password.equals(confirmation)) throw new IllegalArgumentException("Passwords do not match");
        if (users.existsByUsername(username)) throw new IllegalArgumentException("Username already taken");
        var user = users.save(User.builder().username(username).email(invitation.getEmail())
                .passwordHash(passwords.encode(password)).role(Role.DEVELOPER).build());
        // No automatic login. The recipient uses the common sign-in page afterward.
        return accept(token, user.getId());
    }
    private void lockInvitationWorkspace(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidInvitation();
        var workspaceId = invitations.findWorkspaceIdByTokenHash(hash(token)).orElseThrow(this::invalidInvitation);
        workspaces.lockById(workspaceId).orElseThrow(access::notFound);
    }
    @Transactional
    public WorkspaceView accept(String token, Long userId) {
        lockInvitationWorkspace(token);
        var invitation = validInvitation(token, userId);
        var member = members.findByWorkspaceIdAndUserId(invitation.getWorkspaceId(), userId)
                .orElseGet(() -> WorkspaceMember.builder().workspaceId(invitation.getWorkspaceId()).userId(userId).build());
        if (invitation.getRole() == Role.WORKSPACE_ADMIN) member.setAdmin(true);
        members.save(member);
        if (invitation.isBootstrap()) {
            var company = workspaces.findById(invitation.getWorkspaceId()).orElseThrow(access::notFound);
            company.setOwnerUserId(userId); company.setProvisioningPending(false); workspaces.save(company);
        }
        if (invitation.getProjectId() != null) {
            requireManageableMember(projects.findById(invitation.getProjectId()).orElseThrow(access::notFound),
                    userId, invitation.getRole(), invitation.getCreatedBy());
            // Existing access changes still obey the active-job constraint.
            requireNoActiveJob(invitation.getProjectId(), userId);
            var projectMember = projectMembers.findByProjectIdAndUserId(invitation.getProjectId(), userId)
                    .orElseGet(() -> ProjectMember.builder().projectId(invitation.getProjectId()).userId(userId).build());
            projectMember.setRole(invitation.getRole()); projectMembers.save(projectMember);
            if (invitation.getRole() != Role.DEVELOPER) tickets.unassignProjectMember(invitation.getProjectId(), userId);
        }
        invitation.setAcceptedAt(Instant.now()); invitations.save(invitation);
        return get(invitation.getWorkspaceId(), userId);
    }
    private WorkspaceInvitation validInvitation(String token, Long userId) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidInvitation();
        var invitation = invitations.lockByTokenHash(hash(token)).orElseThrow(this::invalidInvitation);
        if (invitation.isRevoked() || invitation.getAcceptedAt() != null || !invitation.getExpiresAt().isAfter(Instant.now())) throw invalidInvitation();
        if (invitation.isBootstrap()) {
            var company = workspaces.findById(invitation.getWorkspaceId()).orElseThrow(this::invalidInvitation);
            if (!company.isProvisioningPending() || company.getOwnerUserId() != null || invitation.getCreatedBy() != null
                    || invitation.getRole() != Role.WORKSPACE_ADMIN || invitation.getProjectId() != null) throw invalidInvitation();
        } else {
            try { requireInvitationAuthority(invitation.getWorkspaceId(), invitation.getProjectId(), invitation.getRole(), invitation.getCreatedBy()); }
            catch (ResponseStatusException ex) { throw invalidInvitation(); }
        }
        if (userId != null && !users.findById(userId).orElseThrow().getEmail().equalsIgnoreCase(invitation.getEmail()))
            throw access.forbidden("Sign in with the email address that received this invitation");
        return invitation;
    }
    private ResponseStatusException invalidInvitation() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "This invitation is invalid, expired, revoked, or already accepted");
    }
    private void validateProjectRole(Role role) {
        if (role != Role.PROJECT_MANAGER && role != Role.QA && role != Role.DEVELOPER)
            throw new IllegalArgumentException("Choose Project Manager, QA, or Developer for a project membership");
    }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
