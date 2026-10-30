import os
import shlex
import subprocess
from dataclasses import dataclass
from pathlib import Path


_SKIP_DIRS = {"target", "node_modules", "build", "dist", "venv", "buildSrc"}
_GRADLE_FILES = ("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")


@dataclass(frozen=True)
class BuildProject:
    directory: Path
    tool: str


def _project_at(directory: Path) -> BuildProject | None:
    # Preserve Maven behavior when a directory contains both build systems.
    if (directory / "pom.xml").is_file():
        return BuildProject(directory, "maven")
    if any((directory / name).is_file() for name in _GRADLE_FILES):
        return BuildProject(directory, "gradle")
    return None


def find_build_project(repo_path: str, max_depth: int = 3) -> BuildProject | None:
    """Find the shallowest Maven/Gradle build, including multi-project Gradle roots.

    Search breadth-first and sort sibling directories for deterministic selection.
    Settings files identify Gradle roots even when only subprojects have build files.
    Hidden/generated directories and directory symlinks are not traversed.
    """
    current_level = [Path(repo_path).resolve()]
    for depth in range(max_depth + 1):
        next_level = []
        for directory in current_level:
            project = _project_at(directory)
            if project:
                return project
            if depth == max_depth:
                continue
            try:
                with os.scandir(directory) as entries:
                    children = sorted(entries, key=lambda entry: entry.name)
                next_level.extend(
                    Path(entry.path) for entry in children
                    if entry.name not in _SKIP_DIRS
                    and not entry.name.startswith(".")
                    and entry.is_dir(follow_symlinks=False)
                )
            except OSError:
                continue
        current_level = next_level
        if not current_level:
            break
    return None


def _launcher(project: BuildProject) -> list[str]:
    executable = "mvn" if project.tool == "maven" else "gradle"
    wrapper = project.directory / ("mvnw" if project.tool == "maven" else "gradlew")
    if wrapper.is_file():
        # A clone can lose its executable bit. Use the wrapper without changing files.
        return [str(wrapper)] if os.access(wrapper, os.X_OK) else ["sh", str(wrapper)]
    return [executable]


def describe_test_command(repo_path: str) -> str:
    project = find_build_project(repo_path)
    if project is None:
        return "the project's Maven or Gradle test command"
    return shlex.join([*_launcher(project), "test"])


def _gradle_test_filters(test_filter: str) -> list[str]:
    """Translate the demo catalog's Class#method+method,OtherClass selectors."""
    args = []
    for selector in test_filter.split(","):
        class_name, separator, methods = selector.strip().partition("#")
        if not class_name:
            continue
        if separator:
            patterns = [f"{class_name}.{method.strip()}" for method in methods.split("+") if method.strip()]
        else:
            patterns = [class_name]
        for pattern in patterns:
            args.extend(["--tests", pattern])
    return args


def _output_text(output: str | bytes | None) -> str:
    # TimeoutExpired may contain bytes even when subprocess.run uses text=True.
    if isinstance(output, bytes):
        return output.decode("utf-8", errors="replace")
    return output or ""


def run_tests(
    repo_path: str,
    test_filter: str | None = None,
    timeout_seconds: int = 300,
    project_dir: str | None = None,
) -> tuple[bool, str]:
    project = _project_at(Path(project_dir).resolve()) if project_dir else find_build_project(repo_path)
    if project is None:
        location = project_dir or repo_path
        search_note = "" if project_dir else " (searched up to 3 levels deep)"
        return False, (
            f"No Maven or Gradle project found in {location}{search_note}. "
            "Expected pom.xml, build.gradle, build.gradle.kts, settings.gradle, or settings.gradle.kts. "
            "This agent supports Java projects built with Maven or Gradle."
        )

    cmd = _launcher(project)
    if project.tool == "maven":
        cmd.extend(["-B", "-q", "test"])
        if test_filter:
            cmd.append(f"-Dtest={test_filter}")
    else:
        cmd.extend(["--no-daemon", "--console=plain", "test"])
        if test_filter:
            cmd.extend(_gradle_test_filters(test_filter))

    header = f"[{project.tool} ran in: {project.directory}]\n[command: {shlex.join(cmd)}]\n"
    try:
        result = subprocess.run(
            cmd,
            cwd=str(project.directory),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired as exc:
        output = _output_text(exc.stdout) + _output_text(exc.stderr)
        return False, header + f"Test run timed out after {timeout_seconds}s\n" + output[-8000:]
    except OSError as exc:
        wrapper = "mvnw" if project.tool == "maven" else "gradlew"
        executable = "mvn" if project.tool == "maven" else "gradle"
        return False, header + (
            f"Could not start {project.tool}: {exc}. "
            f"Commit a working {wrapper} wrapper or install {executable} on the AI-service host, "
            "and ensure a compatible JDK is available."
        )

    output = (result.stdout or "") + (result.stderr or "")
    return result.returncode == 0, header + output[-8000:]
