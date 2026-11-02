import os
import base64
import re
from urllib.parse import urlsplit

import git
import requests

from config import GITHUB_TOKEN, WORKSPACE_DIR


class GitHubAccessError(RuntimeError):
    """An actionable GitHub failure, safe to return without subprocess diagnostics."""

    def __init__(self, message: str, status_code: int = 502):
        super().__init__(message)
        self.status_code = status_code


def _github_url(clone_url: str) -> str:
    url = urlsplit(clone_url)
    parts = url.path.strip("/").split("/")
    if (url.scheme != "https" or url.netloc != "github.com" or url.username or url.password
            or url.query or url.fragment or len(parts) != 2
            or not re.fullmatch(r"[A-Za-z0-9-]+", parts[0])
            or not re.fullmatch(r"[A-Za-z0-9_.-]+", parts[1])
            or parts[1].removesuffix(".git") in ("", ".", "..")):
        raise ValueError("Only clean https://github.com/owner/repository URLs are supported")
    return f"https://github.com/{parts[0]}/{parts[1].removesuffix('.git')}.git"


def _access_denied(clone_url: str, status: int) -> GitHubAccessError:
    repository = urlsplit(_github_url(clone_url)).path.strip("/").removesuffix(".git")
    if status == 401:
        detail = "The GitHub token is invalid or expired. Replace it in the app and re-sync this repository."
    elif status == 404:
        detail = "Check that the repository exists and is included in the token's repository access."
    elif status == 403:
        detail = ("GitHub refused write access. Include this repository in the token's repository access, "
                  "set Contents to Read and write and Pull requests to Read and write "
                  "(or repo scope for a classic token). Repository rules or organization approval may also block access. "
                  "If replacing the token, the QA repository owner must save it in the app and re-sync this repository.")
    else:
        detail = "GitHub is temporarily unavailable or rate limited. Retry after checking GitHub's service status."
    return GitHubAccessError(f"{repository}: {detail} Retry implementation after resolving access. (HTTP {status})",
                             status if status in (401, 403, 404, 429) else 502)


def check_push_access(clone_url: str, token: str = None) -> None:
    """Check Git's receive-pack advertisement; never create a ref or upload a commit.

    GET /repos permissions.push describes the account's role, so it can be true even
    when a fine-grained token cannot push. This checks the credential Git will use.
    Branch-specific rules and PR permissions are still enforced when publishing.
    """
    effective = token or GITHUB_TOKEN
    if not effective:
        return  # Preserve the local, unconnected demo workflow.
    url = _github_url(clone_url)
    try:
        with requests.get(url + "/info/refs", params={"service": "git-receive-pack"},
                          auth=("x-access-token", effective), timeout=(5, 15),
                          allow_redirects=False, stream=True) as response:
            if response.status_code != 200:
                raise _access_denied(url, response.status_code)
    except requests.RequestException:
        raise GitHubAccessError("Could not reach GitHub to verify push access. Check the network and retry implementation.") from None


def _local_dir_for(clone_url: str, workspace_dir: str | None = None) -> str:
    name = clone_url.rstrip("/").split("/")[-1].removesuffix(".git")
    if name in ("", ".", "..") or not re.fullmatch(r"[A-Za-z0-9_.-]+", name):
        raise ValueError("Invalid repository name")
    return os.path.join(workspace_dir or WORKSPACE_DIR, name)


def _credential_environment(token: str = None) -> dict:
    effective = token or GITHUB_TOKEN
    if not effective:
        return {}
    value = base64.b64encode(f"x-access-token:{effective}".encode()).decode()
    # Git reads this only for the subprocess. Tokens never enter remote URLs or argv.
    return {"GIT_CONFIG_COUNT": "3",
            "GIT_CONFIG_KEY_0": "http.https://github.com/.extraheader", "GIT_CONFIG_VALUE_0": "",
            "GIT_CONFIG_KEY_1": "http.https://github.com/.extraheader", "GIT_CONFIG_VALUE_1": f"AUTHORIZATION: basic {value}",
            "GIT_CONFIG_KEY_2": "credential.helper", "GIT_CONFIG_VALUE_2": "",
            "GIT_TERMINAL_PROMPT": "0"}


