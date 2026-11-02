package com.gitissuesolver.backend.issue;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "ticket_activities")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TicketActivity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long ticketId;
    @Column(nullable = false) private Long actorUserId;
    @Column(nullable = false, length = 40) private String action;
    @Column(columnDefinition = "TEXT") private String message;
    @Column(nullable = false) @Builder.Default private Instant createdAt = Instant.now();
}
