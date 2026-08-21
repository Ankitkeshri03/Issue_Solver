package com.gitissuesolver.backend.issue;

import com.gitissuesolver.backend.auth.CustomUserDetails;
import com.gitissuesolver.backend.issue.dto.AssignTicketRequest;
import com.gitissuesolver.backend.issue.dto.CreateTicketRequest;
import com.gitissuesolver.backend.issue.dto.TicketResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    public List<TicketResponse> list(@RequestParam(required = false) Long repoId,
                                      @RequestParam(required = false) Long assignedToId) {
        return ticketService.list(repoId, assignedToId).stream().map(TicketResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TicketResponse get(@PathVariable Long id) {
        return TicketResponse.from(ticketService.getOrThrow(id));
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest request,
                                                  @AuthenticationPrincipal CustomUserDetails principal) {
        Ticket ticket = ticketService.create(request, principal.getUser().getId());
        return ResponseEntity.ok(TicketResponse.from(ticket));
    }

    @PostMapping("/{id}/assign")
    public TicketResponse assign(@PathVariable Long id, @Valid @RequestBody AssignTicketRequest request) {
        return TicketResponse.from(ticketService.assign(id, request.developerId()));
    }
}
