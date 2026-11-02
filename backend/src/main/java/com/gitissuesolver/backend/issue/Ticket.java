package com.gitissuesolver.backend.issue;

import com.gitissuesolver.backend.auth.User;
import com.gitissuesolver.backend.github.Repo;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false)
    private Repo repo;

    @Column(name = "github_issue_number", nullable = false)
    private Integer githubIssueNumber;

    @Column(nullable = false)
    @Builder.Default
    private boolean githubPullRequest = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean githubIssueClosed = false;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT") private String reproductionSteps;
    @Column(columnDefinition = "TEXT") private String expectedBehavior;
    @Column(columnDefinition = "TEXT") private String actualBehavior;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    @Builder.Default private Priority priority = Priority.MEDIUM;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    @Builder.Default private Severity severity = Severity.MEDIUM;
    private Long qaVerifiedBy;
    private Instant qaVerifiedAt;
    @Column(columnDefinition = "TEXT") private String qaNotes;
    @Column(nullable = false) @Builder.Default private boolean qaReopened = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private TicketStatus status = TicketStatus.OPEN;

    @Column(columnDefinition = "TEXT")
    private String plan;

    @Column(name = "pr_url")
    private String prUrl;

    @Column(name = "branch_name")
    private String branchName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
