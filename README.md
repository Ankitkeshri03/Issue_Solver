# AI Software Engineering Agent

A team reports a GitHub issue, a manager assigns it, and a developer uses the AI
agent to propose a tested pull request. Code review and QA verification complete
the workflow.

This is a working MVP of the full pipeline described in `ROADMAP.md`: React dashboard →
Spring Boot backend (auth, tickets, GitHub) → Python/FastAPI AI service (RAG over
pgvector, LangChain agents, self-correcting Maven/Gradle test loop) → GitHub PR.

## Documentation

| Guide | Contents |
| --- | --- |
| [Implemented improvements](docs/improvements.md) | Changes, reasons, source entry points, verification results and limits |
| [Run and troubleshoot](RUNNING.md) | Startup/shutdown, ports, logs, CI and common failures |
| [Company and team setup](docs/team-workflow.md) | Admin activation, invitations, role permissions and database upgrades |
| [Gmail API setup](docs/gmail-api-setup.md) | Sender authorization and email delivery checks |
| [Password recovery](docs/password-recovery.md) | Reset behavior, expiry and account protection |
| [Roadmap](ROADMAP.md) | Architecture, current scope and remaining work |

## Run locally

With Docker, JDK 21, Python 3.11/3.12 and Node.js 22+ installed:

