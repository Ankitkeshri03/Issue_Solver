# Company onboarding, projects, and team roles

Accounts are identities. Workspace and project memberships determine access on every
backend request. Everyone uses the same login page. Public registration and
self-service workspace creation are disabled; existing accounts keep their passwords
and memberships. The legacy account role is not an authorization source.

For the full list of delivered improvements and the relevant implementation files,
see [implemented improvements](improvements.md).

| Role | Scope | Responsibilities |
| --- | --- | --- |
| Workspace Admin | Company workspace | Create projects, invite/remove members, appoint managers, manage access and GitHub connections |
| Project Manager | Managed project | Invite/add/remove QA and developers, review issues, set priority, assign/reassign/unassign developers |
| QA | Project | Import/report GitHub issues, record reproduction/expected/actual behavior and severity, retest and verify/reopen fixes |
| Developer | Project | Analyze/implement assigned issues with AI, review changes, tests and generated PRs |

A person can hold different roles in different projects. Workspace admin access is
separate from the project role. Admins must explicitly join a project as Project
Manager to assign work, QA to verify, or Developer to run assigned agent work.
Managers cannot appoint managers/admins, change those members' access, or manage
another project's team. Removing a project membership leaves other projects intact.
Active developer jobs must finish before reassignment or access changes.

## Provision a company

The operator sets up each company and its first admin using a private CLI. This is
not another role users can choose at signup. From the project root, configure once:

```sh
python3 backend/scripts/provision_company.py --configure
```

This generates `COMPANY_PROVISIONING_KEY` in ignored `backend/.env` with owner-only
permissions. Restart the backend to load it. Keep this secret in backend configuration;
never put it in frontend `VITE_*` variables. An empty key disables provisioning.

With the backend running and the Gmail sender configured, provision a company:

```sh
python3 backend/scripts/provision_company.py \
  --company "Example Startup" \
  --admin-email admin@example.com
```

Replace the example name/email with the actual company and its first admin. The
command requests delivery through the existing Gmail API/SMTP sender. Use `--no-email`
when you want only a private activation link. It creates no shared or generated
password. Private results are saved with owner-only permissions under ignored
`.run/company-invitations/`; the key and activation link are never printed.
If delivery is not confirmed, share the saved link only with its intended recipient.
No deployment is performed.

The company remains pending and inaccessible until the invited admin activates it.
The link is bound to that email, expires after seven days, and can be used once.
A new user chooses their own username/password; an existing user signs in and
accepts without changing their password. After activation everyone uses `/login`.
Only token hashes are stored in the database; links use URL fragments.

The activation page displays the company, optional project, invited email, role and
expiry. It offers a username/password form only for a new account. Existing-account
invitations offer sign-in and acceptance. Recipients cannot change the invited role,
claim a different email, or use an invitation to reset an existing password.

To replace an expired/unaccepted first-admin invitation, use the returned company ID:

```sh
python3 backend/scripts/provision_company.py \
  --reissue 7 \
  --admin-email admin@example.com
```

Reissuing invalidates earlier first-admin links and is blocked after activation.
If an initial request times out, preserve its private request record. Do not create
another company blindly: the operator can find its ID by `provisioning_request_id`
in `workspaces`, then reissue if it is still pending. Reusing `--request-id` prevents
the same initial request creating a second company.

## Getting a team started

1. The invited admin activates their account and signs in.
2. In **Manage workspace & team**, the admin creates projects and appoints or invites
   Project Managers to the relevant projects. Admin invitations apply to the workspace.
3. Each manager opens their project to add existing company members or invite QA
   members and developers by email. Roles are assigned by the invitation; recipients
   cannot choose a higher role while activating. Managers can list/revoke contributor
   invitations in their own projects; admins manage all workspace invitations.
4. The admin connects GitHub on the project page and selects repositories. Team
   members do not receive the stored GitHub credential in API responses.
5. QA reports/imports issues; managers prioritize and assign/unassign developers;
   assigned developers run the agent and review its changes; QA retests fixes.

A pending invitation stops working if its creator loses the necessary access.
To replace an ordinary expired team invitation, the authorized admin/manager creates
a new one in the same project. Revoke any still-valid old invitation first. The
operator's `--reissue` command is specifically for the pending first company admin.
Email is selected by default in the UI; it can be unchecked to create only a private
link. Failed delivery does not discard the invitation. The same configured sender
serves password recovery and invitations.

Replacing an admin's GitHub token requires **Save token**, then **Re-sync token** on
each connected repository. Editing permissions on the same GitHub token does not
require replacing its value in the app.

A dedicated QA team is optional as an organizational choice. The current app still
requires a QA project member for reporting/importing and final verification. Removing
that role would first require moving those actions to another role; GitHub storing an
issue does not establish that a fix works.

## Issue lifecycle

QA selects a real GitHub issue and records reproduction steps, expected and actual
behavior, and severity. A manager sets priority and assigns a developer from that
project. The assigned developer analyzes the issue, reviews the AI plan, and runs
implementation. Existing Maven/Gradle tests and the PR creation workflow still apply.