def clone_or_update(clone_url: str, default_branch: str = "main", token: str = None, workspace_dir: str | None = None) -> str:
    """Returns a local working copy path. If it already exists locally
    (e.g. the dummy user-service seeded directly into workspace/), reuse it
    as-is instead of trying to clone/fetch — this keeps local-only testing
    (no GITHUB_TOKEN, no real remote) working end to end.

    `token` is the connecting user's own GitHub token (per-repo, from the backend's
    Repo.githubToken) and takes priority over the process-wide GITHUB_TOKEN env var,
    which only exists as a fallback for the locally-seeded dummy repo.
    """
    clone_url = _github_url(clone_url)
    local_path = _local_dir_for(clone_url, workspace_dir)
    if os.path.isdir(os.path.join(local_path, ".git")):
        try:
            repo = git.Repo(local_path)
            repo.git.checkout(default_branch)
            repo.remotes.origin.set_url(clone_url)
            with repo.git.custom_environment(**_credential_environment(token)):
                repo.remotes.origin.pull()
        except Exception:
            if workspace_dir:
                raise
            # Local seeded demos can work offline; isolated deployment jobs must fail.
        return local_path

    if os.path.isdir(local_path):
        # Local-only dummy repo (not yet a git repo) — initialize one in place.
        repo = git.Repo.init(local_path, initial_branch=default_branch)
        repo.git.add(A=True)
        if repo.is_dirty() or not repo.head.is_valid():
            repo.index.commit("Initial commit")
        return local_path

    os.makedirs(workspace_dir or WORKSPACE_DIR, exist_ok=True)
    git.Repo.clone_from(clone_url, local_path, branch=default_branch, env=_credential_environment(token))
    return local_path


def create_branch(repo_path: str, branch_name: str) -> None:
    repo = git.Repo(repo_path)
    if branch_name in repo.heads:
        repo.git.checkout(branch_name)
    else:
        repo.git.checkout("-b", branch_name)


def diff_against(repo_path: str, base_branch: str) -> str:
    repo = git.Repo(repo_path)
    try:
        return repo.git.diff(base_branch)
    except Exception:
        return repo.git.diff()


def commit_all(repo_path: str, message: str, paths: list[str] | None = None) -> str:
    """Commits the agent's changes.

    `paths` should be exactly the files the coding agent wrote. Staging everything with
    `git add -A` instead pulled in build output (target/**, node_modules/**) for repos
    that don't gitignore it -- one real 3-file fix produced a 63-file PR full of .class
    artifacts. Falls back to add-all only when the caller can't say what changed.
    """
    repo = git.Repo(repo_path)
    if paths:
        existing = [p for p in paths if os.path.exists(os.path.join(repo_path, p))]
        if existing:
            repo.git.add("--", *existing)
    else:
        repo.git.add(A=True)
    diff = repo.git.diff("--cached")
    if repo.is_dirty(index=True, working_tree=False):
        repo.index.commit(message)
    return diff


def push_branch(repo_path: str, clone_url: str, branch_name: str, token: str = None) -> bool:
    effective = token or GITHUB_TOKEN
    if not effective:
        return False
    clone_url = _github_url(clone_url)
    repo = git.Repo(repo_path)
    try:
        # Use the validated target explicitly: a saved origin.pushurl must not route
        # this job to another repository or an old credential embedded in a URL.
        with repo.git.custom_environment(**_credential_environment(effective)):
            repo.git.push("--porcelain", clone_url, f"{branch_name}:{branch_name}")
    except git.GitCommandError as exc:
        diagnostic = str(exc).lower()
        for status in (401, 403, 404):
            if f"error: {status}" in diagnostic or f"error code: {status}" in diagnostic:
                raise _access_denied(clone_url, status) from None
        raise GitHubAccessError("GitHub rejected the branch upload. Check token write access and repository branch rules. "
                                "The local commit is preserved; no pull request was created.") from None
    return True
