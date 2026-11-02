package com.gitissuesolver.backend.agent;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import com.gitissuesolver.backend.auth.CustomUserDetails;
import com.gitissuesolver.backend.github.ProjectAccess;
import com.gitissuesolver.backend.issue.TicketRepository;
import com.gitissuesolver.backend.issue.TicketStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tickets/{ticketId}")
public class AgentController {

    private final AgentService agentService;
    private final AgentProgressPublisher publisher;
    private final AgentStepRepository agentStepRepository;
    private final ProjectAccess access;
    private final TicketRepository tickets;

    public AgentController(AgentService agentService, AgentProgressPublisher publisher,
                            AgentStepRepository agentStepRepository, ProjectAccess access, TicketRepository tickets) {
        this.agentService = agentService;
        this.publisher = publisher;
        this.agentStepRepository = agentStepRepository;
        this.access = access;
        this.tickets = tickets;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Void> analyze(@PathVariable Long ticketId, @AuthenticationPrincipal CustomUserDetails user) {
        access.prepareAgentRun(ticketId, user.getUser().getId(), false);
        agentService.analyzeAsync(ticketId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/approve")
    public ResponseEntity<Void> approve(@PathVariable Long ticketId, @AuthenticationPrincipal CustomUserDetails user) {
        access.prepareAgentRun(ticketId, user.getUser().getId(), true);
        agentService.implementAsync(ticketId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/steps")
    public List<AgentStep> steps(@PathVariable Long ticketId, @AuthenticationPrincipal CustomUserDetails user) {
        access.ticket(ticketId, user.getUser().getId());
        return agentStepRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }

    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable Long ticketId, @AuthenticationPrincipal CustomUserDetails user) {
        access.ticket(ticketId, user.getUser().getId());
        return publisher.subscribe(ticketId);
    }
}
