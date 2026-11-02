package com.gitissuesolver.backend.workspace;

import com.gitissuesolver.backend.auth.Role;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "workspace_invitations")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class WorkspaceInvitation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long workspaceId;
    private Long projectId;
    @Column(nullable = false, length = 255) private String email;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Role role;
    @Column(nullable = false, unique = true, length = 64) private String tokenHash;
    private Long createdBy;
    @Column(nullable = false) @org.hibernate.annotations.ColumnDefault("false") @Builder.Default private boolean bootstrap = false;
    @Column(nullable = false) private Instant expiresAt;
    private Instant acceptedAt;
    @Column(nullable = false) @Builder.Default private boolean revoked = false;
    @Column(nullable = false) @Builder.Default private Instant createdAt = Instant.now();
}
