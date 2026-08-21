package com.gitissuesolver.backend.issue.dto;

import com.gitissuesolver.backend.issue.Ticket;
import com.gitissuesolver.backend.issue.TicketStatus;

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
        Instant updatedAt
) {
    public static TicketResponse from(Ticket t) {
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
                t.getUpdatedAt()
        );
    }
}
