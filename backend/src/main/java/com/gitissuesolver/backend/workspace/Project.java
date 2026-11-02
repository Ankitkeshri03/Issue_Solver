package com.gitissuesolver.backend.workspace;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "projects")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long workspaceId;
    @Column(nullable = false, length = 150) private String name;
    @Column(length = 2000) private String description;
    @Column(unique = true) private Long legacyRepoId;
    @Column(nullable = false) @Builder.Default private Instant createdAt = Instant.now();
}
