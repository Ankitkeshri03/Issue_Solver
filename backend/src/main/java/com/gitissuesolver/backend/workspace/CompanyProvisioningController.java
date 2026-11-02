package com.gitissuesolver.backend.workspace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

/** Operator-only bootstrap. The secret is never delivered to the frontend. */
@RestController
@RequestMapping("/api/operator/companies")
public class CompanyProvisioningController {
    public record CompanyRequest(@NotBlank @Size(max=150) String companyName,
                                 @NotBlank @Email @Size(max=255) String adminEmail,
                                 @NotNull UUID requestId, Boolean sendEmail) {}
    public record ReissueRequest(@NotBlank @Email @Size(max=255) String adminEmail, Boolean sendEmail) {}
    public record ProvisioningResult(Long companyId, String invitationUrl, Instant expiresAt, boolean emailSent) {}
    private final CompanyProvisioningService service;
    private final InvitationMailService mail;
    private final byte[] key;
    public CompanyProvisioningController(CompanyProvisioningService service, InvitationMailService mail,
            @Value("${app.provisioning.key:}") String key) {
        if (!key.isEmpty() && key.length() < 32) throw new IllegalArgumentException("COMPANY_PROVISIONING_KEY must have at least 32 characters");
        this.service=service; this.mail=mail; this.key=key.getBytes(StandardCharsets.UTF_8);
    }
    private void authorize(String supplied) {
        if (key.length == 0 || !MessageDigest.isEqual(key, (supplied == null ? "" : supplied).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid operator authorization");
    }
    @PostMapping
    public ResponseEntity<ProvisioningResult> provision(@RequestHeader(value="X-Provisioning-Key", required=false) String supplied,
            @Valid @RequestBody CompanyRequest body) {
        authorize(supplied);
        return response(service.provision(body.companyName(), body.adminEmail(), body.requestId()), body.sendEmail());
    }
    @PostMapping("/{companyId}/admin-invitation")
    public ResponseEntity<ProvisioningResult> reissue(@PathVariable Long companyId,
            @RequestHeader(value="X-Provisioning-Key", required=false) String supplied, @Valid @RequestBody ReissueRequest body) {
        authorize(supplied); return response(service.reissue(companyId, body.adminEmail()), body.sendEmail());
    }
    private ResponseEntity<ProvisioningResult> response(CompanyProvisioningService.Provisioned provisioned, Boolean sendEmail) {
        boolean sent = Boolean.TRUE.equals(sendEmail) && mail.send(provisioned.invitation());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ProvisioningResult(provisioned.companyId(),
                provisioned.invitation().invitationUrl(), provisioned.invitation().expiresAt(), sent));
    }
}
