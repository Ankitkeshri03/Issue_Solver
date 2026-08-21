package com.gitissuesolver.backend.agent;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/tickets/{ticketId}")
public class AgentController {

    private final AgentService agentService;
    private final AgentProgressPublisher publisher;
    private final AgentStepRepository agentStepRepository;

    public AgentController(AgentService agentService, AgentProgressPublisher publisher,
                            AgentStepRepository agentStepRepository) {
        this.agentService = agentService;
        this.publisher = publisher;
        this.agentStepRepository = agentStepRepository;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Void> analyze(@PathVariable Long ticketId) {
        agentService.analyzeAsync(ticketId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/approve")
    public ResponseEntity<Void> approve(@PathVariable Long ticketId) {
        agentService.implementAsync(ticketId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/steps")
    public List<AgentStep> steps(@PathVariable Long ticketId) {
        return agentStepRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }

    @GetMapping("/stream")
    public SseEmitter stream(@PathVariable Long ticketId) {
        return publisher.subscribe(ticketId);
    }
}
