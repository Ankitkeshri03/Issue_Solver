package com.gitissuesolver.backend.workspace;

import com.gitissuesolver.backend.auth.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class WorkspaceController {
    public record WorkspaceRequest(@NotBlank @Size(max = 150) String name) {}
    public record ProjectRequest(@NotBlank @Size(max = 150) String name, @Size(max = 2000) String description) {}
    public record ProjectRoleRequest(@NotNull Role role) {}
    public record AdminRequest(boolean admin) {}
    public record InviteRequest(@NotBlank @Email @Size(max = 255) String email, @NotNull Role role, Long projectId, Boolean sendEmail) {}
    public record TokenRequest(@NotBlank @Size(max = 100) String token) {}
    public record InviteResponse(Long id, String invitationUrl, java.time.Instant expiresAt, boolean emailSent) {}
    private final WorkspaceService service;
    private final InvitationMailService mail;
    public WorkspaceController(WorkspaceService service, InvitationMailService mail) { this.service = service; this.mail = mail; }
    @GetMapping("/api/workspaces")
    public List<WorkspaceService.WorkspaceView> list(@AuthenticationPrincipal CustomUserDetails user) { return service.list(user.getUser().getId()); }
    @PostMapping("/api/workspaces")
    public WorkspaceService.WorkspaceView create(@Valid @RequestBody WorkspaceRequest body, @AuthenticationPrincipal CustomUserDetails user) {
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,
                "Company workspaces are provisioned by the operator. Ask your company admin for an invitation.");
    }
    @GetMapping("/api/workspaces/{id}")
    public WorkspaceService.WorkspaceView get(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) { return service.get(id, user.getUser().getId()); }
    @GetMapping("/api/projects")
    public List<WorkspaceService.ProjectView> projects(@RequestParam(required = false) Long workspaceId, @AuthenticationPrincipal CustomUserDetails user) {
        return service.projects(workspaceId, user.getUser().getId());
    }
    @GetMapping("/api/projects/{id}")
    public WorkspaceService.ProjectView project(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) { return service.projectView(id, user.getUser().getId()); }
    @PostMapping("/api/workspaces/{id}/projects")
    public WorkspaceService.ProjectView createProject(@PathVariable Long id, @Valid @RequestBody ProjectRequest body, @AuthenticationPrincipal CustomUserDetails user) {
        return service.createProject(id, body.name(), body.description(), user.getUser().getId());
    }
    @GetMapping("/api/workspaces/{id}/members")
    public List<WorkspaceService.MemberView> members(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) { return service.members(id, user.getUser().getId()); }
    @PutMapping("/api/workspaces/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void admin(@PathVariable Long id, @PathVariable Long userId, @RequestBody AdminRequest body, @AuthenticationPrincipal CustomUserDetails user) {
        service.setAdmin(id, userId, body.admin(), user.getUser().getId());
    }
    @DeleteMapping("/api/workspaces/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id, @PathVariable Long userId, @AuthenticationPrincipal CustomUserDetails user) { service.removeMember(id, userId, user.getUser().getId()); }
    @GetMapping("/api/projects/{id}/members")
    public List<WorkspaceService.ProjectMemberView> projectMembers(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) { return service.projectMembers(id, user.getUser().getId()); }
    @GetMapping("/api/projects/{id}/available-members")
    public List<WorkspaceService.MemberCandidate> availableMembers(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        return service.availableProjectMembers(id, user.getUser().getId());
    }
    @GetMapping("/api/projects/{id}/invitations")
    public List<WorkspaceService.InvitationView> projectInvitations(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        return service.projectInvitations(id, user.getUser().getId());
    }
    @PutMapping("/api/projects/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void projectRole(@PathVariable Long id, @PathVariable Long userId, @Valid @RequestBody ProjectRoleRequest body, @AuthenticationPrincipal CustomUserDetails user) {
        service.setProjectRole(id, userId, body.role(), user.getUser().getId());
    }
    @DeleteMapping("/api/projects/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeProjectMember(@PathVariable Long id, @PathVariable Long userId, @AuthenticationPrincipal CustomUserDetails user) { service.removeProjectMember(id, userId, user.getUser().getId()); }
    @GetMapping("/api/workspaces/{id}/invitations")
    public List<WorkspaceService.InvitationView> invitations(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) { return service.invitations(id, user.getUser().getId()); }
    @PostMapping("/api/workspaces/{id}/invitations")
    public ResponseEntity<InviteResponse> invite(@PathVariable Long id, @Valid @RequestBody InviteRequest body, @AuthenticationPrincipal CustomUserDetails user) {
        var created = service.invite(id, body.projectId(), body.email(), body.role(), user.getUser().getId());
        boolean sent = Boolean.TRUE.equals(body.sendEmail()) && mail.send(created);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new InviteResponse(created.id(), created.invitationUrl(), created.expiresAt(), sent));
    }
    @DeleteMapping("/api/workspaces/{id}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id, @PathVariable Long invitationId, @AuthenticationPrincipal CustomUserDetails user) { service.revokeInvitation(id, invitationId, user.getUser().getId()); }
    @PostMapping("/api/invitations/details")
    public ResponseEntity<WorkspaceService.InvitationDetails> details(@Valid @RequestBody TokenRequest body, @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.invitationDetails(body.token(), user.getUser().getId()));
    }
    @PostMapping("/api/invitations/accept")
    public ResponseEntity<WorkspaceService.WorkspaceView> accept(@Valid @RequestBody TokenRequest body, @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.accept(body.token(), user.getUser().getId()));
    }
}
