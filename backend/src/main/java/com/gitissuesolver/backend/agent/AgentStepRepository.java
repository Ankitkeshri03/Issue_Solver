package com.gitissuesolver.backend.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {
    List<AgentStep> findByTicketIdOrderByCreatedAtAsc(Long ticketId);
}
