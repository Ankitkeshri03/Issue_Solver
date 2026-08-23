import logging
import traceback
from typing import Optional

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from agents.issue_agent import build_query
from agents.planner_agent import make_plan
from agents.retrieval_agent import find_relevant_files
from config import MOCK_LLM
from llm import LlmQuotaError
from tools.git_tools import clone_or_update
from workflow import run_implement_workflow

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="AI Software Engineering Agent — AI Service")


# Without these handlers every failure reached the UI as a bare
# `500 Internal Server Error: "Internal Server Error"`, with the real cause only visible
# in the uvicorn log -- which made a dead model name, a git failure, and an exhausted
# Gemini quota all look like the same unactionable error.


@app.exception_handler(LlmQuotaError)
async def handle_quota_error(_request: Request, exc: LlmQuotaError):
    return JSONResponse(status_code=429, content={"error": str(exc), "type": "llm_quota_exceeded"})


@app.exception_handler(Exception)
async def handle_unexpected_error(_request: Request, exc: Exception):
    logger.error("Unhandled error: %s", exc, exc_info=True)
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
    repo_path = clone_or_update(request.repoCloneUrl, token=request.githubToken)
    query = build_query(request.issueTitle, request.issueDescription or "")
    retrieved = find_relevant_files(request.repoId, repo_path, query)
    plan = make_plan(request.issueTitle, request.issueDescription or "", retrieved)
    return AnalyzeResponse(plan=plan, relevantFiles=[r["file_path"] for r in retrieved])


@app.post("/implement", response_model=ImplementResponse)
def implement(request: ImplementRequest):
    repo_path = clone_or_update(request.repoCloneUrl, request.baseBranch, token=request.githubToken)
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
        success=result.test_passed,
        prUrl=result.pr_url,
        diff=result.diff,
        testOutput=result.test_output,
        attempts=result.attempts,
        failureReason=result.failure_reason,
    )
