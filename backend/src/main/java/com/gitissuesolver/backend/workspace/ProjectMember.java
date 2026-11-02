package com.gitissuesolver.backend.workspace;

import com.gitissuesolver.backend.auth.Role;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "project_members", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "user_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ProjectMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long projectId;
    @Column(nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Role role;
}
