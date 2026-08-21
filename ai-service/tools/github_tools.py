import requests

from config import GITHUB_TOKEN

API_BASE = "https://api.github.com"


def create_pull_request(owner: str, repo: str, title: str, head: str, base: str, body: str) -> str | None:
    if not GITHUB_TOKEN:
        return None
    resp = requests.post(
        f"{API_BASE}/repos/{owner}/{repo}/pulls",
        headers={
            "Authorization": f"Bearer {GITHUB_TOKEN}",
            "Accept": "application/vnd.github+json",
        },
        json={"title": title, "head": head, "base": base, "body": body},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()["html_url"]
