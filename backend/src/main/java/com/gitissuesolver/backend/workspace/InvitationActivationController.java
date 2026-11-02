package com.gitissuesolver.backend.workspace;

import com.gitissuesolver.backend.auth.RecoveryRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/invitations")
public class InvitationActivationController {
    public record TokenRequest(@NotBlank @Size(max=100) String token) {}
    public record ActivationRequest(@NotBlank @Size(max=100) String token,
                                    @NotBlank @Email @Size(max=255) String email,
                                    @NotBlank @Size(min=3,max=100) String username,
                                    @NotBlank @Size(min=8,max=72) String password,
                                    @NotBlank @Size(max=72) String confirmPassword) {}
    private final WorkspaceService teams;
    private final RecoveryRateLimiter limits;
    public InvitationActivationController(WorkspaceService teams, RecoveryRateLimiter limits) { this.teams=teams; this.limits=limits; }
    @PostMapping("/preview")
    public ResponseEntity<WorkspaceService.InvitationPreview> preview(@Valid @RequestBody TokenRequest body, HttpServletRequest request) {
        limits.checkInvitation("invite-preview-ip:" + request.getRemoteAddr(), 120, 3600);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(teams.preview(body.token()));
    }
    @PostMapping("/activate")
    public ResponseEntity<Map<String,String>> activate(@Valid @RequestBody ActivationRequest body, HttpServletRequest request) {
        limits.checkInvitation("invite-activation-ip:" + request.getRemoteAddr(), 30, 3600);
        teams.activate(body.token(), body.username(), body.email(), body.password(), body.confirmPassword());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("message", "Account activated. Sign in with your email or username and chosen password."));
    }
}
