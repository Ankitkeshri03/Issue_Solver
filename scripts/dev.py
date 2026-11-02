#!/usr/bin/env python3
"""Local service lifecycle. Requires Python 3.9+, macOS or Linux; no third-party imports."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from dataclasses import dataclass
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]


class DevError(Exception):
    pass


@dataclass(frozen=True)
class Service:
    name: str
    directory: str
    port: int
    path: str
    kind: str


SERVICES = (
    Service("ai", "ai-service", 8000, "/health", "ai"),
    Service("backend", "backend", 8081, "/actuator/health", "backend"),
    Service("frontend", "frontend", 5173, "/", "frontend"),
)


def say(message):
    print(message, flush=True)


def capture(args, cwd=None, timeout=5, env=None):
    try:
        return subprocess.run(args, cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return subprocess.CompletedProcess(args, 1, "", "Command unavailable or timed out")


def process_info(pid):
    result = capture(["ps", "-p", str(pid), "-o", "stat=", "-o", "lstart="])
    value = result.stdout.strip()
    if result.returncode or not value or value.split()[0].startswith("Z"):
        return None
    return {"pid": int(pid), "identity": value.split(None, 1)[1]}


def matches(record):
    return process_info(record["pid"]) == {"pid": record["pid"], "identity": record["identity"]}


def descendants(pid):
    rows = capture(["ps", "-axo", "pid=,ppid="]).stdout.splitlines()
    pairs = [tuple(map(int, row.split())) for row in rows if len(row.split()) == 2]
    found, pending = [], [pid]
    while pending:
        parent = pending.pop()
        children = [child for child, ancestor in pairs if ancestor == parent]
        found.extend(children)
        pending.extend(children)
    return found


def process_cwd(pid):
    proc = Path(f"/proc/{pid}/cwd")
    if proc.exists():
        return proc.resolve()
    result = capture(["lsof", "-a", "-p", str(pid), "-d", "cwd", "-Fn"])
    return next((Path(line[1:]).resolve() for line in result.stdout.splitlines() if line.startswith("n")), None)


def healthy(service):
    try:
        with urlopen(f"http://127.0.0.1:{service.port}{service.path}", timeout=2) as response:
            body = response.read(32768)
            if response.status != 200:
                return False
            if service.kind == "frontend":
                return b"/@vite/client" in body
            data = json.loads(body)
            return data.get("status") == "UP" and (service.kind != "ai" or isinstance(data.get("mockLlm"), bool))
    except (OSError, ValueError):
        return False


def port_busy(port):
    for address, family in [("127.0.0.1", socket.AF_INET), ("::1", socket.AF_INET6)]:
        with socket.socket(family) as sock:
            sock.settimeout(0.3)
            if sock.connect_ex((address, port)) == 0:
                return True
    return False


def terminate(record, grace=8):
    """Validate process identity before every signal, protecting against stale PID files."""
    root_alive = matches(record)
    candidates = list(record.get("children", []))
    if root_alive:
        candidates += [info for pid in descendants(record["pid"]) if (info := process_info(pid))]
        candidates.append({"pid": record["pid"], "identity": record["identity"]})
    unique = {entry["pid"]: entry for entry in candidates}
    for sig in (signal.SIGTERM, signal.SIGKILL):
        for entry in reversed(list(unique.values())):
            if matches(entry):
                try:
                    os.kill(entry["pid"], sig)
                except ProcessLookupError:
                    pass
        deadline = time.monotonic() + (grace if sig == signal.SIGTERM else 1)
        while any(matches(entry) for entry in unique.values()) and time.monotonic() < deadline:
            time.sleep(0.2)
    if any(matches(entry) for entry in unique.values()):
        raise DevError("A tracked process did not stop; its state was retained")


class Manager:
    def __init__(self, root=ROOT, services=SERVICES):
        self.root = Path(root).resolve()
        self.runtime = self.root / ".run"
        self.logs = self.runtime / "logs"
        self.services = services
        self.state = {"root": str(self.root), "services": {}, "database": None}
        self.commands = {}
        self.processes = {}
        self.env = os.environ.copy()
        self.python = self.root / "ai-service/venv/bin/python"

    @contextmanager
    def locked(self):
        self.logs.mkdir(parents=True, exist_ok=True)
        self.runtime.chmod(0o700)
        with (self.runtime / "lifecycle.lock").open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise DevError("Another start/stop operation is running; wait for it to finish")
            state_file = self.runtime / "state.json"
            if state_file.exists():
                try:
                    self.state = json.loads(state_file.read_text())
                    if self.state.get("root") != str(self.root):
                        raise ValueError()
                except (ValueError, OSError):
                    raise DevError("Invalid .run/state.json; refusing to act on unverified process IDs")
            yield

    def save(self):
        temporary = self.runtime / "state.tmp"
        temporary.write_text(json.dumps(self.state, indent=2) + "\n")
        temporary.chmod(0o600)
        temporary.replace(self.runtime / "state.json")

    def logged(self, args, name, cwd=None, timeout=300):
        log_path = self.logs / f"{name}.log"
        with log_path.open("a") as stream:
            log_path.chmod(0o600)
            process = subprocess.Popen(args, cwd=cwd or self.root, env=self.env,
                                       stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT,
                                       start_new_session=True)
            try:
                code = process.wait(timeout=timeout)
            except BaseException:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                    process.wait(timeout=3)
                except (ProcessLookupError, subprocess.TimeoutExpired):
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                raise
        if code:
            raise DevError(f"{name} failed. See {log_path}")

    def prepare(self):
        for tool in ("node", "npm", "docker", "lsof"):
            if not shutil.which(tool):
                raise DevError(f"Install {tool} before starting the project; see RUNNING.md")
        java_home = self.env.get("JAVA_HOME", "")
        if platform.system() == "Darwin":
            candidate = capture(["/usr/libexec/java_home", "-v", "21"]).stdout.strip()
            java_home = candidate or java_home
        java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        version = capture([java, "-version"]) if java else None
        if version is None or not re.search(r'version "21[.\"]', version.stderr + version.stdout):
            raise DevError("JDK 21 is required. Install it or set JAVA_HOME to your JDK 21 directory")
        if java_home:
            self.env["JAVA_HOME"] = java_home
            self.env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + self.env.get("PATH", "")
        if not self.python.exists():
            candidate = next((shutil.which(name) for name in ("python3.11", "python3.12") if shutil.which(name)), None)
            if not candidate:
                raise DevError("Install Python 3.11 or 3.12 to create the AI-service virtual environment")
            say("Creating the AI-service virtual environment…")
            self.logged([candidate, "-m", "venv", str(self.python.parent.parent)], "dependencies")
        requirements = self.root / "ai-service/requirements.txt"
        digest = hashlib.sha256(requirements.read_bytes()).hexdigest()
        stamp = self.runtime / "ai-requirements.sha256"
        imports = capture([str(self.python), "-c", "import uvicorn, fastapi, psycopg2, dotenv, numpy, requests, git, langchain_google_genai"], timeout=20)
        if imports.returncode or (stamp.exists() and stamp.read_text() != digest):
            say("Installing AI-service dependencies (first setup can take a few minutes)…")
            self.logged([str(self.python), "-m", "pip", "install", "-r", str(requirements)], "dependencies", timeout=600)
        stamp.write_text(digest)
        lock = self.root / "frontend/package-lock.json"
        digest = hashlib.sha256(lock.read_bytes()).hexdigest()
        stamp = self.runtime / "frontend-lock.sha256"
        if not (self.root / "frontend/node_modules/vite/bin/vite.js").exists() or (stamp.exists() and stamp.read_text() != digest):
            say("Installing frontend dependencies…")
            self.logged(["npm", "ci"], "dependencies", self.root / "frontend")
        stamp.write_text(digest)
        self.commands = {
            "ai": [str(self.python), "-m", "uvicorn", "main:app", "--reload", "--host", "127.0.0.1", "--port", "8000"],
            "backend": ["sh", "./mvnw", "--batch-mode", "--no-transfer-progress", "spring-boot:run"],
            "frontend": ["npm", "run", "dev", "--", "--host", "127.0.0.1", "--port", "5173", "--strictPort"],
        }

    def probe_database(self):
        code = """import psycopg2
