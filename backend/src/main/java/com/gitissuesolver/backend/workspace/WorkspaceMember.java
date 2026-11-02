package com.gitissuesolver.backend.workspace;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "workspace_members", uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "user_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class WorkspaceMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long workspaceId;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false) private boolean admin;
}
