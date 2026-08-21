"""LangGraph workflow for the implement phase: code -> test -> (retry up to 3x) -> commit -> PR.

This is the self-correction loop from the roadmap: generate a fix, run mvn test,
and if it fails, feed the failure back into the coding agent and try again, up to
MAX_TEST_RETRIES times, before giving up.
"""

from typing import Optional, TypedDict

from langgraph.graph import END, StateGraph

from agents.bug_catalog import test_filter_for
from agents.coding_agent import apply_fix
from agents.test_agent import run_tests
from config import MAX_TEST_RETRIES, MOCK_LLM
from tools import git_tools, github_tools


class ImplementState(TypedDict):
    repo_path: str
    clone_url: str
    owner: Optional[str]
    repo_name: Optional[str]
    issue_number: int
    issue_text: str
    plan: str
    relevant_files: list[str]
    branch_name: str
    base_branch: str
    attempt: int
    feedback: Optional[str]
    test_passed: bool
    test_output: str
    diff: str
    pr_url: Optional[str]
    failure_reason: Optional[str]


def code_node(state: ImplementState) -> dict:
    apply_fix(state["repo_path"], state["issue_text"], state["plan"], state["relevant_files"], state.get("feedback"))
    # LangGraph requires every node to write at least one key; this is a no-op update.
    return {"feedback": state.get("feedback")}


def test_node(state: ImplementState) -> dict:
    # In mock mode the dummy repo intentionally carries several unrelated pre-seeded
    # bugs at once, so scope the run to just this issue's test(s) — a real coding agent
    # working a single ticket shouldn't be blocked by unrelated failing tests either.
    test_filter = test_filter_for(state["issue_text"]) if MOCK_LLM else None
    passed, output = run_tests(state["repo_path"], test_filter=test_filter)
    return {"test_passed": passed, "test_output": output, "attempt": state["attempt"] + 1}


def decide_after_test(state: ImplementState) -> str:
    if state["test_passed"]:
        return "commit"
    if state["attempt"] >= MAX_TEST_RETRIES:
        return "fail"
    return "retry"


def prepare_retry_node(state: ImplementState) -> dict:
    return {"feedback": state["test_output"]}


def commit_node(state: ImplementState) -> dict:
    git_tools.create_branch(state["repo_path"], state["branch_name"])
    diff = git_tools.commit_all(state["repo_path"], f"Fix issue #{state['issue_number']} via AI agent")
    return {"diff": diff}


def pr_node(state: ImplementState) -> dict:
    pushed = git_tools.push_branch(state["repo_path"], state["clone_url"], state["branch_name"])
    pr_url = None
    if pushed and state.get("owner") and state.get("repo_name"):
        pr_url = github_tools.create_pull_request(
            state["owner"],
            state["repo_name"],
            title=f"Fix issue #{state['issue_number']}",
            head=state["branch_name"],
            base=state["base_branch"],
            body=f"Automated fix for issue #{state['issue_number']}.\n\nPlan:\n{state['plan']}",
        )
    return {"pr_url": pr_url}


def fail_node(state: ImplementState) -> dict:
    return {"failure_reason": f"Tests still failing after {state['attempt']} attempt(s)"}


def build_graph():
    graph = StateGraph(ImplementState)
    graph.add_node("code", code_node)
    graph.add_node("test", test_node)
    graph.add_node("prepare_retry", prepare_retry_node)
    graph.add_node("commit", commit_node)
    graph.add_node("pr", pr_node)
    graph.add_node("fail", fail_node)

    graph.set_entry_point("code")
    graph.add_edge("code", "test")
    graph.add_conditional_edges(
        "test", decide_after_test, {"commit": "commit", "retry": "prepare_retry", "fail": "fail"}
    )
    graph.add_edge("prepare_retry", "code")
    graph.add_edge("commit", "pr")
    graph.add_edge("pr", END)
    graph.add_edge("fail", END)
    return graph.compile()


implement_graph = build_graph()
