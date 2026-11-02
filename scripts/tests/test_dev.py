from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import dev


SERVER = '''import http.server,json,os
from pathlib import Path
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'<script src="/@vite/client"></script>')
http.server.HTTPServer(('127.0.0.1',int(os.environ['TEST_PORT'])),Handler).serve_forever()
'''


def unused_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="issue solver lifecycle ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / "frontend").mkdir()
        self.port = unused_port()
        self.service = dev.Service("frontend", "frontend", self.port, "/", "frontend")
        self.manager = dev.Manager(self.root, (self.service,))
        self.manager.env["TEST_PORT"] = str(self.port)
        self.manager.commands = {"frontend": [sys.executable, "-c", SERVER]}
        self.prepare = patch.object(self.manager, "prepare")
        self.prepare.start()
        self.addCleanup(self.prepare.stop)
        self.database = patch.object(self.manager, "database")
        self.database.start()
        self.addCleanup(self.database.stop)

    def tearDown(self):
        for records in self.manager.state.get("services", {}).values():
            for record in records:
                dev.terminate(record, grace=1)

    def test_real_start_repeated_start_and_stop_with_spaces_in_root(self):
        with self.manager.locked():
            self.manager.start()
            original = self.manager.state["services"]["frontend"][0]["pid"]
            self.manager.start()
            self.assertEqual(self.manager.state["services"]["frontend"][0]["pid"], original)
            self.assertTrue(dev.healthy(self.service))
            self.assertEqual((self.root / ".run/state.json").stat().st_mode & 0o777, 0o600)
            self.manager.stop(keep_db=True)
            self.assertFalse(dev.port_busy(self.port))
            self.manager.stop(keep_db=True)

    def test_foreign_process_on_port_is_not_adopted_or_killed(self):
        other = self.root / "unrelated"
        other.mkdir()
        proc = subprocess.Popen([sys.executable, "-c", SERVER], cwd=other, env=self.manager.env,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(lambda: proc.poll() is None and proc.kill())
        deadline = time.monotonic() + 5
        while not dev.healthy(self.service) and time.monotonic() < deadline:
            time.sleep(0.05)
        with self.manager.locked(), self.assertRaisesRegex(dev.DevError, "unverified process"):
            self.manager.start()
        self.assertIsNone(proc.poll())
        proc.terminate()
        proc.wait(timeout=3)

    def test_stale_pid_record_cannot_stop_a_different_process(self):
        proc = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], cwd=self.root)
        try:
            record = {"pid": proc.pid, "identity": "a different process start time", "children": []}
            dev.terminate(record, grace=0.1)
            self.assertIsNone(proc.poll())
        finally:
            proc.terminate()
            proc.wait(timeout=3)

    def test_startup_failure_stops_new_process_and_preserves_existing_data(self):
        second = dev.Service("second", "second", unused_port(), "/", "frontend")
        (self.root / "second").mkdir()
        self.manager.services = (self.service, second)
        self.manager.commands["second"] = [sys.executable, "-c", "import time; time.sleep(.2); raise SystemExit(1)"]
        sentinel = self.root / "database-data"
        sentinel.write_text("preserve")
        with self.manager.locked(), self.assertRaisesRegex(dev.DevError, "did not become healthy"):
            self.manager.start()
        self.assertFalse(dev.port_busy(self.port))
        self.assertEqual(sentinel.read_text(), "preserve")
        self.assertNotIn("frontend", self.manager.state["services"])

    def test_existing_project_service_is_adopted_and_can_be_stopped(self):
        proc = subprocess.Popen([sys.executable, "-c", SERVER], cwd=self.root / "frontend", env=self.manager.env,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 5
            while not dev.healthy(self.service) and time.monotonic() < deadline:
                time.sleep(0.05)
            with self.manager.locked():
                self.manager.start()
                self.assertFalse(self.manager.state["services"]["frontend"][0]["owned"])
                self.manager.stop(keep_db=True)
            proc.wait(timeout=3)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait()

    def test_stop_terminates_runtime_child_processes_too(self):
        pid_file = self.root / "child.pid"
        code = "import subprocess,sys,time,pathlib; p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); pathlib.Path(sys.argv[1]).write_text(str(p.pid)); time.sleep(60)"
        proc = subprocess.Popen([sys.executable, "-c", code, str(pid_file)], cwd=self.root)
        try:
            deadline = time.monotonic() + 5
            while not pid_file.exists() and time.monotonic() < deadline:
                time.sleep(.05)
            child_pid = int(pid_file.read_text())
            record = dict(dev.process_info(proc.pid), children=[], owned=True)
            dev.terminate(record, grace=1)
            proc.wait(timeout=3)
            self.assertIsNone(dev.process_info(child_pid))
        finally:
            if proc.poll() is None:
                dev.terminate(dict(dev.process_info(proc.pid), children=[], owned=True), grace=1)
                proc.wait()

    def test_concurrent_lifecycle_operations_are_rejected(self):
        with self.manager.locked():
            with self.assertRaisesRegex(dev.DevError, "Another start/stop"):
                with dev.Manager(self.root).locked():
                    self.fail("Acquired lock twice")

    def test_healthy_database_survives_unresponsive_docker_management(self):
        self.database.stop()
        with self.manager.locked(), patch.object(self.manager, "probe_database", return_value=True), \
                patch.object(dev, "capture", return_value=subprocess.CompletedProcess([], 1, "", "timeout")), \
                patch.object(self.manager, "logged") as logged:
            self.manager.database()
            self.assertIsNone(self.manager.state["database"])
            logged.assert_not_called()

    def test_stop_database_uses_saved_container_id_and_preserves_volumes(self):
        with self.manager.locked():
            self.manager.state["database"] = "a" * 64
            with patch.object(dev, "capture", return_value=subprocess.CompletedProcess([], 0, "", "")) as run:
                self.manager.stop()
            self.assertEqual(run.call_args.args[0], ["docker", "stop", "--time", "10", "a" * 64])
            self.assertNotIn("down", run.call_args.args[0])

    def test_missing_database_is_started_with_compose_and_saved_after_health_check(self):
        self.database.stop()
        container = "a" * 64
        def docker(args, **kwargs):
            return subprocess.CompletedProcess(args, 0, container if "ps" in args else "ready", "")
        with self.manager.locked(), patch.object(self.manager, "probe_database", side_effect=[False, True, True]), \
                patch.object(dev, "capture", side_effect=docker), patch.object(self.manager, "logged") as logged:
            self.manager.database()
            self.assertEqual(logged.call_args.args[0][-3:], ["up", "-d", "postgres"])
            self.assertEqual(self.manager.state["database"], container)

    def test_failed_database_stop_retains_container_state_for_retry(self):
        with self.manager.locked():
            self.manager.state["database"] = "a" * 64
            with patch.object(dev, "capture", return_value=subprocess.CompletedProcess([], 1, "", "timeout")):
                with self.assertRaisesRegex(dev.DevError, "state was retained"):
                    self.manager.stop()
            self.assertEqual(self.manager.state["database"], "a" * 64)

    def test_stop_leaves_database_when_requested(self):
        with self.manager.locked():
            self.manager.state["database"] = "a" * 64
            with patch.object(dev, "capture") as run:
                self.manager.stop(keep_db=True)
            run.assert_not_called()
            self.assertEqual(self.manager.state["database"], "a" * 64)


if __name__ == "__main__":
    unittest.main()
