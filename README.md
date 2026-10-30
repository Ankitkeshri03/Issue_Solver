# AI Software Engineering Agent

A GitHub issue goes in, a tested Pull Request comes out. Developer just reviews and merges.

This is a working MVP of the full pipeline described in `ROADMAP.md`: React dashboard →
Spring Boot backend (auth, tickets, GitHub) → Python/FastAPI AI service (RAG over
pgvector, LangChain agents, self-correcting Maven/Gradle test loop) → GitHub PR.

## Status: what's actually built and tested

Everything below has been run end-to-end on this machine, not just written:

- **Backend** (`backend/`, Spring Boot 4.1 / Java 21): JWT auth with QA/DEVELOPER roles,
  GitHub issue fetching, ticket CRUD + assignment, SSE live-progress streaming, async
  bridge to the AI service. `mvn test` passes (full Spring context boot).
- **Frontend** (`frontend/`, React + Vite + Tailwind): the 4 screens — Dashboard,
  Ticket detail, Agent live view, Diff + PR. `npm run build` passes; verified live against
  the real backend (register/login, CORS, SSE).
- **AI service** (`ai-service/`, FastAPI + LangChain): RAG indexing/search over pgvector,
  planner/coding/test agents (LangChain for the actual LLM calls — `ChatGoogleGenerativeAI`
  / `GoogleGenerativeAIEmbeddings`), and a plain-Python retry loop (`workflow.py`) for
  code → test → retry (up to 3x) → commit → PR.
- **Dummy `user-service`** (`workspace/user-service/`): a small Spring Boot app seeded
  with 5 intentional bugs (NPE, wrong HTTP status, missing validation, wrong calculation,
  swallowed exception), each with a failing test. Verified: all 5 fail red on `main`, and
  the AI agent (in mock mode) fixes each one independently, on its own branch, in 1 attempt,
  without touching the others.
- **Full loop, driven through the real backend API**: register → connect repo → create
  ticket → assign → analyze (real pgvector RAG + plan) → approve → implement (real
  `mvn test` run, real git commit, real diff) → `PR_CREATED`. Two real integration bugs
  were found and fixed doing this (see "Bugs found & fixed" below).

## Mock LLM mode (why the loop works without any API key)

`MOCK_LLM=true` (the default) replaces Gemini calls with deterministic logic:
- **Embeddings**: a bag-of-words feature-hashing function — not semantically trained, but
  lexically meaningful, so pgvector similarity search genuinely ranks relevant files higher.
- **Planning**: keyword-matched canned plans for the 5 known dummy-repo bug categories,
  falling back to a generic "read the file, apply minimal fix" plan otherwise.
- **Coding**: exact known-good patches for the 5 dummy bugs (string replacement against the
  real buggy source, not fake).

This proves the *architecture* — RAG, planning, coding, test-run, self-correction, git
branching, PR creation — works end-to-end without needing a Gemini key or a GitHub token
to develop against. Flip `MOCK_LLM=false` + set `GEMINI_API_KEY` to use the real model; the
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

None of these are required to run the mock-mode loop against the dummy repo (as
demonstrated above). You need them for the real thing:

| Variable | Where | Required for |
|---|---|---|
| `GEMINI_API_KEY` | `ai-service/.env` | Real LLM planning/coding (set `MOCK_LLM=false` too) |
| `JWT_SECRET` | `backend/.env` or shell env | Optional — a dev default is baked in; set your own for anything beyond local testing |

Copy `ai-service/.env.example` to `ai-service/.env` and fill in what you have. **Never
paste real secrets into `.env.example` itself** — it's a git-tracked template; only
`.env` is gitignored.

### GitHub access — per-user, connected in the UI (not a shared env token)

Each app user connects their **own** GitHub personal access token from the Dashboard
("Connect GitHub"), instead of the whole app sharing one token. Once connected:

1. The Dashboard lists every repo that token can read/write (`GET /user/repos`), so you
   pick from your actual repos rather than typing owner/repo blind.
2. Clicking "Connect" on a repo stamps *that user's* token onto the `repos` row
   (`Repo.githubToken`), and all subsequent operations against it — reading issues,
   pushing the fix branch, opening the PR — use that stored token, propagated from the
   backend through to the AI service per-request (never a shared env var).
3. A `GITHUB_TOKEN` env var still exists as a **fallback only**, used solely so the
   locally-seeded dummy `user-service` repo keeps working without anyone connecting an
   account.

Create the token at GitHub → Settings → Developer settings → **Fine-grained tokens**,
scoped to just the repo(s) you want the agent touching, with Contents (Read/Write),
Pull requests (Read/Write), and Issues (Read) permissions.

**Known limitation:** tokens are currently stored as plaintext in Postgres
(`users.github_token`, `repos.github_token`). That's fine for personal/local use but is
**not production-safe** — before deploying this beyond a single-user local setup, add
encryption-at-rest or move tokens into a proper secrets manager.

## Running it

### Option A — docker-compose (postgres + ai-service + backend)

```bash
cd Git_Issue_solver
docker compose up -d --build
cd frontend && npm install && npm run dev   # frontend isn't containerized; run it separately
```

Note: the ai-service and backend Docker builds haven't been run in this session (only
tested via local venv / mvn — see "Status" above); docker-compose itself was validated
for the postgres+pgvector service, which is up and was used for every test above.

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

Open http://localhost:5173, register a QA user and a DEVELOPER user, connect repo
`ankitkeshri/user-service` (or any owner/repo — GitHub issue fetching needs `GITHUB_TOKEN`;
ticket creation/assignment/analyze/implement work regardless since they operate on the
locally-seeded `workspace/user-service`).

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

