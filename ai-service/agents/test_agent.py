import os
import subprocess


def run_tests(repo_path: str, test_filter: str | None = None, timeout_seconds: int = 300) -> tuple[bool, str]:
    mvnw = f"{repo_path}/mvnw"
    cmd = [mvnw if os.path.isfile(mvnw) and os.access(mvnw, os.X_OK) else "mvn", "-B", "-q", "test"]
    if test_filter:
        cmd.append(f"-Dtest={test_filter}")
    try:
        result = subprocess.run(
            cmd,
            cwd=repo_path,
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired as e:
        return False, f"mvn test timed out after {timeout_seconds}s\n{e.output or ''}"

    output = (result.stdout or "") + (result.stderr or "")
    passed = result.returncode == 0
    # trim to keep step messages/DB rows reasonable
    return passed, output[-8000:]
