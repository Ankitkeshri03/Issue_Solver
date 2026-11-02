// Only explicit QA verification marks a tracked issue solved.
export function buildIssueRows(issues, tickets) {
  const rows = new Map();
  const pullRequests = new Set(issues.filter((issue) => issue.pull_request).map((issue) => issue.number));
  for (const issue of issues) {
    if (!issue.pull_request) rows.set(issue.number, { number: issue.number, issue });
  }
  for (const ticket of tickets) {
    if (ticket.githubPullRequest || pullRequests.has(ticket.githubIssueNumber)) continue;
    const row = rows.get(ticket.githubIssueNumber) || { number: ticket.githubIssueNumber };
    // Keep a single card when a source issue has already been imported.
    if (!row.ticket || ticket.id > row.ticket.id) row.ticket = ticket;
    rows.set(row.number, row);
  }
  return [...rows.values()].map((row) => ({
    ...row,
    title: row.issue?.title || row.ticket?.title,
    solved: row.ticket?.status === "RESOLVED",
  })).sort((a, b) => b.number - a.number);
}
