package com.gitissuesolver.backend.issue;

import com.gitissuesolver.backend.auth.CustomUserDetails;
import com.gitissuesolver.backend.auth.UserRepository;
import com.gitissuesolver.backend.github.ProjectAccess;
import com.gitissuesolver.backend.issue.dto.AssignTicketRequest;
import com.gitissuesolver.backend.issue.dto.CreateTicketRequest;
import com.gitissuesolver.backend.issue.dto.TicketResponse;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final ProjectAccess access;
    private final TicketActivityRepository activities;
    private final UserRepository users;

    public TicketController(TicketService ticketService, ProjectAccess access, TicketActivityRepository activities, UserRepository users) {
        this.ticketService = ticketService;
        this.access = access;
        this.activities = activities;
        this.users = users;
    }

    @GetMapping
    public List<TicketResponse> list(@RequestParam(required = false) Long repoId,
                                      @RequestParam(required = false) Long assignedToId,
                                      @AuthenticationPrincipal CustomUserDetails principal) {
        return ticketService.list(repoId, assignedToId, principal.getUser().getId()).stream()
                .map(t -> response(t, principal.getUser().getId())).toList();
    }

    @GetMapping("/{id}")
    public TicketResponse get(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails principal) {
        return response(access.ticket(id, principal.getUser().getId()), principal.getUser().getId());
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest request,
                                                  @AuthenticationPrincipal CustomUserDetails principal) {
        Ticket ticket = ticketService.create(request, principal.getUser().getId());
        return ResponseEntity.ok(response(ticket, principal.getUser().getId()));
    }

    @PostMapping("/{id}/assign")
    public TicketResponse assign(@PathVariable Long id, @Valid @RequestBody AssignTicketRequest request,
                                 @AuthenticationPrincipal CustomUserDetails principal) {
        return response(ticketService.assign(id, request.developerId(), principal.getUser().getId()), principal.getUser().getId());
    }

    public record PriorityRequest(@NotNull Priority priority) {}
    @DeleteMapping("/{id}/assignment")
    public TicketResponse unassign(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        return response(ticketService.unassign(id, user.getUser().getId()), user.getUser().getId());
    }
    public record QaRequest(@NotBlank @Size(max = 6000) String notes) {}
    public record ReportRequest(@NotBlank @Size(max = 6000) String reproductionSteps,
                                @NotBlank @Size(max = 6000) String expectedBehavior,
                                @NotBlank @Size(max = 6000) String actualBehavior, @NotNull Severity severity) {}
    @PutMapping("/{id}/priority")
    public TicketResponse priority(@PathVariable Long id, @Valid @RequestBody PriorityRequest body, @AuthenticationPrincipal CustomUserDetails user) {
        return response(ticketService.setPriority(id, body.priority(), user.getUser().getId()), user.getUser().getId());
    }
    @PutMapping("/{id}/report")
    public TicketResponse report(@PathVariable Long id, @Valid @RequestBody ReportRequest body, @AuthenticationPrincipal CustomUserDetails user) {
        return response(ticketService.updateReport(id, body.reproductionSteps(), body.expectedBehavior(), body.actualBehavior(), body.severity(), user.getUser().getId()), user.getUser().getId());
    }
    @PostMapping("/{id}/verify")
    public TicketResponse verify(@PathVariable Long id, @Valid @RequestBody QaRequest body, @AuthenticationPrincipal CustomUserDetails user) {
        return response(ticketService.verify(id, body.notes(), user.getUser().getId()), user.getUser().getId());
    }
    @PostMapping("/{id}/reopen")
    public TicketResponse reopen(@PathVariable Long id, @Valid @RequestBody QaRequest body, @AuthenticationPrincipal CustomUserDetails user) {
        return response(ticketService.reopen(id, body.notes(), user.getUser().getId()), user.getUser().getId());
    }
    @GetMapping("/{id}/activity")
    public List<ActivityView> activity(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        access.ticket(id, user.getUser().getId());
        return activities.findByTicketIdOrderByIdAsc(id).stream().map(item -> new ActivityView(item.getId(),
                users.findById(item.getActorUserId()).orElseThrow().getUsername(), item.getAction(), item.getMessage(), item.getCreatedAt())).toList();
    }
    public record ActivityView(Long id, String actorUsername, String action, String message, java.time.Instant createdAt) {}
    private TicketResponse response(Ticket ticket, Long actor) {
        return TicketResponse.from(ticket, actor, access.role(ticket.getRepo().getProjectId(), actor),
                access.canAssign(ticket.getRepo().getProjectId(), actor));
    }
}
