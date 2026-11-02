# AI Software Engineering Agent — Roadmap

## Product scope

A team reports an issue, a manager assigns a developer, and the developer uses an
AI agent to propose a tested pull request. A human reviews the code; QA retests the
behavior before the issue is marked solved. Passing existing tests is evidence,
not a measured fix-accuracy score.

See [README](README.md) for capabilities, [RUNNING](RUNNING.md) for startup, and
[team workflow](docs/team-workflow.md) for roles, onboarding and upgrade migrations.
The [improvements overview](docs/improvements.md) maps delivered changes to source
files and records the latest completed validation separately from future work.
The original two-role/four-screen MVP has been extended with workspace and project
membership. Historical build notes remain in `docs/build-plan.html`.

## Four roles and their scope

| Role | Scope | Responsibilities |
| --- | --- | --- |
| Workspace Admin | Workspace | Create projects, invite/remove members, manage access and GitHub connections, appoint managers |
| Project Manager | Project | Invite/manage project QA and developers, triage issues, set priority, assign/reassign/unassign work |
| QA | Project | Report reproduction steps, expected/actual behavior and severity; verify fixes or reopen issues |
| Developer | Project | Analyze and implement assigned issues with AI; review changes, tests and PRs |

Roles belong to memberships. One person may have different roles in different
projects. The operator provisions companies and invites their first admin. New
users activate through email-bound links and choose their own passwords. Public
signup is closed. Admins need an explicit Project Manager/QA/Developer project
membership for those specialist actions.

## Implemented foundation

- [x] JWT sign-in by username/email and email-based password recovery.
- [x] Workspace/project membership with backend permission checks.
- [x] Private operator provisioning and invitation-only account activation.
- [x] Project-manager delegation for QA/developer membership within managed projects.
- [x] Single-use, email-bound invitations with revocation and optional email delivery.
- [x] Admin-managed GitHub connections; credentials are not returned to team members.
- [x] GitHub issue import with PR filtering and repair of mistakenly imported PR tickets.
- [x] QA reports, severity, manager-set priority and project-scoped developer assignment.
- [x] Java indexing, embeddings and retrieval using PostgreSQL/pgvector.
- [x] AI planning, developer plan approval, coding and Maven/Gradle testing.
- [x] Bounded test/fix retries, candidate branch commits, push and PR creation.
- [x] Persisted live progress with keep-alives, reconnection, snapshot recovery and
      interrupted-run handling after a backend restart.
- [x] Code diff, test output and PR review links.
- [x] QA verification/reopening and issue activity history.
- [x] One-command startup/shutdown and automated CI checks.

## Current technology and language support

| Layer | Implementation |
| --- | --- |
| Frontend | React, Vite, Tailwind, React Router |
| Backend | Spring Boot 4.1, Java 21, Spring Security, JPA |
| AI service | Python/FastAPI, LangChain model/embedding clients, plain Python workflow |
| Database | PostgreSQL 16 with pgvector |
| Integrations | GitHub REST/Git, Gmail HTTPS API or explicitly configured SMTP |

The agent indexes and edits Java source. It detects Maven or Gradle builds up to
three directories below the repository root and prefers committed wrappers.
Groovy/Kotlin Gradle build scripts are supported; Groovy/Kotlin source editing is
not implemented. Independent builds in one repository are not all tested.

## User-facing screens

Workspace dashboard; workspace/team administration; project/repository pages;
invitation acceptance; issue detail with role-specific controls; live agent progress;
diff/PR review; shared sign-in, invitation activation and password recovery.

## Issue lifecycle

```text
QA reports GitHub issue → Manager prioritizes and assigns
    → Assigned developer analyzes → Reviews AI plan → Approves implementation
    → Candidate branch → Code → Maven/Gradle tests → Commit/push → PR
    → Human code review and merge → QA retests → Solved or reopened
```

```text
OPEN → ANALYZING → PLAN_READY → PLAN_APPROVED → IMPLEMENTING
    → TESTING → PR_CREATED → AWAITING_QA → RESOLVED
```

Failures become `FAILED` and can be retried by the assigned developer. QA reopening
returns the issue to `OPEN` and preserves its saved plan/history. A merged PR or
closed GitHub issue becomes `AWAITING_QA`, not automatically `RESOLVED`. GitHub
synchronization happens when a project member opens/refreshes the repository page;
the app does not merge PRs or close GitHub issues.

## Communication and source layout

React calls authenticated Spring REST endpoints under `/api/workspaces`,
`/api/projects`, `/api/repos` and `/api/tickets`. Spring checks membership and action
permissions, calls the Python AI service, and publishes saved progress over SSE.
The AI service retrieves relevant Java files, invokes the model, runs the detected
build, and uses the project's repository credential to publish a candidate PR.

New-account invitation preview/activation uses token-bound requests under
`/api/auth/invitations`. Existing users accept invitations while signed in. Private
company provisioning uses `/api/operator/companies` with the operator key; an admin
login alone cannot create another company. Accounts all share `/login`.

```text
frontend/src/             UI, API client, role/status helpers and tests
backend/src/main/java/   auth/, workspace/, github/, issue/, agent/, config/
backend/scripts/         company provisioning, Gmail setup and helper tests
ai-service/              agents/, tools/, rag/, workflow.py, tests/
database/                init.sql and ordered upgrade migrations
scripts/                 local service lifecycle and safety tests
.github/workflows/       application CI and candidate test workflow
```

## Verification and remaining work

Backend tests cover permissions, tenant isolation, invitation expiry/replay/concurrent
acceptance, membership changes versus agent startup, migration preservation, issue
lifecycle, authentication/recovery and live progress. Frontend tests cover issue
classification and progress recovery. Java build fixtures exercise failing, filtered
and corrected Maven/Gradle suites. CI uses disposable data and fake external services.
Onboarding checks also cover first-admin activation, preservation of existing-account
passwords, blocked public signup, manager delegation and single-use activation under
concurrent requests. See the [recorded verification snapshot](docs/improvements.md#verification-snapshot-and-limits)
for test counts and their scope.

- [ ] Require or generate targeted regression tests for the reported bug, beyond
      merely passing a repository's existing suite.
- [ ] Evaluate verified fix rate and retrieval quality against a documented dataset.
- [ ] Track model usage and cost.
- [ ] Add a controlled execution sandbox for connected repository build/test commands.
- [ ] Add source-language support beyond Java.

Manual issue priority is implemented; automatic priority prediction is future work.
Public deployment is a separate, explicitly authorized task. Docker image builds and
actual email inbox delivery must be verified independently of passing application tests.
