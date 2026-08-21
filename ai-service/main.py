from typing import Optional

from fastapi import FastAPI
from pydantic import BaseModel

from agents.issue_agent import build_query
from agents.planner_agent import make_plan
from agents.retrieval_agent import find_relevant_files
from config import MOCK_LLM
from graph import implement_graph
from tools.git_tools import clone_or_update

app = FastAPI(title="AI Software Engineering Agent — AI Service")


class AnalyzeRequest(BaseModel):
    ticketId: int
    issueNumber: int
    issueTitle: str
    issueDescription: Optional[str] = ""
    repoCloneUrl: str
    repoId: int


class AnalyzeResponse(BaseModel):
    plan: str
    relevantFiles: list[str]


class ImplementRequest(BaseModel):
    ticketId: int
    issueNumber: int
    issueTitle: Optional[str] = ""
    issueDescription: Optional[str] = ""
    plan: str
    repoCloneUrl: str
    repoId: int
    branchName: str
    baseBranch: str = "main"


class ImplementResponse(BaseModel):
    success: bool
    prUrl: Optional[str] = None
    diff: Optional[str] = None
    testOutput: Optional[str] = None
    attempts: int
    failureReason: Optional[str] = None


def _owner_repo(clone_url: str) -> tuple[Optional[str], Optional[str]]:
    if "github.com" not in clone_url:
        return None, None
    tail = clone_url.rstrip("/").split("github.com/")[-1].removesuffix(".git")
    parts = tail.split("/")
    if len(parts) != 2:
        return None, None
    return parts[0], parts[1]


@app.get("/health")
def health():
    return {"status": "UP", "mockLlm": MOCK_LLM}


@app.post("/analyze", response_model=AnalyzeResponse)
def analyze(request: AnalyzeRequest):
    repo_path = clone_or_update(request.repoCloneUrl)
    query = build_query(request.issueTitle, request.issueDescription or "")
    retrieved = find_relevant_files(request.repoId, repo_path, query)
    plan = make_plan(request.issueTitle, request.issueDescription or "", retrieved)
    return AnalyzeResponse(plan=plan, relevantFiles=[r["file_path"] for r in retrieved])


@app.post("/implement", response_model=ImplementResponse)
def implement(request: ImplementRequest):
    repo_path = clone_or_update(request.repoCloneUrl, request.baseBranch)
    owner, repo_name = _owner_repo(request.repoCloneUrl)
    issue_text = f"{request.issueTitle or ''} {request.issueDescription or ''} {request.plan}"

    query = build_query(request.issueTitle or f"issue #{request.issueNumber}", request.issueDescription or "")
    relevant_files = [r["file_path"] for r in find_relevant_files(request.repoId, repo_path, query)]

    initial_state = {
        "repo_path": repo_path,
        "clone_url": request.repoCloneUrl,
        "owner": owner,
        "repo_name": repo_name,
        "issue_number": request.issueNumber,
        "issue_text": issue_text,
        "plan": request.plan,
        "relevant_files": relevant_files,
        "branch_name": request.branchName,
        "base_branch": request.baseBranch,
        "attempt": 0,
        "feedback": None,
        "test_passed": False,
        "test_output": "",
        "diff": "",
        "pr_url": None,
        "failure_reason": None,
    }

    final_state = implement_graph.invoke(initial_state)

    return ImplementResponse(
        success=bool(final_state.get("test_passed")),
        prUrl=final_state.get("pr_url"),
        diff=final_state.get("diff"),
        testOutput=final_state.get("test_output"),
        attempts=final_state.get("attempt", 0),
        failureReason=final_state.get("failure_reason"),
    )
