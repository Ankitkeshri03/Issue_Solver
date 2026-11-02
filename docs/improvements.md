# Implemented project improvements

Documentation updated on 2 November 2026. This describes the current implementation;
the historical MVP notes in `build-plan.html` and `file-manifest.html` describe an
earlier snapshot.

## Improvements and their purpose

| Area | What changed | Why it helps |
| --- | --- | --- |
| Java build support | Detect Maven and Gradle, prefer committed wrappers, find nested builds, and support Gradle settings files and both build-script DSLs. Test failures feed the agent's correction loop. | Connected Java repositories can use either build tool. |
| Local startup | `./start.sh` and `./stop.sh` manage the database and app services, check readiness, reuse healthy project processes, and save private logs. | The whole app can run from one terminal while preserving database data. |
| Continuous integration | GitHub Actions checks the backend, AI service, real Maven/Gradle fixtures, frontend, and setup/lifecycle scripts. | Pushes and pull requests receive repeatable checks without application secrets. |
| Login and recovery | One login accepts email or username. Password resets use email-bound, expiring, single-use links and invalidate old sessions after success. | Users can recover access while proving possession of the registered inbox. |
| Email transport | Gmail API sends recovery and invitation messages over HTTPS using the dedicated sender's OAuth authorization. | The application does not require the sender's Gmail password or an SMTP connection. |
| Company onboarding | An operator provisions a company and invites its first admin. Activation links expire after seven days; new users choose their own password. Public signup and self-service company creation are closed. | Each company starts with an identified admin and individual accounts. |
| Roles and isolation | Workspace Admin, Project Manager, QA, and Developer permissions come from workspace/project memberships and are checked by the backend. | An account or a role in one project does not grant access to another company/project. |
| Delegated team management | Admins appoint managers and control GitHub. Managers add/invite/remove QA and developers only in projects they manage. | Managers can maintain their teams without receiving company-wide administration access. |
| Issue management | Reports include reproduction steps, expected/actual behavior and severity. Managers set priority and assign/reassign/unassign developers. Actions are recorded in activity history. | The developer gets a clear report and a traceable assignment. |
| GitHub reliability | Push-access checks, clearer authorization failures, private repository credentials, and explicit token re-sync support the publishing workflow. | Credential problems can be identified before spending a run editing code. |
| Live progress | Saved steps, stream keep-alives, reconnect/poll recovery, and interrupted-run handling replace indefinite stale progress. | Refreshing or losing a connection does not erase saved progress or hide a stopped run. |
| Issue lists and verification | PRs are excluded from issue lists, duplicate issue cards are consolidated, and active/solved states follow the ticket lifecycle. QA verifies or reopens fixes. | A proposed PR or closed GitHub issue is not mistaken for a verified solution. |
| Guides and upgrades | Setup, onboarding, recovery, troubleshooting and ordered database migrations are documented, with backup instructions. | Existing installations can upgrade while preserving accounts, memberships and issue history. |

## How the roles work together

```text
Operator provisions company and invites its first admin
    → Admin activates, creates projects, appoints managers and connects GitHub
    → Managers invite/add QA and developers to their managed projects
    → Everyone signs in through the same login page

QA reports/imports a GitHub issue
    → Manager prioritizes and assigns a project developer
    → Developer analyzes, reviews the plan and runs implementation/tests
    → Candidate branch and PR → Human code review and merge
    → QA retests → Solved or reopened
```

Workspace admin access is separate from the project role. An admin needs an explicit
Project Manager membership to assign issues, a QA membership to verify fixes, or a
Developer membership to run agent work assigned to them. Managers cannot appoint or
demote managers/admins. Active developer jobs must finish before access or assignment
changes.

A separate QA team is optional as a company staffing decision. The current app still
uses the QA role for reporting/importing issues and final verification. Removing QA
would require assigning those actions to another role first; storing an issue on
GitHub does not verify its fix.

## Where the implementation lives

