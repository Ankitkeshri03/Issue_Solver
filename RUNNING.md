# Running the project

Four services: PostgreSQL in Docker, plus backend, AI service, and frontend.
Use `./start.sh` to run them in the background, or the manual alternative below.

For a summary of the changes and their source files, see
[implemented improvements](docs/improvements.md). For a new company, configure the
operator key below, start the services, then follow [company onboarding](#company-onboarding).

---

## One-time setup (keys)

AI secrets live in `ai-service/.env`; backend mail/auth settings live in
`backend/.env`. Never put real credentials in tracked `.env.example` files.

```bash
cd ~/Desktop/Git_Issue_solver/ai-service
cp .env.example .env      # only if .env doesn't exist yet
```

Then edit `.env`:

```ini
DATABASE_URL=postgresql://gitissuesolver:gitissuesolver@localhost:5433/gitissuesolver
GEMINI_API_KEY=<your key from https://aistudio.google.com/apikey>
GITHUB_TOKEN=<fallback token, only used for the local dummy repo>
MOCK_LLM=false            # true = deterministic offline mode, no API key needed
WORKSPACE_DIR=../workspace
```

Optional tuning (all have working defaults, override only if needed):

```ini
GEMINI_CHAT_MODEL=models/gemini-flash-lite-latest
GEMINI_EMBED_MODEL=models/gemini-embedding-001
LLM_MAX_RETRIES=5          # retries on Gemini 429 quota errors
MAX_FILES_TO_EDIT=3        # files the coding agent rewrites per attempt
EMBED_BATCH_SIZE=50
```

**Workspace admins manage GitHub connections from the project page.** An admin saves
their own GitHub token, then connects repositories to a project. QA, managers, and
developers use that connection through backend-authorized actions; API responses do
not expose its token. The `.env` token is a fallback for local AI-service fixtures,
not a substitute for workspace membership or a team repository connection.

First-time only, for frontend deps:
```bash
cd ~/Desktop/Git_Issue_solver/frontend && npm install
```

Before provisioning the first company, configure the private operator key from the
project root. This preserves existing backend settings and prints no secret:

```bash
cd /Users/ankit/Desktop/Git_Issue_solver
python3 backend/scripts/provision_company.py --configure
```

The key is saved to ignored `backend/.env` with owner-only permissions. An empty
`COMPANY_PROVISIONING_KEY` disables provisioning. If the backend was already running,
stop and start the app to load the new setting before provisioning. Keep this key
out of frontend `VITE_*` variables.

---

## Start everything with one command

Prerequisites: Docker Desktop (or Docker Engine + Compose v2 on Linux), JDK 21,
Python 3.11 or 3.12, Node.js 22+ with npm, and `lsof`. macOS also needs the Xcode
Command Line Tools for Git and the Maven wrapper. Secrets belong in `backend/.env`
and `ai-service/.env`; the scripts preserve both files.

```bash
cd /Users/ankit/Desktop/Git_Issue_solver
./start.sh
```

The launcher selects JDK 21 on macOS, creates the AI virtual environment if missing,
installs missing dependencies, starts Docker Desktop when supported, and starts the
PostgreSQL service with its existing named volume. It waits for a real database
connection and pgvector, then starts the AI service, backend and frontend. Initial
package and Maven downloads can take a few minutes.

Open **http://localhost:5173**. Services run in the background, so one terminal is
enough. Each service must pass its HTTP health check before startup reports success.
Re-running `./start.sh` reuses healthy project services instead of creating duplicates.
A process using a required port from another folder is reported and left alone.

For an existing database, apply the ordered [upgrade migrations](docs/team-workflow.md#existing-installations)
before starting this version. Fresh Docker databases load the current schema on first
initialization; changing `init.sql` does not upgrade an existing database volume.

After startup, the operator provisions a company and invites its first admin using
`python3 backend/scripts/provision_company.py` (see the guide below). The admin
creates projects, appoints managers and connects repositories; managers invite
project contributors, assign issues and set priority; QA reports and
verifies; assigned developers run the AI agent. Follow the complete
[team workflow](docs/team-workflow.md).

Existing services started manually from this project's service folders are registered
with the launcher; `./stop.sh` can then stop those project processes too. If an existing
project service is unhealthy, check its terminal or stop it before retrying. Failure
during startup stops newly launched app processes and leaves the database data intact.

Runtime state and private logs are in ignored `.run/`:

```bash
python3 scripts/dev.py status
tail -f .run/logs/backend.log
tail -f .run/logs/ai.log
tail -f .run/logs/frontend.log
```

Dependency installation output is in `.run/logs/dependencies.log`; database startup
output is in `.run/logs/postgres.log`. A reused manual process continues logging to
its original terminal, not a new launcher log. The default local ports are fixed:
PostgreSQL 5433, AI 8000, backend 8081, frontend 5173. Keep your local configuration
consistent with these ports. Stop and start again after changing backend `.env`.

If Docker's management API hangs but your configured PostgreSQL + pgvector responds,
the launcher reuses that database with a clear notice. It cannot safely manage an
unverified container, so stopping leaves that database running. It never restarts
Docker Desktop or removes a volume to resolve a timeout. If the database is also
unavailable, it exits with instructions after bounded waits. On Linux without Docker
Desktop, start Docker Engine normally before running the script; it does not use sudo.

## Stop

```bash
./stop.sh
# Or leave PostgreSQL available:
./stop.sh --keep-db
```

Stop uses verified saved process IDs and their child processes. It stops only this
project's tracked services and, when Docker can identify it, its PostgreSQL container.
It preserves accounts, repositories, tokens, database volumes, logs and all `.env`
files. Docker Desktop itself and unrelated containers/processes are left running.
Repeated stop commands are safe. Do not delete `.run/state.json` while services run,
as that file is how the launcher knows which processes belong to this project.

## Company onboarding

With the operator key loaded, the app running, and the email sender configured,
run from the project root with the actual company name and first-admin email:

```bash
python3 backend/scripts/provision_company.py \
  --company "Example Startup" \
  --admin-email admin@example.com
```

The command requests an email invitation. Add `--no-email` to create only a private
link. Results go to an owner-only file under ignored `.run/company-invitations/`;
the link is not printed. Failed email delivery still leaves a link that can be
shared privately with the intended admin.

The admin opens the link, chooses their own password if new, and signs in through
the common login page. Existing users sign in to accept without changing their
password. The admin creates projects and appoints managers; managers invite QA and
developers only into their managed projects. There is no public signup or shared
company password. See [the team guide](docs/team-workflow.md) for reissuing expired
first-admin links, role boundaries, and the QA workflow.

## Manual startup (alternative)

Use separate terminals if you prefer to see live logs:

```bash
# Project root:
docker compose up -d postgres
# ai-service/:
venv/bin/python -m uvicorn main:app --reload --port 8000
# backend/ (with JDK 21):
./mvnw spring-boot:run
# frontend/:
npm run dev
```

## Automated CI

[CI workflow](.github/workflows/ci.yml) runs on every branch push, pull request and
manual workflow dispatch after the file is committed and pushed. Its independent
jobs run:

- Java 21 backend tests and Maven packaging, against a disposable pgvector/PostgreSQL
  database named `gitissuesolver_test`. Failed test reports are retained for seven days.
- Python 3.11 AI tests, including real Maven and Gradle 8.10 failing/filter/fix smoke
  tests in temporary repositories, plus an AI application import check.
- A locked `npm ci`, frontend production build, live-progress recovery tests and issue-list tests using Node 22.
- Local lifecycle safety tests, Gmail OAuth helper tests, and company-provisioning
  script tests with fake credentials/requests.

CI uses read-only repository access, does not fetch the workspace gitlinks, and needs
no Gmail, Gemini, GitHub PAT or deployment secrets. It does not deploy, send email,
create PRs or modify your application database. New pushes cancel an older CI run on
the same branch. The separate `issue-solver-tests.yml` remains available for testing
AI-generated candidate commits; it is not the application's push/PR CI pipeline.

Run the script safety checks locally without changing running application services:

```bash
python3 -m unittest discover -s scripts/tests -v
python3 -m unittest discover -s backend/scripts -p 'test_*.py' -v
```

## Email login and password recovery

Sign in with your username or registered email. To enable **Forgot password?**,
follow [Gmail API setup](docs/gmail-api-setup.md) and run the one-time authorization
helper. It saves credentials in ignored `backend/.env`; restart the backend afterward.
The provider is `gmail_api` and delivery uses HTTPS. No Gmail password or App Password
is needed. Google OAuth Testing refresh tokens expire after seven days; the guide
explains the remaining publishing requirements before unattended public use.

Unknown emails show **No account found** and ask the person to request an invitation; existing emails
receive a link that must match their email before a new password is accepted.
Recovery stays disabled until `PASSWORD_RESET_ENABLED=true`. See
[password recovery](docs/password-recovery.md) for migrations and verification.

## Java project requirements

Connected repositories can use Maven (`pom.xml`) or Gradle (`build.gradle`,
`build.gradle.kts`, `settings.gradle`, or `settings.gradle.kts`). The AI service
selects the shallowest build within three directory levels and runs its `test` task.
Gradle multi-project builds are run from the directory containing their settings file.

Install a JDK compatible with the connected project on the AI-service host. Commit
`mvnw` plus `.mvn/wrapper/`, or `gradlew` plus `gradle/wrapper/`, to use the project's
chosen tool version. If a wrapper is absent, install `mvn` or `gradle` on that host.
Wrappers without an executable bit are launched with `sh`. The Docker image includes
Maven; use a committed Gradle wrapper for Gradle projects in Docker. First-time wrapper
downloads and dependency resolution require network access.

This adds Gradle builds for Java source; Kotlin/Groovy source files are not indexed.

## Repository issues

The repository page shows each GitHub issue once, under **Active** or **Solved**.
Pull requests are excluded and cannot be imported as tickets. **Awaiting review**
means a proposed fix exists. Merging the PR or closing the GitHub issue moves it
to **Awaiting QA verification**; only QA verification marks it **Solved**.
Project members can use **Refresh** to synchronize GitHub states.
Reopening a closed GitHub issue returns its ticket to active work.

Existing PRs mistakenly imported as tickets are retained for history and excluded
from issue lists after the repository is synchronized. Existing installations must
apply `database/migrations/003_issue_sources.sql` before starting this version;
the migration adds two columns with safe defaults and does not delete tickets.

The four-role team workflow also requires `database/migrations/004_workspace_roles.sql`
and `database/migrations/005_company_onboarding.sql` for existing databases. See [team setup and migration](docs/team-workflow.md).

## Troubleshooting

Live progress opens immediately, sends keep-alive frames, reconnects after transport
errors and checks saved ticket/step snapshots every five seconds during a run. A finished
analysis shows **analysis complete** and a failed run shows **failed**, rather than an
ambiguous disconnected badge. Missed updates are recovered after reconnecting.
Database sessions are scoped to short repository operations, not to the lifetime of
the stream. Local startup marks interrupted runs as failed so they can be retried;
it preserves issue data and saved plans and does not automatically create commits or PRs.
This recovery is disabled by default in `prod`; `RECOVER_INTERRUPTED_JOBS=true` is
appropriate only when a single backend owns all jobs.

| Symptom | Cause | Fix |
|---|---|---|
| `Failed to fetch` in browser | backend not running | run `./start.sh` |
| `Port 8081 was already in use` | old backend still alive | run `./stop.sh --keep-db`, then `./start.sh` |
| App `401`/`403`, or project `404` | expired session, missing membership, or role not allowed for the action | Sign in again if needed. Ask the workspace admin to check project membership; only the assigned developer can start agent work. Request a new invitation if your membership was removed. |
| GitHub push `403` during implementation | GitHub refuses the saved credential's write access | Include the target repository in GitHub token access and grant Contents and Pull requests **Read and write**. If replacing the token, a workspace admin must open the project → GitHub connection → **Save token**, then **Re-sync token** on the repository. Retry implementation after checking any organization approval or repository rules. |
| A merged fix is still Active | QA verification is pending | QA opens the issue, records retest notes, then uses **Verify & mark solved** or **Reopen for developer**. |
| Developer missing from assignment list | not a Developer member of this project | A workspace admin or that project’s manager adds the person as Developer; an account alone does not grant project access. |
| `429 ... quota` in a step message | Gemini free-tier limit (per-minute) | wait ~1 min; it auto-retries with backoff |
| `No Maven or Gradle project found` | build files are absent or more than 3 levels deep | place the Maven/Gradle build root within the supported search depth |
| `Could not start maven/gradle` | no usable wrapper or installed tool | commit the complete wrapper or install the corresponding tool on the AI-service host |
| Wrapper download or Java version failure | network unavailable or incompatible JDK | allow distribution/dependency downloads and use the project's required JDK |
| GitHub rate limit error | token missing/invalid or GitHub quota exhausted | A workspace admin checks the project's GitHub connection; wait if GitHub has exhausted that credential's quota. |

Errors now surface with their real cause in the UI step timeline — if you see a bare
"Internal Server Error", check `uvicorn`'s terminal output for the traceback.
