# Running the project

Four services. Postgres runs in Docker; the other three run in their own terminal tab.

---

## One-time setup (keys)

Secrets live in `ai-service/.env` — **never** in `.env.example` (that one is git-tracked).

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

**The GitHub token users actually use is per-user, not from `.env`** — each QA user pastes
their own token into "Connect GitHub" on the Dashboard. The `.env` one is only a fallback
for the locally-seeded dummy repo.

First-time only, for frontend deps:
```bash
cd ~/Desktop/Git_Issue_solver/frontend && npm install
```

---

## Start (4 terminals)

### 1. Postgres + pgvector
```bash
cd ~/Desktop/Git_Issue_solver
docker compose up -d postgres
```

### 2. AI service (FastAPI + LangChain) — port 8000
```bash
cd ~/Desktop/Git_Issue_solver/ai-service
source venv/bin/activate
uvicorn main:app --reload --port 8000
```

### 3. Backend (Spring Boot) — port 8081
```bash
cd ~/Desktop/Git_Issue_solver/backend
mvn spring-boot:run
```

### 4. Frontend (React + Vite) — port 5173
```bash
cd ~/Desktop/Git_Issue_solver/frontend
npm run dev
```

Open **http://localhost:5173**

---

## Health check (run anytime)

```bash
docker ps --filter name=gitissuesolver-postgres --format '{{.Names}} {{.Status}}'
curl -s http://localhost:8000/health                              # ai-service
curl -s http://localhost:8081/actuator/health                     # backend
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5173    # frontend
```

`{"status":"UP","mockLlm":false}` from the ai-service means real Gemini is active.

---

## Stop

```bash
pkill -f "uvicorn main:app"    # ai-service
pkill -f "spring-boot:run"     # backend
pkill -f "vite"                # frontend
docker compose stop postgres   # database (data survives)
```

Or by port, if `pkill` doesn't match:
```bash
lsof -nP -iTCP:8081 -sTCP:LISTEN -t | xargs kill    # swap port as needed
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Failed to fetch` in browser | backend not running | start terminal 3 |
| `Port 8081 was already in use` | old backend still alive | `pkill -f "spring-boot:run"` |
| `403` on every API call | stale browser token | DevTools → Application → Local Storage → clear, re-register |
| `429 ... quota` in a step message | Gemini free-tier limit (per-minute) | wait ~1 min; it auto-retries with backoff |
| `no POM in this directory` | repo isn't Maven, or pom is >3 levels deep | only Maven/Java repos are supported |
| `rate limit exceeded for <ip>` | unauthenticated GitHub call | connect your GitHub token as a QA user |

Errors now surface with their real cause in the UI step timeline — if you see a bare
"Internal Server Error", check `uvicorn`'s terminal output for the traceback.
