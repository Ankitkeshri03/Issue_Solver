package com.gitissuesolver.backend.issue.dto;

import com.gitissuesolver.backend.issue.Ticket;
import com.gitissuesolver.backend.issue.TicketStatus;
import com.gitissuesolver.backend.issue.Priority;
import com.gitissuesolver.backend.issue.Severity;
import com.gitissuesolver.backend.auth.Role;

import java.time.Instant;

public record TicketResponse(
        Long id,
        Long repoId,
        String repoFullName,
        Integer githubIssueNumber,
        String title,
        String description,
        TicketStatus status,
        String plan,
        String prUrl,
        String branchName,
        Long assignedToId,
        String assignedToUsername,
        Instant createdAt,
        Instant updatedAt,
        Long projectId,
        String reproductionSteps,
        String expectedBehavior,
        String actualBehavior,
        Priority priority,
        Severity severity,
        Long qaVerifiedBy,
        Instant qaVerifiedAt,
        String qaNotes,
        Permissions permissions
) {
    public record Permissions(boolean canAssign, boolean canPrioritize, boolean canReport, boolean canDevelop,
                              boolean canVerify, boolean canReopen) {}
    public static TicketResponse from(Ticket t, Long actor, Role role, boolean canAssign) {
        return new TicketResponse(
                t.getId(),
                t.getRepo().getId(),
                t.getRepo().fullName(),
                t.getGithubIssueNumber(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus(),
                t.getPlan(),
                t.getPrUrl(),
                t.getBranchName(),
                t.getAssignedTo() != null ? t.getAssignedTo().getId() : null,
                t.getAssignedTo() != null ? t.getAssignedTo().getUsername() : null,
                t.getCreatedAt(),
                t.getUpdatedAt(), t.getRepo().getProjectId(), t.getReproductionSteps(), t.getExpectedBehavior(),
                t.getActualBehavior(), t.getPriority(), t.getSeverity(), t.getQaVerifiedBy(), t.getQaVerifiedAt(), t.getQaNotes(),
                new Permissions(canAssign, canAssign, role == Role.QA,
                        role == Role.DEVELOPER && t.getAssignedTo() != null && t.getAssignedTo().getId().equals(actor),
                        role == Role.QA && t.getStatus() == TicketStatus.AWAITING_QA,
                        role == Role.QA && (t.getStatus() == TicketStatus.AWAITING_QA || t.getStatus() == TicketStatus.RESOLVED))
        );
    }
}
