package com.gitissuesolver.backend.issue;

import com.gitissuesolver.backend.auth.User;
import com.gitissuesolver.backend.auth.UserRepository;
import com.gitissuesolver.backend.github.Repo;
import com.gitissuesolver.backend.github.RepoRepository;
import com.gitissuesolver.backend.issue.dto.CreateTicketRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final RepoRepository repoRepository;
    private final UserRepository userRepository;

    public TicketService(TicketRepository ticketRepository, RepoRepository repoRepository, UserRepository userRepository) {
        this.ticketRepository = ticketRepository;
        this.repoRepository = repoRepository;
        this.userRepository = userRepository;
    }

    public Ticket create(CreateTicketRequest request, Long createdByUserId) {
        Repo repo = repoRepository.findById(request.repoId())
                .orElseThrow(() -> new IllegalArgumentException("Repo not found: " + request.repoId()));
        User createdBy = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + createdByUserId));

        Ticket ticket = Ticket.builder()
                .repo(repo)
                .githubIssueNumber(request.githubIssueNumber())
                .title(request.title())
                .description(request.description())
                .status(TicketStatus.OPEN)
                .createdBy(createdBy)
                .build();
        return ticketRepository.save(ticket);
    }

    public Ticket assign(Long ticketId, Long developerId) {
        Ticket ticket = getOrThrow(ticketId);
        User developer = userRepository.findById(developerId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + developerId));
        ticket.setAssignedTo(developer);
        return ticketRepository.save(ticket);
    }

    public Ticket updateStatus(Long ticketId, TicketStatus status) {
        Ticket ticket = getOrThrow(ticketId);
        ticket.setStatus(status);
        return ticketRepository.save(ticket);
    }

    public Ticket getOrThrow(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));
    }

    public List<Ticket> list(Long repoId, Long assignedToId) {
        if (repoId != null) return ticketRepository.findByRepoId(repoId);
        if (assignedToId != null) return ticketRepository.findByAssignedToId(assignedToId);
        return ticketRepository.findAll();
    }
}
