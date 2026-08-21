# AI Software Engineering Agent — Full Roadmap

## One line summary
A platform where a GitHub issue goes in, and a tested Pull Request comes out. Developer just reviews and merges.

---

## Your interview story
> "At ParaMai I manually copied GitHub tickets into Claude, got a fix, then applied it myself.
> This project automates that entire loop — ticket goes in, tested PR comes out, developer just approves."

---

## Roles — 2 only
| Role | Can do |
|---|---|
| QA | Create ticket, assign to developer, track status |
| Developer | See assigned tickets, analyze with AI, approve plan, review diff, create PR |

---

## Language scope
**Java Spring Boot only** (to start)
- Test runner: `mvn test`
- Easy to verify fixes
- Add Python/JavaScript later

---

## Tech Stack
| Layer | Technology | Why |
|---|---|---|
| Frontend | React + Tailwind | You know it |
| Backend | Java + Spring Boot | You know it |
| AI Service | Python + FastAPI | You know it |
| LLM | Gemini Flash (free) | Free, you know it |
| Agent Framework | LangChain + LangGraph | You know LangChain |
| Database | PostgreSQL + pgvector | One DB for everything |
| Integration | GitHub API | Core of project |

---

## Why pgvector over Pinecone?
- Already inside PostgreSQL — no extra service
- Free, no limits for this project size
- One database for users + tickets + embeddings
- Interview answer: "Reduced infrastructure complexity"

---

## UI — Only 4 Screens
1. **Dashboard** — ticket list + repo selector
2. **Ticket detail** — issue info + AI analysis + approve plan
3. **Agent live view** — step by step progress stream
4. **Diff + PR** — code diff viewer + create PR button

---

## How All Parts Communicate

### React → Spring Boot
- Protocol: HTTP REST API
- React sends user actions
- Spring Boot responds with JSON
```
GET /api/issues?repo=user-service
→ returns list of tickets as JSON
```

### Spring Boot → GitHub API
- Protocol: HTTP with GitHub Token
- Fetches issues, creates branches, creates PRs
```
GET https://api.github.com/repos/name/user-service/issues
Header: Authorization: Bearer TOKEN
```

### Spring Boot → Python AI Service
- Protocol: HTTP REST API
- Sends issue details, gets back analysis and PR result
```
POST http://localhost:8000/analyze
{ issue_id, title, description, repo_url }
```

### Python → Gemini (LLM)
- Protocol: LangChain API call
- Sends issue + relevant code as prompt
- Gets back fixed code as text
```python
llm.invoke("Issue: ... Code: ... Fix this bug")
```

### Python → pgvector
- Protocol: SQL query
- Converts ticket to embedding, finds similar code chunks
```sql
SELECT file_path, code_chunk 
FROM code_embeddings
ORDER BY embedding <-> query_embedding
LIMIT 5
```

### Python → GitHub API
- Creates branch, commits fixed code, opens PR
```
POST /repos/name/user-service/pulls
{ title, head branch, base branch }
```

### Spring Boot → React (live updates)
- Protocol: WebSocket / SSE
- Pushes live progress while AI is working
```
"Reading issue ✅"
"Found 3 relevant files ✅"
"Running tests 🔄"
```

---

## What Gets Passed Where
| From | To | What |
|---|---|---|
| React | Spring Boot | User actions, repo name |
| Spring Boot | GitHub API | Token, repo name |
| GitHub API | Spring Boot | Issues as JSON |
| Spring Boot | Python | Issue details, repo URL |
| Python | Gemini | Issue + code as text prompt |
| Gemini | Python | Fixed code as text |
| Python | pgvector | Embeddings, SQL queries |
| Python | GitHub API | Branch, commit, PR data |
| Spring Boot | React | Live progress updates |

---

## The Core Flow
```
Someone creates issue in GitHub
        ↓
Your UI shows that issue (via GitHub API)
        ↓
QA assigns it to Developer
        ↓
Developer clicks "Analyze with AI"
        ↓
Spring Boot calls Python AI service
        ↓
Python: reads issue → searches pgvector → finds relevant files
        ↓
Python: sends issue + code to Gemini → gets fix
        ↓
Python: runs mvn test
        ↓
Fail? → retry max 3 times
        ↓
Pass? → create branch → commit fix → open PR on GitHub
        ↓
Developer sees diff → approves → merges
        ↓
Ticket → RESOLVED
```

---

