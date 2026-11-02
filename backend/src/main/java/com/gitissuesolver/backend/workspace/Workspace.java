package com.gitissuesolver.backend.workspace;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "workspaces")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Workspace {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 150) private String name;
    private Long ownerUserId;
    @Column(nullable = false) @org.hibernate.annotations.ColumnDefault("false") @Builder.Default private boolean provisioningPending = false;
    @Column(unique = true, length = 36) private String provisioningRequestId;
    @Column(unique = true) private Long legacyOwnerUserId;
    @Column(nullable = false) @Builder.Default private Instant createdAt = Instant.now();
}
