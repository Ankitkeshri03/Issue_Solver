package com.gitissuesolver.backend.github;

import com.gitissuesolver.backend.issue.*;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Synchronize source identity and resolution without modifying anything on GitHub. */
@Service
public class IssueSyncService {
    private static final Set<TicketStatus> RUNNING = Set.of(TicketStatus.ANALYZING, TicketStatus.PLAN_APPROVED,
            TicketStatus.IMPLEMENTING, TicketStatus.TESTING);
    private final GitHubService github;
    private final TicketRepository tickets;

    public IssueSyncService(GitHubService github, TicketRepository tickets) {
        this.github = github;
        this.tickets = tickets;
    }

    public List<GitHubIssueDto> sync(Repo repo) {
        var items = github.fetchRepositoryItems(repo.getGithubOwner(), repo.getGithubRepo(), repo.getGithubToken());
        Map<Integer, GitHubIssueDto> byNumber = items.stream().collect(Collectors.toMap(
                GitHubIssueDto::number, Function.identity(), (first, last) -> last));
        for (var ticket : tickets.findByRepoId(repo.getId())) {
            var item = byNumber.get(ticket.getGithubIssueNumber());
            if (item == null || RUNNING.contains(ticket.getStatus())) continue;
            boolean pullRequest = item.isPullRequest();
            boolean closed = !pullRequest && "closed".equals(item.state());
            var status = ticket.getStatus();
            if (!pullRequest) {
                if (closed && !ticket.isQaReopened() && status != TicketStatus.RESOLVED) status = TicketStatus.AWAITING_QA;
                else if (!closed && ticket.isGithubIssueClosed() && (status == TicketStatus.RESOLVED || status == TicketStatus.AWAITING_QA))
                    status = TicketStatus.OPEN;
                if (status == TicketStatus.PR_CREATED && linkedPrMerged(repo, ticket, byNumber))
                    status = TicketStatus.AWAITING_QA;
            }
            if (ticket.isGithubPullRequest() != pullRequest || ticket.isGithubIssueClosed() != closed
                    || ticket.getStatus() != status) {
                // A job may have started since the snapshot. Do not overwrite its
                // state, assignments, or saved plan with a detached entity merge.
                tickets.syncGitHubState(ticket.getId(), ticket.getStatus(), status, pullRequest, closed);
            }
        }
        return items.stream().filter(item -> !item.isPullRequest()).toList();
    }

    private boolean linkedPrMerged(Repo repo, Ticket ticket, Map<Integer, GitHubIssueDto> items) {
        String prefix = "https://github.com/" + repo.fullName() + "/pull/";
        if (ticket.getPrUrl() == null || !ticket.getPrUrl().startsWith(prefix)) return false;
        String suffix = ticket.getPrUrl().substring(prefix.length());
        if (!suffix.matches("[0-9]{1,9}")) return false;
        int number = Integer.parseInt(suffix);
        var item = items.get(number);
        // Closing an unmerged PR must never mark the original issue solved.
        return item != null && item.isPullRequest() && "closed".equals(item.state())
                && github.isPullRequestMerged(repo, number);
    }
}
