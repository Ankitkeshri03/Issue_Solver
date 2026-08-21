package com.gitissuesolver.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findByRepoId(Long repoId);
    List<Ticket> findByAssignedToId(Long userId);
    List<Ticket> findByStatus(TicketStatus status);

    // Async agent methods run outside the request's Hibernate session, so the lazily-loaded
    // Repo association must be fetched eagerly up front or accessing it throws
    // LazyInitializationException ("no session").
    @Query("select t from Ticket t join fetch t.repo where t.id = :id")
    Optional<Ticket> findByIdWithRepo(@Param("id") Long id);
}
