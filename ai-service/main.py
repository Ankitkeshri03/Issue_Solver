import logging
import traceback
import hmac
import tempfile
from contextlib import contextmanager
from typing import Optional

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from agents.issue_agent import build_query
from agents.planner_agent import make_plan
from agents.retrieval_agent import find_relevant_files
from agents.test_agent import describe_test_command
from config import MOCK_LLM, AI_SERVICE_TOKEN, APP_ENV, TEST_RUNNER
from llm import LlmQuotaError
from tools.git_tools import clone_or_update, check_push_access, GitHubAccessError
from workflow import run_implement_workflow

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="AI Software Engineering Agent — AI Service")


@app.middleware("http")
async def require_service_token(request: Request, call_next):
    if request.url.path != "/health" and AI_SERVICE_TOKEN:
        if not hmac.compare_digest(request.headers.get("authorization", ""), f"Bearer {AI_SERVICE_TOKEN}"):
            return JSONResponse(status_code=401, content={"error": "Invalid service authentication"})
    return await call_next(request)


@contextmanager
def repository_workspace(clone_url, branch="main", token=None):
    if APP_ENV == "production" or TEST_RUNNER == "github_actions":
        with tempfile.TemporaryDirectory(prefix="issue-solver-") as directory:
            yield clone_or_update(clone_url, branch, token=token, workspace_dir=directory)
    else:
        yield clone_or_update(clone_url, branch, token=token)


# Without these handlers every failure reached the UI as a bare
# `500 Internal Server Error: "Internal Server Error"`, with the real cause only visible
# in the uvicorn log -- which made a dead model name, a git failure, and an exhausted
# Gemini quota all look like the same unactionable error.


@app.exception_handler(LlmQuotaError)
async def handle_quota_error(_request: Request, exc: LlmQuotaError):
    return JSONResponse(status_code=429, content={"error": str(exc), "type": "llm_quota_exceeded"})


@app.exception_handler(GitHubAccessError)
async def handle_github_error(_request: Request, exc: GitHubAccessError):
    return JSONResponse(status_code=exc.status_code, content={"error": str(exc), "type": "github_access_error"})


@app.exception_handler(Exception)
async def handle_unexpected_error(_request: Request, exc: Exception):
    logger.error("Unhandled error: %s", exc, exc_info=True)
    if APP_ENV == "production":
        return JSONResponse(status_code=500, content={"error": "The AI job failed. Check the service logs or retry the ticket.", "type": "internal_error"})
    return JSONResponse(
        status_code=500,
        content={
            "error": f"{type(exc).__name__}: {exc}",
            "type": "internal_error",
            "traceback": traceback.format_exc().splitlines()[-8:],
        },
    )


class AnalyzeRequest(BaseModel):
    ticketId: int
    issueNumber: int
    issueTitle: str
    issueDescription: Optional[str] = ""
    repoCloneUrl: str
    repoId: int
    githubToken: Optional[str] = None


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
    githubToken: Optional[str] = None


class ImplementResponse(BaseModel):
    success: bool
    testsPassed: Optional[bool] = None
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
    with repository_workspace(request.repoCloneUrl, token=request.githubToken) as repo_path:
        return _analyze(request, repo_path)


def _analyze(request, repo_path):
    query = build_query(request.issueTitle, request.issueDescription or "")
    retrieved = find_relevant_files(request.repoId, repo_path, query)
    plan = make_plan(
        request.issueTitle, request.issueDescription or "", retrieved,
        test_command=describe_test_command(repo_path),
    )
    return AnalyzeResponse(plan=plan, relevantFiles=[r["file_path"] for r in retrieved])


@app.post("/implement", response_model=ImplementResponse)
def implement(request: ImplementRequest):
    # Fail before cloning, embeddings, or paid/quota-limited AI editing.
    check_push_access(request.repoCloneUrl, request.githubToken)
    with repository_workspace(request.repoCloneUrl, request.baseBranch, token=request.githubToken) as repo_path:
        return _implement(request, repo_path)


def _implement(request, repo_path):
    owner, repo_name = _owner_repo(request.repoCloneUrl)
    issue_text = f"{request.issueTitle or ''} {request.issueDescription or ''} {request.plan}"

    query = build_query(request.issueTitle or f"issue #{request.issueNumber}", request.issueDescription or "")
    relevant_files = [r["file_path"] for r in find_relevant_files(request.repoId, repo_path, query)]

    result = run_implement_workflow(
        repo_path=repo_path,
        clone_url=request.repoCloneUrl,
        owner=owner,
        repo_name=repo_name,
        issue_number=request.issueNumber,
        issue_text=issue_text,
        plan=request.plan,
        relevant_files=relevant_files,
        branch_name=request.branchName,
        base_branch=request.baseBranch,
        github_token=request.githubToken,
    )

    return ImplementResponse(
        success=result.test_passed and not result.failure_reason,
        testsPassed=result.test_passed,
        prUrl=result.pr_url,
        diff=result.diff,
        testOutput=result.test_output,
        attempts=result.attempts,
        failureReason=result.failure_reason,
    )
