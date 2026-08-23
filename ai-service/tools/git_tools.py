import os

import git

from config import GITHUB_TOKEN, WORKSPACE_DIR


def _local_dir_for(clone_url: str) -> str:
    name = clone_url.rstrip("/").split("/")[-1].removesuffix(".git")
    return os.path.join(WORKSPACE_DIR, name)


def _authenticated_url(clone_url: str, token: str = None) -> str:
    effective = token or GITHUB_TOKEN
    if effective and clone_url.startswith("https://github.com/"):
        return clone_url.replace("https://github.com/", f"https://x-access-token:{effective}@github.com/")
    return clone_url


def clone_or_update(clone_url: str, default_branch: str = "main", token: str = None) -> str:
    """Returns a local working copy path. If it already exists locally
    (e.g. the dummy user-service seeded directly into workspace/), reuse it
    as-is instead of trying to clone/fetch — this keeps local-only testing
    (no GITHUB_TOKEN, no real remote) working end to end.

    `token` is the connecting user's own GitHub token (per-repo, from the backend's
    Repo.githubToken) and takes priority over the process-wide GITHUB_TOKEN env var,
    which only exists as a fallback for the locally-seeded dummy repo.
    """
    local_path = _local_dir_for(clone_url)
    if os.path.isdir(os.path.join(local_path, ".git")):
        try:
            repo = git.Repo(local_path)
            repo.git.checkout(default_branch)
            repo.remotes.origin.pull()
        except Exception:
            pass  # offline / no real remote configured — use working tree as-is
        return local_path

    if os.path.isdir(local_path):
        # Local-only dummy repo (not yet a git repo) — initialize one in place.
        repo = git.Repo.init(local_path, initial_branch=default_branch)
        repo.git.add(A=True)
        if repo.is_dirty() or not repo.head.is_valid():
            repo.index.commit("Initial commit")
        return local_path

    os.makedirs(WORKSPACE_DIR, exist_ok=True)
    git.Repo.clone_from(_authenticated_url(clone_url, token), local_path)
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


def commit_all(repo_path: str, message: str) -> str:
    repo = git.Repo(repo_path)
    repo.git.add(A=True)
    diff = repo.git.diff("--cached")
    if repo.is_dirty(index=True, working_tree=False):
        repo.index.commit(message)
    return diff


def push_branch(repo_path: str, clone_url: str, branch_name: str, token: str = None) -> bool:
    effective = token or GITHUB_TOKEN
    if not effective:
        return False
    repo = git.Repo(repo_path)
    auth_url = _authenticated_url(clone_url, effective)
    if "agent-origin" in [r.name for r in repo.remotes]:
        repo.delete_remote("agent-origin")
    remote = repo.create_remote("agent-origin", auth_url)
    remote.push(refspec=f"{branch_name}:{branch_name}")
    return True
