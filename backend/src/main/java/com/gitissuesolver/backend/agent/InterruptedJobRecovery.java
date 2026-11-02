package com.gitissuesolver.backend.agent;

import com.gitissuesolver.backend.issue.TicketRepository;
import com.gitissuesolver.backend.issue.TicketStatus;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Local single-server jobs cannot survive a JVM restart. Preserve data and enable retry. */
@Component
@ConditionalOnProperty(name = "app.agent.recover-on-startup", havingValue = "true")
public class InterruptedJobRecovery implements ApplicationRunner {
    private final TicketRepository tickets;
    private final AgentStepRepository steps;
    public InterruptedJobRecovery(TicketRepository tickets, AgentStepRepository steps) {
        this.tickets = tickets; this.steps = steps;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var running = List.of(TicketStatus.ANALYZING, TicketStatus.PLAN_APPROVED,
                TicketStatus.IMPLEMENTING, TicketStatus.TESTING);
        for (var ticket : tickets.findByStatusIn(running)) {
            ticket.setStatus(TicketStatus.FAILED);
            tickets.save(ticket);
            steps.save(AgentStep.builder().ticketId(ticket.getId()).stepName("interrupted")
                    .status(AgentStepStatus.FAILED)
                    .message("The backend restarted before this run finished. Open the ticket and retry analysis or implementation. Existing issue data and any saved plan were preserved.")
                    .build());
        }
    }
}
