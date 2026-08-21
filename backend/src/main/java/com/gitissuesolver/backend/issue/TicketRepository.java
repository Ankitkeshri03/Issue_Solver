package com.gitissuesolver.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findByRepoId(Long repoId);
    List<Ticket> findByAssignedToId(Long userId);
    List<Ticket> findByStatus(TicketStatus status);
}