| Capability | Main source entry points |
| --- | --- |
| Maven/Gradle testing | [AI test agent](../ai-service/agents/test_agent.py), [runner tests](../ai-service/tests/test_build_runner.py), [build integration tests](../ai-service/tests/test_build_integration.py) |
| Startup and shutdown | [start.sh](../start.sh), [stop.sh](../stop.sh), [lifecycle manager](../scripts/dev.py) |
| CI | [application CI](../.github/workflows/ci.yml) |
| Login and password recovery | [auth service](../backend/src/main/java/com/gitissuesolver/backend/auth/AuthService.java), [reset service](../backend/src/main/java/com/gitissuesolver/backend/auth/PasswordResetService.java), [login page](../frontend/src/pages/Login.jsx) |
| Gmail and invitations | [Gmail sender](../backend/src/main/java/com/gitissuesolver/backend/auth/GmailApiEmailSender.java), [invitation mail service](../backend/src/main/java/com/gitissuesolver/backend/workspace/InvitationMailService.java), [OAuth setup helper](../backend/scripts/setup_gmail.py) |
| Company activation | [operator CLI](../backend/scripts/provision_company.py), [provisioning service](../backend/src/main/java/com/gitissuesolver/backend/workspace/CompanyProvisioningService.java), [activation API](../backend/src/main/java/com/gitissuesolver/backend/workspace/InvitationActivationController.java), [activation page](../frontend/src/pages/AcceptInvitation.jsx) |
| Memberships and team management | [workspace access rules](../backend/src/main/java/com/gitissuesolver/backend/workspace/WorkspaceAccess.java), [workspace service](../backend/src/main/java/com/gitissuesolver/backend/workspace/WorkspaceService.java), [project page](../frontend/src/pages/ProjectDetail.jsx) |
| Assignment, reporting and QA | [ticket service](../backend/src/main/java/com/gitissuesolver/backend/issue/TicketService.java), [ticket page](../frontend/src/pages/TicketDetail.jsx) |
| GitHub credentials and publishing | [repository controller](../backend/src/main/java/com/gitissuesolver/backend/github/RepoController.java), [Git tools](../ai-service/tools/git_tools.py) |
| Progress recovery | [progress publisher](../backend/src/main/java/com/gitissuesolver/backend/agent/AgentProgressPublisher.java), [interrupted-run recovery](../backend/src/main/java/com/gitissuesolver/backend/agent/InterruptedJobRecovery.java), [frontend progress helper](../frontend/src/lib/agentProgress.js) |
| Issue synchronization and lists | [issue sync service](../backend/src/main/java/com/gitissuesolver/backend/github/IssueSyncService.java), [issue-list helper](../frontend/src/lib/repoIssues.js) |
| Database upgrades | [ordered migration instructions](team-workflow.md#existing-installations), [migration files](../database/migrations/) |

## Verification snapshot and limits

The last completed onboarding validation recorded **65 backend tests**, **15 frontend
tests**, and **11 backend setup-script tests** passing, plus a successful frontend
production build. These are prior validation results, not a new test run performed
by this documentation update or a claim about remote GitHub Actions status.

Backend coverage includes company activation, invitation expiry/replay, existing
account password protection, cross-project permissions, manager delegation,
assignment, recovery, migration preservation and concurrent operations. Frontend
unit tests cover progress recovery and issue classification; they are not a browser
test of every screen. Setup-script tests use fake credentials and requests.

The AI runner also has regression tests and optional real Maven/Gradle smoke tests.
See [build support and test commands](../README.md#supported-projects-and-test-runners),
[CI and local script checks](../RUNNING.md#automated-ci), and
[backend verification](team-workflow.md#checking-the-implementation).

Current limits:

- Source editing/indexing supports Java. Gradle's Groovy/Kotlin DSL support does not
  add Groovy/Kotlin source-language support. One detected build is selected per run.
- The agent runs the selected repository's existing tests. Passing them does not
  prove full issue coverage, and no general fix-accuracy percentage has been measured.
- A GitHub push-access check cannot override token permissions, branch rules or PR
  restrictions. The app creates candidate PRs; people review and merge them.
- GitHub state synchronizes when a member opens/refreshes the repository page.
  QA verification remains a separate action.
- Automated email tests do not establish real inbox delivery. Company provisioning
  needs an actual company name and first-admin email; no shared password is generated.
- Deployment and Docker application-image verification are separate from the local
  setup and checks described here.

For the next operator steps, use [company provisioning](team-workflow.md#provision-a-company).
For remaining development work, use the [roadmap](../ROADMAP.md#verification-and-remaining-work).
