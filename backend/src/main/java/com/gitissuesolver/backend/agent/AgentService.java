package com.gitissuesolver.backend.agent;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.gitissuesolver.backend.agent.dto.AnalyzeRequest;
import com.gitissuesolver.backend.agent.dto.AnalyzeResponse;
import com.gitissuesolver.backend.agent.dto.ImplementRequest;
import com.gitissuesolver.backend.agent.dto.ImplementResponse;
import com.gitissuesolver.backend.issue.Ticket;
import com.gitissuesolver.backend.issue.TicketRepository;
import com.gitissuesolver.backend.issue.TicketStatus;

@Service
public class AgentService {

    private final TicketRepository ticketRepository;
    private final AgentStepRepository agentStepRepository;
    private final AgentClient agentClient;
    private final AgentProgressPublisher publisher;

    public AgentService(TicketRepository ticketRepository, AgentStepRepository agentStepRepository,
                         AgentClient agentClient, AgentProgressPublisher publisher) {
        this.ticketRepository = ticketRepository;
        this.agentStepRepository = agentStepRepository;
        this.agentClient = agentClient;
        this.publisher = publisher;
    }

    @Async
    public void analyzeAsync(Long ticketId) {
        Ticket ticket = ticketRepository.findByIdWithRepo(ticketId).orElseThrow();
        ticket.setStatus(TicketStatus.ANALYZING);
        ticketRepository.save(ticket);
        step(ticketId, "reading-issue", AgentStepStatus.DONE, "Read issue #" + ticket.getGithubIssueNumber());

        try {
            step(ticketId, "retrieval", AgentStepStatus.RUNNING, "Searching pgvector for relevant files...");
            AnalyzeResponse response = agentClient.analyze(new AnalyzeRequest(
                    ticket.getId(), ticket.getGithubIssueNumber(), ticket.getTitle(), issueDescription(ticket),
                    ticket.getRepo().getCloneUrl(), ticket.getRepo().getId(), ticket.getRepo().getGithubToken()));
            step(ticketId, "retrieval", AgentStepStatus.DONE,
                    "Found " + response.relevantFiles().size() + " relevant file(s): " + response.relevantFiles());

            step(ticketId, "planning", AgentStepStatus.DONE, response.plan());

            ticket.setPlan(response.plan());
            ticket.setStatus(TicketStatus.PLAN_READY);
            ticketRepository.save(ticket);
        } catch (Exception e) {
            step(ticketId, "analyze", AgentStepStatus.FAILED, e.getMessage());
            ticket.setStatus(TicketStatus.FAILED);
            ticketRepository.save(ticket);
        } finally {
            publisher.complete(ticketId);
        }
    }

    @Async
    public void implementAsync(Long ticketId) {
        Ticket ticket = ticketRepository.findByIdWithRepo(ticketId).orElseThrow();
        ticket.setStatus(TicketStatus.PLAN_APPROVED);
        ticketRepository.save(ticket);

        String branchName = "fix/issue-" + ticket.getGithubIssueNumber() + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        ticket.setBranchName(branchName);
        ticket.setStatus(TicketStatus.IMPLEMENTING);
        ticketRepository.save(ticket);
        step(ticketId, "coding", AgentStepStatus.RUNNING, "Editing files on branch " + branchName);

        try {
            ImplementResponse response = agentClient.implement(new ImplementRequest(
                    ticket.getId(), ticket.getGithubIssueNumber(), ticket.getTitle(), issueDescription(ticket),
                    ticket.getPlan(), ticket.getRepo().getCloneUrl(), ticket.getRepo().getId(),
                    branchName, ticket.getRepo().getDefaultBranch(), ticket.getRepo().getGithubToken()));

            step(ticketId, "coding", AgentStepStatus.DONE, "Code changes applied");
            if (response.diff() != null && !response.diff().isBlank()) {
                step(ticketId, "diff", AgentStepStatus.DONE, response.diff());
            }
            ticket.setStatus(TicketStatus.TESTING);
            ticketRepository.save(ticket);
            boolean testsPassed = response.testsPassed() != null ? response.testsPassed() : response.success();
            step(ticketId, "testing", testsPassed ? AgentStepStatus.DONE : AgentStepStatus.FAILED,
                    "Tests (attempts=" + response.attempts() + "): " + response.testOutput());

            if (response.success()) {
                ticket.setStatus(TicketStatus.PR_CREATED);
                ticket.setPrUrl(response.prUrl());
                ticketRepository.save(ticket);
                step(ticketId, "pull-request", AgentStepStatus.DONE, "PR created: " + response.prUrl());
            } else {
                ticket.setStatus(TicketStatus.FAILED);
                ticketRepository.save(ticket);
                step(ticketId, "pull-request", AgentStepStatus.FAILED, response.failureReason());
            }
        } catch (Exception e) {
            step(ticketId, "implement", AgentStepStatus.FAILED, e.getMessage());
            ticket.setStatus(TicketStatus.FAILED);
            ticketRepository.save(ticket);
        } finally {
            publisher.complete(ticketId);
        }
    }

    private void step(Long ticketId, String name, AgentStepStatus status, String message) {
        AgentStep step = agentStepRepository.save(AgentStep.builder()
                .ticketId(ticketId)
                .stepName(name)
                .status(status)
                .message(message)
                .build());
        publisher.publish(ticketId, step);
    }

    private String issueDescription(Ticket ticket) {
        return java.util.Objects.toString(ticket.getDescription(), "")
                + "\n\nReproduction steps:\n" + java.util.Objects.toString(ticket.getReproductionSteps(), "Not provided")
                + "\n\nExpected behavior:\n" + java.util.Objects.toString(ticket.getExpectedBehavior(), "Not provided")
                + "\n\nActual behavior:\n" + java.util.Objects.toString(ticket.getActualBehavior(), "Not provided")
                + "\n\nSeverity: " + ticket.getSeverity();
    }
}
