import test from "node:test";
import assert from "node:assert/strict";
import { buildIssueRows } from "./repoIssues.js";

const issue = { number: 1, title: "Original bug", state: "open" };
const ticket = { id: 8, githubIssueNumber: 1, title: "Original bug", status: "PR_CREATED" };

test("a GitHub issue and its ticket appear once, awaiting review stays active", () => {
  const rows = buildIssueRows([issue], [ticket]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].ticket.id, 8);
  assert.equal(rows[0].solved, false);
});
test("pull requests and their mistakenly imported tickets never appear as issues", () => {
  const rows = buildIssueRows([issue, { number: 2, title: "Fix issue #1", pull_request: {} }],
    [ticket, { id: 9, githubIssueNumber: 2, title: "Fix issue #1", status: "OPEN" }]);
  assert.deepEqual(rows.map((row) => row.number), [1]);
});
test("stored PR markers are respected even without a GitHub response", () => {
  assert.equal(buildIssueRows([], [{ ...ticket, githubPullRequest: true }]).length, 0);
});
test("closed source issues still require QA verification; only verified tickets are solved", () => {
  assert.equal(buildIssueRows([{ ...issue, state: "closed" }], [])[0].solved, false);
  assert.equal(buildIssueRows([issue], [{ ...ticket, status: "RESOLVED" }])[0].solved, true);
  assert.equal(buildIssueRows([issue], [{ ...ticket, status: "AWAITING_QA" }])[0].solved, false);
});
test("failed tickets remain active and visible if GitHub is unavailable", () => {
  const rows = buildIssueRows([], [{ ...ticket, status: "FAILED" }]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].solved, false);
});
test("duplicate imported records produce one card using the latest ticket", () => {
  const rows = buildIssueRows([issue], [{ ...ticket, id: 9 }, ticket]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].ticket.id, 9);
});