**PR created** means **Awaiting review**. A merged linked PR or closed GitHub issue
moves the ticket to **Awaiting QA verification**, not Solved. QA records retest notes
and either verifies it (Solved) or reopens it for the developer. Reopening preserves
the prior plan and PR history; a still-closed GitHub issue cannot automatically undo
the QA reopening. Assignment, priority changes, report updates, and QA decisions are
recorded in issue activity.

GitHub issue/PR synchronization runs when a project member opens or refreshes the
repository page. The app does not merge PRs, close GitHub issues, or claim verification
merely because existing tests passed. Reopened GitHub issues return to active work.

## Existing installations

Fresh databases initialized by Docker use `database/init.sql`, which includes the
workspace-role and company-onboarding migrations through psql's `\ir` command. Keep the migrations directory
beside that file when using psql; Compose mounts it automatically. Existing volumes
are not reinitialized when `init.sql` changes.

For an existing installation, stop application services while keeping PostgreSQL
running, and take a private backup. Run these from the project root:

```sh
./stop.sh --keep-db
mkdir -p .run/backups
chmod 700 .run/backups
migration_backup=".run/backups/before-team-roles-$(date +%Y%m%d-%H%M%S).dump"
(umask 077; docker exec gitissuesolver-postgres pg_dump -Fc -U gitissuesolver -d gitissuesolver > "$migration_backup")
```

Confirm the backup command succeeded before continuing. Apply only migrations not
already completed, in number order. An installation on migrations 001–003 needs
004 and 005; the original schema needs all five commands below. An installation
already on the four-role version needs only 005.

```sh
docker exec -i gitissuesolver-postgres psql -v ON_ERROR_STOP=1 -U gitissuesolver -d gitissuesolver < database/migrations/001_password_recovery.sql
docker exec -i gitissuesolver-postgres psql -v ON_ERROR_STOP=1 -U gitissuesolver -d gitissuesolver < database/migrations/002_repository_ownership.sql
docker exec -i gitissuesolver-postgres psql -v ON_ERROR_STOP=1 -U gitissuesolver -d gitissuesolver < database/migrations/003_issue_sources.sql
docker exec -i gitissuesolver-postgres psql -v ON_ERROR_STOP=1 -U gitissuesolver -d gitissuesolver < database/migrations/004_workspace_roles.sql
docker exec -i gitissuesolver-postgres psql -v ON_ERROR_STOP=1 -U gitissuesolver -d gitissuesolver < database/migrations/005_company_onboarding.sql
```

Run each command only after the previous command succeeds. For a database outside
Docker, use `psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f <migration-file>` with a
PostgreSQL URL, not a JDBC URL. Keep backups private because they contain account and
integration data. Once all migrations succeed, run `./start.sh` and reload the app.
These are upgrade steps, not commands to repeat on every normal startup.

Migration **004** gives each existing repository owner a workspace and makes them
its admin. Existing
repositories become projects; their QA reporters and assigned developers receive
the corresponding memberships. Passwords, tokens, tickets, saved plans, PR URLs,
and progress history are preserved. Previously solved tickets without recorded QA
verification become Awaiting QA verification. The migration records completion so
rerunning it cannot re-grant memberships an admin subsequently removed.

Migration **005** adds pending-company ownership, provisioning request IDs and
first-admin invitation metadata. It preserves existing accounts, passwords, owners,
project roles and tickets. Existing users continue with their current login; they
do not need to reactivate because public signup has closed.

Unowned legacy demo repositories stay inaccessible until explicitly connected by
an authorized admin. New databases load all five migrations from `database/init.sql`.

## Checking the implementation

Use a disposable test database, never the application database: the test profile
creates and drops its schema. With JDK 21 and a separate `gitissuesolver_test`
database available:

```sh
cd backend
TEST_DATABASE_URL=jdbc:postgresql://localhost:5433/gitissuesolver_test \
TEST_DATABASE_USERNAME=gitissuesolver TEST_DATABASE_PASSWORD=gitissuesolver \
./mvnw --batch-mode --no-transfer-progress verify
```

From `frontend/`, run `npm test` and `npm run build`. Backend tests cover role
boundaries, cross-workspace isolation, invitations, developer assignment, QA
verification/reopening, migration preservation and concurrent membership operations.
Company onboarding tests cover new-admin activation, existing-account password
protection, manager delegation and blocked self-service signup/workspace creation.
Automated tests use fake GitHub/mail/AI services and do not send invitation emails.

## API boundaries

Workspace and project management use `/api/workspaces` and `/api/projects`.
Invitation preview and new-account activation use token-bound POST requests under
`/api/auth/invitations`; existing-account acceptance uses authenticated
`/api/invitations/accept`. Operator provisioning uses `/api/operator/companies` and
a separately verified `X-Provisioning-Key`, never an admin JWT alone. Repository connections require a project ID; issue reads use
`GET /api/repos/{repoId}/issues`. The developer directory requires a project ID.
Ticket responses contain server-calculated action permissions; the UI uses those
permissions while all writes are independently authorized on the backend.

The app does not create or merge GitHub issues/PRs during membership changes.
Invitation email is requested through the operator CLI or an authorized admin/manager
invitation form. Tests use fake email services; they do not establish live delivery.
