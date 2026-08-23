import os
import subprocess

# Directories that never contain the project's own pom.xml, and are expensive to walk.
_SKIP_DIRS = {".git", "target", "node_modules", ".mvn", "build", "dist", ".idea", "venv"}


def find_maven_project_dir(repo_path: str, max_depth: int = 3) -> str | None:
    """Locates the directory containing the pom.xml Maven should run from.

    Real-world repos are often monorepos (`backend/` + `frontend/`) where the pom.xml is
    nested rather than at the repo root -- running `mvn test` at the root there fails with
    "no POM in this directory". Searches breadth-first so the shallowest (usually the
    aggregator/parent) pom wins.
    """
    if os.path.isfile(os.path.join(repo_path, "pom.xml")):
        return repo_path

    current_level = [repo_path]
    for _ in range(max_depth):
        next_level = []
        for directory in current_level:
            try:
                entries = sorted(os.listdir(directory))
            except OSError:
                continue
            for entry in entries:
                if entry in _SKIP_DIRS or entry.startswith("."):
                    continue
                candidate = os.path.join(directory, entry)
                if not os.path.isdir(candidate):
                    continue
                if os.path.isfile(os.path.join(candidate, "pom.xml")):
                    return candidate
                next_level.append(candidate)
        if not next_level:
            break
        current_level = next_level
    return None


def run_tests(
    repo_path: str,
    test_filter: str | None = None,
    timeout_seconds: int = 300,
    project_dir: str | None = None,
) -> tuple[bool, str]:
    work_dir = project_dir or find_maven_project_dir(repo_path)
    if work_dir is None:
        return False, (
            f"No pom.xml found in {repo_path} (searched up to 3 levels deep). "
            "This agent currently supports Maven-based Java projects only."
        )

    mvnw = os.path.join(work_dir, "mvnw")
    cmd = [mvnw if os.path.isfile(mvnw) and os.access(mvnw, os.X_OK) else "mvn", "-B", "-q", "test"]
    if test_filter:
        cmd.append(f"-Dtest={test_filter}")
    try:
        result = subprocess.run(
            cmd,
            cwd=work_dir,
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired as e:
        return False, f"mvn test timed out after {timeout_seconds}s\n{e.output or ''}"

    output = (result.stdout or "") + (result.stderr or "")
    passed = result.returncode == 0
    # trim to keep step messages/DB rows reasonable
    return passed, f"[mvn ran in: {work_dir}]\n" + output[-8000:]
