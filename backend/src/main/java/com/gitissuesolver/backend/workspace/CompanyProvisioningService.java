package com.gitissuesolver.backend.workspace;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
public class CompanyProvisioningService {
    public record Provisioned(Long companyId, WorkspaceService.InvitationCreated invitation) {}
    private final WorkspaceRepository companies;
    private final WorkspaceService teams;
    public CompanyProvisioningService(WorkspaceRepository companies, WorkspaceService teams) { this.companies = companies; this.teams = teams; }
    @Transactional
    public Provisioned provision(String name, String adminEmail, UUID requestId) {
        if (companies.findByProvisioningRequestId(requestId.toString()).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This provisioning request was already processed. Reissue its admin invitation instead of creating another company.");
        var company = companies.save(Workspace.builder().name(name.trim()).provisioningPending(true)
                .provisioningRequestId(requestId.toString()).build());
        return new Provisioned(company.getId(), teams.bootstrapInvitation(company, adminEmail));
    }
    @Transactional
    public Provisioned reissue(Long companyId, String adminEmail) {
        var company = companies.lockById(companyId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));
        return new Provisioned(companyId, teams.bootstrapInvitation(company, adminEmail));
    }
}
