"""Run the candidate revision in its repository's GitHub Actions workflow.

Only repository/commit IDs and a test filter go to Actions. The user's PAT and
the application's database, Gemini and email credentials stay on the API host.
"""
import io
import json
import time
import uuid
import zipfile

import requests

WORKFLOW = "issue-solver-tests.yml"


def _check(response, operation):
    if response.status_code in (401, 403):
        raise RuntimeError(f"GitHub denied {operation}. Grant the connected token Contents, Pull requests and Actions read/write permissions.")
    if response.status_code == 404:
        raise RuntimeError(f"GitHub could not find {operation}. Use Set up test runner on the repository page and check token access.")
    response.raise_for_status()
    return response


def _read_result(response, expected_job, expected_sha):
    chunks, size = [], 0
    for chunk in response.iter_content(16384):
        size += len(chunk)
        if size > 1024 * 1024:
            response.close()
            raise RuntimeError("Actions test artifact exceeds the allowed size")
        chunks.append(chunk)
    with zipfile.ZipFile(io.BytesIO(b"".join(chunks))) as archive:
        info = archive.getinfo("issue-solver-result.json")
        if info.file_size > 65536:
            raise RuntimeError("Actions test result exceeds the allowed size")
        result = json.loads(archive.read(info))
    if result.get("job_id") != expected_job or result.get("sha") != expected_sha:
        raise RuntimeError("Actions returned a result for a different job or commit")
    if not isinstance(result.get("passed"), bool) or not isinstance(result.get("output"), str):
        raise RuntimeError("Actions returned an invalid test result")
    return result


def run_github_tests(full_name, sha, token, test_filter=None, timeout_seconds=600, session=None):
    if not token:
        return False, "Connect a GitHub token with Actions read/write permission before running remote tests."
    session = session or requests.Session()
    session.headers.update({"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                            "X-GitHub-Api-Version": "2022-11-28"})
    base = f"https://api.github.com/repos/{full_name}"
    job_id = str(uuid.uuid4())
    deadline = time.monotonic() + timeout_seconds
    run = None
    try:
        repo = _check(session.get(base, timeout=30), "repository metadata").json()
        workflow_url = f"{base}/actions/workflows/{WORKFLOW}"
        _check(session.get(workflow_url, timeout=30), "test runner workflow")
        _check(session.post(f"{workflow_url}/dispatches", json={
            "ref": repo["default_branch"],
            "inputs": {"candidate_sha": sha, "job_id": job_id, "test_filter": test_filter or ""},
        }, timeout=30), "test workflow dispatch")

        while time.monotonic() < deadline:
            if run is None:
                runs = _check(session.get(f"{workflow_url}/runs", params={"event": "workflow_dispatch", "per_page": 100}, timeout=30), "test workflow runs").json()
                run = next((item for item in runs.get("workflow_runs", [])
                            if item.get("display_title") == f"Issue Solver tests {job_id}"), None)
            else:
                run = _check(session.get(f"{base}/actions/runs/{run['id']}", timeout=30), "test run status").json()
            if run and run["status"] == "completed":
                artifacts = _check(session.get(f"{base}/actions/runs/{run['id']}/artifacts", timeout=30), "test result artifacts").json()
                artifact = next((a for a in artifacts.get("artifacts", [])
                                 if a["name"] == f"issue-solver-{job_id}" and not a.get("expired")), None)
                if artifact is None:
                    return False, f"The test runner ended with {run.get('conclusion')} without a result artifact. Inspect {run['html_url']}"
                response = _check(session.get(f"{base}/actions/artifacts/{artifact['id']}/zip", timeout=30, stream=True), "test result download")
                result = _read_result(response, job_id, sha)
                output = f"[GitHub Actions: {run['html_url']}]\n[Candidate commit: {sha}]\n" + result["output"][-8000:]
                return result["passed"] and run.get("conclusion") == "success", output
            time.sleep(5)
        if run:
            # Stop this specific job rather than leave a timed-out build consuming quota.
            session.post(f"{base}/actions/runs/{run['id']}/cancel", timeout=15)
        return False, f"GitHub Actions did not finish within {timeout_seconds}s. Retry after checking the repository's Actions page."
    except (requests.RequestException, RuntimeError, KeyError, ValueError, zipfile.BadZipFile) as exc:
        return False, f"Remote tests could not complete: {exc}"
