package com.gitissuesolver.backend.agent;

import com.gitissuesolver.backend.agent.dto.ImplementResponse;
import com.gitissuesolver.backend.github.Repo;
import com.gitissuesolver.backend.issue.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Optional;

class AgentPublicationTests {
    @Test
    void rejectedUploadPreservesDiffAndPassedTestsButFailsTicket() {
        var tickets = mock(TicketRepository.class);
        var steps = mock(AgentStepRepository.class);
        var client = mock(AgentClient.class);
        var publisher = mock(AgentProgressPublisher.class);
        var repo = Repo.builder().id(4L).cloneUrl("https://github.com/example/project.git")
                .defaultBranch("main").githubToken("unused-test-token").build();
        var ticket = Ticket.builder().id(8L).repo(repo).githubIssueNumber(1).plan("plan").build();
        when(tickets.findByIdWithRepo(8L)).thenReturn(Optional.of(ticket));
        when(steps.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.implement(any())).thenReturn(new ImplementResponse(false, null, "saved diff", "Tests passed", 1,
                "GitHub refused write access", true));

        new AgentService(tickets, steps, client, publisher).implementAsync(8L);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.FAILED);
        assertThat(ticket.getPrUrl()).isNull();
        var captor = ArgumentCaptor.forClass(AgentStep.class);
        verify(steps, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(step -> {
            assertThat(step.getStepName()).isEqualTo("testing");
            assertThat(step.getStatus()).isEqualTo(AgentStepStatus.DONE);
            assertThat(step.getMessage()).contains("Tests passed");
        }).anySatisfy(step -> {
            assertThat(step.getStepName()).isEqualTo("diff");
            assertThat(step.getMessage()).isEqualTo("saved diff");
        }).anySatisfy(step -> {
            assertThat(step.getStepName()).isEqualTo("pull-request");
            assertThat(step.getStatus()).isEqualTo(AgentStepStatus.FAILED);
            assertThat(step.getMessage()).contains("write access");
        });
        verify(publisher).complete(8L);
    }
}