## Ticket States
```
OPEN → ANALYZING → PLAN_READY → PLAN_APPROVED →
IMPLEMENTING → TESTING → PR_CREATED → RESOLVED
```

---

## Self Correction Loop
```
Generate fix
     ↓
Run mvn test
     ↓
Pass? → Create PR
Fail? → AI reads error → fixes code → runs tests again
                    (max 3 attempts)
```

---

## Folder Structure
```
my-project/
│
├── frontend/              ← React app (4 screens)
│
├── backend/               ← Spring Boot
│   ├── auth/              ← login, JWT
│   ├── github/             ← GitHub API calls
│   ├── issue/              ← ticket management
│   └── agent/              ← talks to Python service
│
├── ai-service/            ← Python + FastAPI
│   ├── agents/
│   │   ├── issue_agent.py
│   │   ├── retrieval_agent.py
│   │   ├── planner_agent.py
│   │   ├── coding_agent.py
│   │   └── test_agent.py
│   ├── tools/
│   │   ├── file_tools.py
│   │   ├── git_tools.py
│   │   └── github_tools.py
│   └── rag/
│
└── database/              ← SQL files, pgvector setup
```

---

## Testing Strategy
We create a dummy `user-service` Java project with intentional bugs:

| Issue | Bug type |
|---|---|
| #1 | Null pointer exception |
| #2 | Wrong HTTP status code |
| #3 | Missing input validation |
| #4 | Wrong logic in calculation |
| #5 | Missing exception handling |

This gives us a controlled way to test and verify the AI agent works correctly.

---

## Build Phases

### Phase 1 — Week 1 — Foundation
- [ ] Create dummy user-service Java repo on GitHub
- [ ] Spring Boot project setup
- [ ] PostgreSQL connection
- [ ] Login / Register with JWT
- [ ] 2 roles: QA and Developer
- [ ] Connect GitHub repo via GitHub API
- [ ] Read and display GitHub issues in UI
- [ ] Basic React UI with ticket list

### Phase 2 — Week 2 — RAG System
- [ ] Clone GitHub repo locally
- [ ] Parse Java files
- [ ] Split code into chunks
- [ ] Generate embeddings (Gemini embedding API)
- [ ] Store in pgvector
- [ ] Semantic search — find relevant files for a ticket

### Phase 3 — Week 3 — AI Agents
- [ ] Python FastAPI service setup
- [ ] Issue analyzer agent
- [ ] Retrieval agent (uses RAG)
- [ ] Planner agent — creates step by step plan
- [ ] Developer approves plan in UI
- [ ] Coding agent — edits files on new git branch
- [ ] Git tools (read file, write file, create branch)

### Phase 4 — Week 4 — Testing + PR
- [ ] Test runner — runs mvn test
- [ ] Self correction loop (max 3 retries)
- [ ] Show code diff in UI
- [ ] Developer reviews diff
- [ ] Create GitHub PR automatically
- [ ] Ticket status → RESOLVED
- [ ] Live progress via WebSocket

### Phase 5 — After MVP — Polish
- [ ] ML: issue priority prediction
- [ ] Evaluation metrics (Recall@K, patch success rate)
- [ ] Token cost tracking
- [ ] Docker sandbox for safe code execution
- [ ] Add Python language support

---

## MVP Checklist — Stop Here First
- [ ] Login + 2 roles working
- [ ] Connect a GitHub repo
- [ ] Read GitHub issues in your UI
- [ ] Index repo code into pgvector
- [ ] AI finds relevant files for a ticket
- [ ] AI proposes fix plan → dev approves
- [ ] AI edits code on a new branch
- [ ] Run mvn test → retry if failed (max 3x)
- [ ] Show code diff to developer
- [ ] Developer clicks → GitHub PR created

---

## Agentic AI — simple explanation
Normal AI = you ask, it answers, done.
Agentic AI = you give a goal, it breaks into steps, uses tools, makes decisions, completes goal on its own.

Your agent tools:
```python
read_file("LoginService.java")
edit_file("LoginService.java")
run_tests()
create_branch("fix/issue-101")
search_code("findUser")
```

LangGraph defines the workflow. AI decides which tool to use at each step.

---

## Next Steps (in order)
1. Create dummy user-service Java Spring Boot repo on GitHub with 5 intentional bugs
2. Create 5 GitHub issues for those bugs
3. Start Phase 1 — Spring Boot setup + login
4. Build piece by piece, understand every line

---

*Java Spring Boot only. 2 roles. 4 screens. Build MVP first.*
