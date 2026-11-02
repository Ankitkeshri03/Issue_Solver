import base64
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

import git
import requests
from fastapi.testclient import TestClient

import main
from tools import git_tools
import workflow


@contextmanager
def github_fixture(status=200):
    received = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            received.append((self.path, self.headers.get_all("Authorization")))
            self.send_response(status)
            self.end_headers()

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", received
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


class GitHubAccessTests(unittest.TestCase):
    url = "https://github.com/example/project.git"

    def test_preflight_uses_git_write_advertisement_without_uploading(self):
        with github_fixture() as (base, received), patch.object(git_tools, "_github_url", return_value=base + "/example/project.git"):
            git_tools.check_push_access(self.url, "test-pat")
        self.assertEqual(received, [("/example/project.git/info/refs?service=git-receive-pack",
                                    ["Basic " + base64.b64encode(b"x-access-token:test-pat").decode()])])

    def test_preflight_surfaces_actionable_errors_without_provider_body_or_token(self):
        for status, expected in [(401, "expired"), (403, "Contents"), (404, "repository exists"), (503, "temporarily unavailable")]:
            with self.subTest(status=status), github_fixture(status) as (base, _received), patch.object(git_tools, "_github_url", return_value=base + "/example/project.git"):
                with self.assertRaises(git_tools.GitHubAccessError) as caught:
                    git_tools.check_push_access(self.url, "test-pat")
                self.assertIn(expected, str(caught.exception))
                self.assertNotIn("test-pat", str(caught.exception))
                self.assertEqual(caught.exception.status_code, status if status != 503 else 502)

    def test_network_error_is_safe_and_local_demo_needs_no_network(self):
        with patch.object(git_tools.requests, "get", side_effect=requests.Timeout("test-pat")):
            with self.assertRaisesRegex(git_tools.GitHubAccessError, "Could not reach GitHub") as caught:
                git_tools.check_push_access(self.url, "test-pat")
            self.assertNotIn("test-pat", str(caught.exception))
        with patch.object(git_tools, "GITHUB_TOKEN", ""), patch.object(git_tools.requests, "get") as get:
            git_tools.check_push_access(self.url)
            get.assert_not_called()

    def test_refuses_credential_urls_and_other_hosts(self):
        for url in ["https://test-pat@github.com/example/project.git", "https://evil.example/example/project.git",
                    "https://github.com/example/project.git?token=test-pat", "https://github.com/example/.."]:
            with self.subTest(url=url), patch.object(git_tools.requests, "get") as get:
                with self.assertRaises(ValueError):
                    git_tools.check_push_access(url, "test-pat")
                get.assert_not_called()

    def test_real_git_sends_only_the_job_authorization_over_stale_headers(self):
        with tempfile.TemporaryDirectory() as directory, github_fixture(403) as (base, received):
            repo = git.Repo.init(directory)
            repo.git.config("http.extraHeader", "Authorization: Basic stale-credential")
            env = os.environ.copy()
            credentials = git_tools._credential_environment("test-pat")
            for key, value in credentials.items():
                env[key] = value.replace("https://github.com/", base + "/")
            subprocess.run(["git", "ls-remote", base + "/example/project.git"], cwd=directory,
                           env=env, capture_output=True, timeout=10)
        expected = "Basic " + base64.b64encode(b"x-access-token:test-pat").decode()
        self.assertEqual(len(received[0][1]), 1)
        scheme, credential = received[0][1][0].split(" ", 1)
        self.assertEqual(scheme.lower(), "basic")
        self.assertEqual(credential, expected.split(" ", 1)[1])

    def test_push_uses_validated_job_target_and_sanitizes_rejection(self):
        repo = Mock()
        repo.git.push.side_effect = git.GitCommandError("git push", 128, "fatal: error: 403 test-pat")
        with patch.object(git_tools.git, "Repo", return_value=repo):
            repo.git.custom_environment.return_value.__enter__ = Mock()
            repo.git.custom_environment.return_value.__exit__ = Mock(return_value=False)
            with self.assertRaises(git_tools.GitHubAccessError) as caught:
                git_tools.push_branch("unused", self.url, "fix/issue-1", "test-pat")
        repo.git.push.assert_called_once_with("--porcelain", self.url, "fix/issue-1:fix/issue-1")
        self.assertIn("Read and write", str(caught.exception))
        self.assertNotIn("test-pat", str(caught.exception))

    def test_implementation_http_rejects_access_before_clone_or_ai_work(self):
        error = git_tools._access_denied(self.url, 403)
        with patch.object(main, "AI_SERVICE_TOKEN", ""), patch.object(main, "check_push_access", side_effect=error), \
                patch.object(main, "repository_workspace") as workspace, patch.object(main, "_implement") as implement:
            with TestClient(main.app) as client:
                response = client.post("/implement", json={"ticketId": 1, "issueNumber": 1, "plan": "plan",
                    "repoCloneUrl": self.url, "repoId": 1, "branchName": "fix/issue-1", "githubToken": "test-pat"})
        self.assertEqual(response.status_code, 403)
        self.assertEqual(response.json()["type"], "github_access_error")
        self.assertIn("Contents", response.json()["error"])
        self.assertNotIn("traceback", response.json())
        workspace.assert_not_called()
        implement.assert_not_called()

    def test_late_push_rejection_preserves_passed_tests_and_diff_without_pr(self):
        with patch.object(workflow, "TEST_RUNNER", "local"), patch.object(workflow, "MOCK_LLM", False), \
                patch.object(git_tools, "create_branch"), patch.object(workflow, "apply_fix", return_value=["Example.java"]), \
                patch.object(workflow, "run_tests", return_value=(True, "Tests passed")), \
                patch.object(git_tools, "commit_all", return_value="diff --git Example.java"), \
                patch.object(git_tools, "push_branch", side_effect=git_tools._access_denied(self.url, 403)), \
                patch.object(workflow.github_tools, "create_pull_request") as pr:
            result = workflow.run_implement_workflow("unused", self.url, "example", "project", 1, "issue", "plan", [],
                                                     "fix/issue-1", "main", "test-pat")
        self.assertTrue(result.test_passed)
        self.assertEqual(result.test_output, "Tests passed")
        self.assertEqual(result.diff, "diff --git Example.java")
        self.assertIn("Contents", result.failure_reason)
        self.assertIsNone(result.pr_url)
        pr.assert_not_called()


if __name__ == "__main__":
    unittest.main()