from config import DATABASE_URL
with psycopg2.connect(DATABASE_URL, connect_timeout=3) as conn, conn.cursor() as cur:
    cur.execute("SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname='vector')")
    raise SystemExit(0 if cur.fetchone()[0] else 1)
"""
        return capture([str(self.python), "-c", code], self.root / "ai-service", timeout=5, env=self.env).returncode == 0

    def compose(self, *args):
        return ["docker", "compose", "--project-directory", str(self.root), "-f", str(self.root / "docker-compose.yml"), *args]

    def database(self):
        ready = self.probe_database()
        docker_ready = capture(["docker", "info", "--format", "{{.ServerVersion}}"], timeout=4).returncode == 0
        if ready:
            say("PostgreSQL + pgvector: ready (reusing existing database)")
        else:
            if not docker_ready:
                say("Starting Docker Desktop; waiting up to 90 seconds…")
                result = capture(["docker", "desktop", "start", "--detach"], timeout=10)
                if result.returncode:
                    raise DevError("Start Docker Desktop/Engine manually, then rerun ./start.sh. No database data was removed")
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    if capture(["docker", "info", "--format", "{{.ServerVersion}}"], timeout=3).returncode == 0:
                        docker_ready = True
                        break
                    time.sleep(2)
            if not docker_ready:
                raise DevError("Docker did not become ready. Check Docker Desktop and .run/logs before retrying")
            if capture(["docker", "compose", "version"], timeout=5).returncode:
                raise DevError("Docker Compose v2 is required")
            say("Starting PostgreSQL (the existing data volume is preserved)…")
            self.logged(self.compose("up", "-d", "postgres"), "postgres", timeout=180)
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline and not self.probe_database():
                time.sleep(2)
            if not self.probe_database():
                raise DevError("PostgreSQL/pgvector is not ready. Check .run/logs/postgres.log and your database configuration")
            say("PostgreSQL + pgvector: ready")
        if docker_ready:
            result = capture(self.compose("ps", "-q", "postgres"), timeout=5)
            container = result.stdout.strip()
            if result.returncode == 0 and re.fullmatch(r"[a-f0-9]{12,64}", container):
                self.state["database"] = container
        else:
            say("Note: Docker management API is unavailable. The database works; stop.sh will leave it running")
            self.state["database"] = None
        self.save()

    def existing(self, service):
        result = capture(["lsof", "-nP", f"-iTCP:{service.port}", "-sTCP:LISTEN", "-t"])
        pids = {int(value) for value in result.stdout.split() if value.isdigit()}
        expected = self.root / service.directory
        if not pids or any(process_cwd(pid) != expected for pid in pids):
            raise DevError(f"Port {service.port} is occupied by an unverified process. Stop it manually; it will not be killed")
        roots = set()
        for pid in pids:
            # Follow only same-project runtime parents, never an interactive shell or IDE.
            while True:
                parent = capture(["ps", "-p", str(pid), "-o", "ppid="]).stdout.strip()
                if not parent.isdigit():
                    break
                executable = Path(capture(["ps", "-p", parent, "-o", "comm="]).stdout.strip()).name.lower()
                if process_cwd(int(parent)) != expected or not executable.startswith(("java", "python", "node", "npm", "uvicorn", "mvn")):
                    break
                pid = int(parent)
            roots.add(pid)
        return [dict(info, children=[], owned=False) for pid in roots if (info := process_info(pid))]

    def start(self):
        self.prepare()
        self.database()
        newly_started = []
        try:
            for service in self.services:
                if port_busy(service.port):
                    records = self.existing(service)
                    # Keep the original supervisor PID when this script already owns the service.
                    old = self.state["services"].get(service.name, [])
                    records = old if old and all(matches(entry) for entry in old) else records
                    self.state["services"][service.name] = records
                    self.save()
                    if not healthy(service):
                        raise DevError(f"{service.name} owns port {service.port} but is unhealthy. Check its console or run ./stop.sh before retrying")
                    say(f"{service.name}: ready (reusing project service on {service.port})")
                else:
                    log_path = self.logs / f"{service.name}.log"
                    with log_path.open("a") as stream:
                        log_path.chmod(0o600)
                        process = subprocess.Popen(self.commands[service.name], cwd=self.root / service.directory,
                                                   env=self.env, stdin=subprocess.DEVNULL, stdout=stream,
                                                   stderr=subprocess.STDOUT, start_new_session=True)
                    self.processes[process.pid] = process
                    info = process_info(process.pid)
                    if info is None:
                        raise DevError(f"{service.name} exited immediately; see {log_path}")
                    records = [dict(info, owned=True, children=[])]
                    newly_started.append((service.name, records[0]))
                    self.state["services"][service.name] = records
                    self.save()
                    say(f"Starting {service.name} on {service.port}… log: {log_path}")
                    deadline = time.monotonic() + (180 if service.name == "backend" else 60)
                    while time.monotonic() < deadline and process.poll() is None and not healthy(service):
                        time.sleep(1)
                    if process.poll() is not None or not healthy(service):
                        raise DevError(f"{service.name} did not become healthy; see {log_path}")
                    say(f"{service.name}: ready")
                for record in records:
                    record["children"] = [info for pid in descendants(record["pid"]) if (info := process_info(pid))]
                self.state["services"][service.name] = records
                self.save()
        except BaseException:
            for name, record in reversed(newly_started):
                terminate(record)
                process = self.processes.pop(record["pid"], None)
                if process is not None:
                    process.wait(timeout=2)
                self.state["services"].pop(name, None)
            self.save()
            raise
        say(f"\nOpen http://localhost:5173\nLogs: {self.logs}\nStop: ./stop.sh (or ./stop.sh --keep-db)")

    def stop(self, keep_db=False):
        for name in reversed([service.name for service in self.services]):
            records = self.state["services"].get(name, [])
            for record in records:
                terminate(record)
                process = self.processes.pop(record["pid"], None)
                if process is not None:
                    process.wait(timeout=2)
            if records:
                say(f"{name}: stopped (only tracked project processes)")
                self.state["services"].pop(name, None)
                self.save()
        container = self.state.get("database")
        if container and not keep_db:
            result = capture(["docker", "stop", "--time", "10", container], timeout=20)
            if result.returncode:
                raise DevError("Application processes stopped, but Docker could not stop PostgreSQL. Its state was retained; data is intact")
            self.state["database"] = None
            self.save()
            say("PostgreSQL: stopped; data volume preserved")
        elif keep_db:
            say("PostgreSQL: left running (--keep-db)")
        else:
            say("No managed database to stop. Unverified containers and Docker Desktop are left alone")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "stop", "status"))
    parser.add_argument("--keep-db", action="store_true", help="Leave PostgreSQL running when stopping")
    args = parser.parse_args()
    manager = Manager()
    try:
        with manager.locked():
            if args.action == "start":
                manager.start()
            elif args.action == "stop":
                manager.stop(args.keep_db)
            else:
                for service in manager.services:
                    say(f"{service.name} ({service.port}): {'ready' if healthy(service) else 'not ready'}")
                say(f"PostgreSQL + pgvector: {'ready' if manager.probe_database() else 'not ready'}")
    except (DevError, OSError, subprocess.TimeoutExpired) as exc:
        say(f"ERROR: {exc}")
        return 1
    except KeyboardInterrupt:
        say("Startup interrupted. New application processes were stopped; database data was preserved")
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