**Upgrading an existing database?** Follow the ordered
[migration instructions](docs/team-workflow.md#existing-installations) first.
The startup script does not apply upgrade migrations automatically.

```bash
./start.sh
# Open http://localhost:5173
./stop.sh
```

Startup handles dependencies, database readiness and service health checks; logs go
in ignored `.run/logs/`. Existing project services are reused, and shutdown preserves
the database volume. See [RUNNING.md](RUNNING.md) for configuration, requirements,
manual startup, and `./stop.sh --keep-db`. New companies start with
[operator provisioning and admin activation](docs/team-workflow.md#provision-a-company);
everyone then uses the same login page.

[GitHub Actions CI](.github/workflows/ci.yml) checks backend tests/package, AI tests
with real Maven/Gradle builds, frontend production build, and startup/setup scripts
on pushes and pull requests. No external-service secrets or deployment are required.

## Status: what's actually built and tested

Implemented features and their verification:

- **Backend** (`backend/`, Spring Boot 4.1 / Java 21): JWT/email login, password
  recovery, private company provisioning, invitation-only activation, workspace/project
  memberships, four roles, delegated project team management, GitHub connections,
  ticket reporting/priority/assignment, QA verification, activity history, SSE progress,
  and the async AI bridge. The backend test suite passes against an isolated database.
- **Frontend** (`frontend/`, React + Vite + Tailwind): workspace dashboard, admin/team
  management, project/repository pages, invitation acceptance, issue detail, live
  progress, and diff/PR review. Frontend tests and the production build pass; workspace
  administration and QA controls were also checked in the running app.
- **AI service** (`ai-service/`, FastAPI + LangChain): RAG indexing/search over pgvector,
  planner/coding/test agents (LangChain for the actual LLM calls — `ChatGoogleGenerativeAI`
  / `GoogleGenerativeAIEmbeddings`), and a plain-Python retry loop (`workflow.py`) for
  code → test → retry (up to 3x) → commit → PR.
- **Local test fixture `user-service`** (`workspace/user-service/`): a small Spring Boot app seeded
  with 5 intentional bugs (NPE, wrong HTTP status, missing validation, wrong calculation,
  swallowed exception), each with a failing test. Verified: all 5 fail red on `main`, and
  the AI agent (in mock mode) fixes each one independently, on its own branch, in 1 attempt,
  without touching the others in the original demo. This is a development fixture,
  not a repository automatically available to workspace members.
- **Real GitHub workflow**: live Gemini analysis/implementation, Maven tests, a branch
  push and PR creation were exercised against a connected Java repository. This proves
  the integration path, not that every proposed fix fully addresses its issue. QA must
  verify the behavior; existing tests may not cover newly changed behavior.

## Mock LLM mode (why the loop works without any API key)

`MOCK_LLM=true` (the default) replaces Gemini calls with deterministic logic:
- **Embeddings**: a bag-of-words feature-hashing function — not semantically trained, but
  lexically meaningful, so pgvector similarity search genuinely ranks relevant files higher.
- **Planning**: keyword-matched canned plans for the 5 known dummy-repo bug categories,
  falling back to a generic "read the file, apply minimal fix" plan otherwise.
- **Coding**: exact known-good patches for the 5 dummy bugs (string replacement against the
  real buggy source, not fake).

This exercises retrieval, planning, coding, tests, retries and local git commits
without a Gemini key. Publishing a real GitHub branch/PR still requires a valid,
authorized repository credential. Flip `MOCK_LLM=false` + set `GEMINI_API_KEY` to use the real model; the
coding agent then asks Gemini to rewrite each relevant file in full, with the previous
Maven or Gradle test failure fed back in on retries.

## Supported projects and test runners

The agent indexes and edits **Java source** in projects built with **Maven or Gradle**.
Gradle build scripts can use Groovy (`build.gradle`) or Kotlin DSL (`build.gradle.kts`);
this does not add Kotlin or Groovy source-language support.

| Build tool | Detection files | Test command |
|---|---|---|
| Maven | `pom.xml` | `./mvnw -B -q test`, or `mvn -B -q test` without a wrapper |
| Gradle | `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts` | `./gradlew --no-daemon --console=plain test`, or `gradle --no-daemon --console=plain test` without a wrapper |

The runner prefers committed wrappers, including scripts without an executable bit
(launched through `sh`). A compatible JDK must be available. Without a wrapper, the
corresponding build tool must be installed on the AI-service host. The Docker image
includes Maven; Gradle projects in Docker should commit `gradlew` and `gradle/wrapper/`.
The first wrapper run may need network access to download its distribution and dependencies.

Detection searches the repository root and up to three directory levels below it,
skipping hidden directories and generated output. The shallowest build wins; sibling
directories are checked alphabetically. Maven wins if both build systems exist in the
same directory. Gradle settings files keep multi-project builds at their root so `test`
runs the subprojects' test tasks. Independent builds in one repository are not all run.

Normal AI mode runs the detected build's existing test suite. Demo mode can select the
known bug's classes/methods using Maven `-Dtest` or Gradle `--tests`. Output records the
actual command and working directory. Failures feed the existing correction loop,
with at most three attempts and a five-minute timeout per test run.

Run runner regression tests (no database, API key, or Java build tool required):

```bash
cd ai-service
python3.11 -m unittest discover -s tests -v
```

To also run real Maven/Gradle smoke tests, install both tools and a compatible JDK,
then run `RUN_BUILD_INTEGRATION=1 python3.11 -m unittest discover -s tests -v`.
These create temporary Java/JUnit projects and verify a failing suite, a passing
class/method selection, and a corrected full suite, including both Gradle DSLs and a
multi-project Gradle root. They download dependencies as needed and leave connected
repositories untouched.

## Credentials you need to supply

Email/username sign-in and email-based password recovery are implemented. Unknown
emails ask the person to request an invitation; registered emails receive a single-use reset
link after Gmail API authorization. Follow the [Gmail API setup guide](docs/gmail-api-setup.md)
to authorize a dedicated sender without sharing a Gmail password. Email uses HTTPS,
including on hosts that block SMTP. See [password recovery](docs/password-recovery.md)
for security behavior, migrations and tests. Production settings are listed in
`backend/.env.production.example`; setup does not deploy the application.

The mock-mode loop against the dummy repository is an AI-service development
fixture and does not need a Gemini or email credential. The team UI requires authorized project
membership and a connected GitHub repository for issue import. For live AI work:

| Variable | Where | Required for |
|---|---|---|
| `GEMINI_API_KEY` | `ai-service/.env` | Real LLM planning/coding (set `MOCK_LLM=false` too) |
| `JWT_SECRET` | `backend/.env` or shell env | Optional — a dev default is baked in; set your own for anything beyond local testing |

Copy `ai-service/.env.example` to `ai-service/.env` and fill in what you have. **Never
paste real secrets into `.env.example` itself** — it's a git-tracked template; only
`.env` is gitignored.

### Team roles and project access

Issue Solver supports **Workspace Admin**, **Project Manager**, **QA**, and
**Developer**. Workspace membership controls access to a company/team; project
memberships assign responsibilities separately for each project. Admins manage
projects, invitations, and GitHub connections. Managers manage QA/developers within
their own projects, prioritize issues, and assign/reassign/unassign developers;
QA reports and verifies; assigned developers operate the AI agent. A merged PR
awaits QA verification before an issue becomes Solved.

See [the team workflow](docs/team-workflow.md) for setup, permissions, invitation
links/email, and the ordered `004_workspace_roles.sql` and
`005_company_onboarding.sql` upgrade migrations. Public signup is disabled; users
activate through invitations and choose their own passwords.

### GitHub access — connected by workspace admins

Workspace admins connect their own GitHub personal access token from a project's
**GitHub connection** section. Project members use the connection through authorized
backend actions; the credential is never returned to team members. Once connected:

1. The project page lists repositories accessible to that token (`GET /user/repos`), so you
   pick from your actual repos rather than typing owner/repo blind.
2. Clicking "Connect" on a repo stamps *that user's* token onto the `repos` row
   (`Repo.githubToken`), and all subsequent operations against it — reading issues,
   pushing the fix branch, opening the PR — use that stored token, propagated from the
   backend through to the AI service per-request (never a shared env var).
3. A `GITHUB_TOKEN` env var still exists as a **fallback only**, used solely so the
   locally-seeded dummy `user-service` repo keeps working without anyone connecting an
   account.

When replacing an admin's token, use **Save token**, then **Re-sync token** on each
existing repository in that project. Editing the permissions of the same GitHub token
does not require replacing it in the app. Other project members do not need to paste
the shared repository credential into their own accounts.

Create the token at GitHub → Settings → Developer settings → **Fine-grained tokens**,
scoped to just the repo(s) you want the agent touching, with Contents (Read/Write),
Pull requests (Read/Write), and Issues (Read) permissions.

Stored tokens are encrypted with AES-GCM when `TOKEN_ENCRYPTION_KEY` is configured;
production requires that key. Local development without a key retains plaintext
compatibility. Keep the key and database backups private.

## Running it

### Option A — docker-compose (postgres + ai-service + backend)

```bash
cd Git_Issue_solver
docker compose up -d --build
cd frontend && npm install && npm run dev   # frontend isn't containerized; run it separately
```

The recorded validation used local app processes and PostgreSQL. The backend and
AI-service Docker images still need their own build/run verification. Compose reads
project-root `.env` or exported variables, not `backend/.env`; see
[email configuration](docs/password-recovery.md#real-email-through-the-gmail-https-api).

### Option B — local dev (what was actually used for all testing here)

```bash
# 1. Postgres with pgvector
docker compose up -d postgres

# 2. AI service
cd ai-service
python3.11 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
export DATABASE_URL=postgresql://gitissuesolver:gitissuesolver@localhost:5433/gitissuesolver
export WORKSPACE_DIR=$(pwd)/../workspace
uvicorn main:app --reload --port 8000

# 3. Backend (new terminal)
cd backend
mvn spring-boot:run

# 4. Frontend (new terminal)
cd frontend
npm install && npm run dev
```

Open http://localhost:5173 and follow [team setup](docs/team-workflow.md#getting-a-team-started):
provision a company through the operator CLI, activate its admin, create a project,
invite a manager, QA and developers, and connect GitHub
as a workspace admin. QA reports an issue; the manager assigns a project developer;
that developer analyzes and implements the approved plan. After code review and
merge, QA retests and marks it solved or reopens it. Project membership grants access;
unowned local fixtures are not exposed through the UI. The operator provisions the
company first; there is no public registration or shared company password.

## Bugs found and fixed while proving this end-to-end

Both were only visible by actually running the full loop, not from reading the code:

1. **Async Hibernate lazy-load**: `AgentService`'s `@Async` methods loaded a `Ticket` via
   `findById`, then accessed the lazy `ticket.getRepo()` association from a thread with no
   Hibernate session → `LazyInitializationException`. Fixed with a `JOIN FETCH` query
   (`TicketRepository.findByIdWithRepo`).
2. **JDK HttpClient vs uvicorn**: Spring's `RestClient` (backed by Java's `HttpClient`)
   sends an `Upgrade: h2c` cleartext-HTTP/2 negotiation header by default. uvicorn doesn't
   understand it and silently dropped the request body, so every backend→AI-service call
   landed as an empty body (`422 Field required`). Fixed by forcing
   `HttpClient.Version.HTTP_1_1` in `AgentClient`.

## What's not done

- No measured fix-accuracy percentage, retrieval benchmark, or guarantee of complete
  issue coverage. Targeted tests and human code/QA review are still needed.
- The app does not merge PRs or close GitHub issues. GitHub state is refreshed from
  the repository page; QA verification is a separate action inside the app.
- Automated mail tests use fakes; real inbox delivery is not established by those tests.
- Docker builds for `backend` and `ai-service` images are written but not run.
- Automatic priority prediction, evaluation metrics, cost tracking, execution
  sandboxing and other source languages remain future work in the roadmap.
