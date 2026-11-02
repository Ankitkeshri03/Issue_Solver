"""Implement workflow: code -> test -> (retry up to 3x) -> commit -> PR.

This is the self-correction loop from the roadmap: generate a fix, run Maven or Gradle tests,
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
from config import MAX_TEST_RETRIES, MOCK_LLM, TEST_RUNNER
from tools import git_tools, github_tools
from tools.actions_runner import run_github_tests


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
    remote = TEST_RUNNER == "github_actions"
    if remote and (not owner or not repo_name or not github_token):
        result.failure_reason = "Remote tests require a connected GitHub repository and token."
        return result
    git_tools.create_branch(repo_path, branch_name)

    for attempt in range(1, MAX_TEST_RETRIES + 1):
        edited_files = apply_fix(repo_path, issue_text, plan, relevant_files, feedback)
        if attempt == 1 and not edited_files:
            result.failure_reason = "The coding agent did not produce any changes. Review the plan and retry."
            return result
        if remote:
            if edited_files:
                git_tools.commit_all(repo_path, f"Fix issue #{issue_number} (attempt {attempt})", paths=edited_files)
            if not git_tools.push_branch(repo_path, clone_url, branch_name, token=github_token):
                result.failure_reason = "Could not publish the candidate commit for remote tests."
                return result
            import git
            sha = git.Repo(repo_path).head.commit.hexsha
            passed, output = run_github_tests(f"{owner}/{repo_name}", sha, github_token, test_filter)
        else:
            passed, output = run_tests(repo_path, test_filter=test_filter)
        result.attempts = attempt
        result.test_output = output

        if passed:
            result.test_passed = True
            break

        feedback = output  # fed into the next apply_fix call as failure context

    if not result.test_passed:
        if remote:
            result.diff = git_tools.diff_against(repo_path, base_branch)
        result.failure_reason = f"Tests still failing after {result.attempts} attempt(s)"
        return result

    if remote:
        result.diff = git_tools.diff_against(repo_path, base_branch)
    else:
        result.diff = git_tools.commit_all(
            repo_path, f"Fix issue #{issue_number} via AI agent", paths=edited_files
        )
    if not result.diff.strip():
        result.failure_reason = "No code changes were produced; no pull request was created."
        result.test_passed = False
        return result

    try:
        pushed = git_tools.push_branch(repo_path, clone_url, branch_name, token=github_token)
    except git_tools.GitHubAccessError as exc:
        # Tests passed, but publication failed. Return the diff and test output so
        # the developer can still review the candidate instead of losing both.
        result.failure_reason = str(exc)
        return result
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
