"""Implement workflow: code -> test -> (retry up to 3x) -> commit -> PR.

This is the self-correction loop from the roadmap: generate a fix, run mvn test,
and if it fails, feed the failure back into the coding agent and try again, up to
MAX_TEST_RETRIES times, before giving up. Plain Python control flow — the loop only
ever goes forward or retries, so a state-machine framework added no value here.
LangChain is still used for the actual LLM calls (see planner_agent.py, coding_agent.py,
llm.py); this module just sequences those calls.
"""

from dataclasses import dataclass, field
from typing import Optional

from agents.bug_catalog import test_filter_for
from agents.coding_agent import apply_fix
from agents.test_agent import run_tests
from config import MAX_TEST_RETRIES, MOCK_LLM
from tools import git_tools, github_tools


@dataclass
class ImplementResult:
    test_passed: bool = False
    test_output: str = ""
    attempts: int = 0
    diff: str = ""
    pr_url: Optional[str] = None
    failure_reason: Optional[str] = None


def run_implement_workflow(
    repo_path: str,
    clone_url: str,
    owner: Optional[str],
    repo_name: Optional[str],
    issue_number: int,
    issue_text: str,
    plan: str,
    relevant_files: list[str],
    branch_name: str,
    base_branch: str,
    github_token: Optional[str] = None,
) -> ImplementResult:
    result = ImplementResult()
    feedback: Optional[str] = None
    edited_files: list[str] = []

    # In mock mode the dummy repo intentionally carries several unrelated pre-seeded
    # bugs at once, so scope the run to just this issue's test(s) — a real coding agent
    # working a single ticket shouldn't be blocked by unrelated failing tests either.
    test_filter = test_filter_for(issue_text) if MOCK_LLM else None

    for attempt in range(1, MAX_TEST_RETRIES + 1):
        edited_files = apply_fix(repo_path, issue_text, plan, relevant_files, feedback)
        passed, output = run_tests(repo_path, test_filter=test_filter)
        result.attempts = attempt
        result.test_output = output

        if passed:
            result.test_passed = True
            break

        feedback = output  # fed into the next apply_fix call as failure context

    if not result.test_passed:
        result.failure_reason = f"Tests still failing after {result.attempts} attempt(s)"
        return result

    git_tools.create_branch(repo_path, branch_name)
    result.diff = git_tools.commit_all(
        repo_path, f"Fix issue #{issue_number} via AI agent", paths=edited_files
    )

    pushed = git_tools.push_branch(repo_path, clone_url, branch_name, token=github_token)
    if pushed and owner and repo_name:
        result.pr_url = github_tools.create_pull_request(
            owner,
            repo_name,
            title=f"Fix issue #{issue_number}",
            head=branch_name,
            base=base_branch,
            body=f"Automated fix for issue #{issue_number}.\n\nPlan:\n{plan}",
            token=github_token,
        )

    return result
