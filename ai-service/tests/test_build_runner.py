import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agents.test_agent import describe_test_command, find_build_project, run_tests
from tools.file_tools import list_java_files


class BuildRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="build runner ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def write(self, name, content=""):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def test_recognizes_maven_and_both_gradle_dsls(self):
        for marker, tool in [("pom.xml", "maven"), ("build.gradle", "gradle"),
                             ("build.gradle.kts", "gradle"), ("settings.gradle", "gradle"),
                             ("settings.gradle.kts", "gradle")]:
            with self.subTest(marker=marker):
                path = self.write(marker)
                project = find_build_project(str(self.root))
                self.assertEqual((project.directory, project.tool), (self.root, tool))
                path.unlink()

    def test_shallowest_build_wins_over_alphabetically_earlier_deep_build(self):
        self.write("a/deep/pom.xml")
        self.write("z/build.gradle.kts")
        self.assertEqual(find_build_project(str(self.root)).directory, self.root / "z")

    def test_settings_only_root_keeps_gradle_subprojects_together(self):
        self.write("settings.gradle.kts", 'include("api", "service")')
        self.write("api/build.gradle.kts")
        self.write("service/build.gradle.kts")
        self.assertEqual(find_build_project(str(self.root)).directory, self.root)

    def test_search_depth_includes_three_levels_but_not_four(self):
        marker = self.write("a/b/c/pom.xml")
        self.assertEqual(find_build_project(str(self.root)).directory, marker.parent)
        marker.unlink()
        self.write("a/b/c/d/build.gradle")
        self.assertIsNone(find_build_project(str(self.root)))

    def test_ignores_generated_hidden_and_symlinked_builds(self):
        for directory in ["target", "build", ".gradle", ".git", "node_modules", "venv", "buildSrc"]:
            self.write(f"{directory}/pom.xml")
        self.write(".hidden-project/build.gradle")
        (self.root / "linked-project").symlink_to(self.root / ".hidden-project", target_is_directory=True)
        self.assertIsNone(find_build_project(str(self.root)))

    def test_maven_takes_precedence_when_both_builds_exist(self):
        self.write("pom.xml")
        self.write("build.gradle")
        self.assertEqual(find_build_project(str(self.root)).tool, "maven")

    def test_sibling_selection_is_deterministic(self):
        self.write("z/pom.xml")
        self.write("a/build.gradle")
        self.assertEqual(find_build_project(str(self.root)).directory, self.root / "a")

    def test_system_tools_and_filter_syntax(self):
        selector = "UserServiceTest#first+second,UserControllerTest"
        for marker, expected in [
            ("pom.xml", ["mvn", "-B", "-q", "test", f"-Dtest={selector}"]),
            ("build.gradle", ["gradle", "--no-daemon", "--console=plain", "test",
                              "--tests", "UserServiceTest.first", "--tests", "UserServiceTest.second",
                              "--tests", "UserControllerTest"]),
        ]:
            with self.subTest(marker=marker):
                path = self.write(marker)
                with patch("agents.test_agent.subprocess.run", return_value=subprocess.CompletedProcess([], 0, "OK", "")) as run:
                    passed, output = run_tests(str(self.root), test_filter=selector)
                self.assertTrue(passed, output)
                self.assertEqual(run.call_args.args[0], expected)
                self.assertEqual(run.call_args.kwargs["cwd"], str(self.root))
                path.unlink()

    def test_no_filter_runs_entire_suite(self):
        for marker in ["pom.xml", "build.gradle.kts"]:
            with self.subTest(marker=marker):
                path = self.write(marker)
                with patch("agents.test_agent.subprocess.run", return_value=subprocess.CompletedProcess([], 0, "", "")) as run:
                    run_tests(str(self.root))
                cmd = run.call_args.args[0]
                self.assertIn("test", cmd)
                self.assertFalse(any(arg.startswith("-Dtest") or arg == "--tests" for arg in cmd))
                path.unlink()

    def test_executes_wrappers_with_spaces_and_without_executable_bit(self):
        # Exercise real process launching rather than mocking subprocess.
        for marker, wrapper in [("pom.xml", "mvnw"), ("build.gradle.kts", "gradlew")]:
            for executable in [False, True]:
                with self.subTest(wrapper=wrapper, executable=executable):
                    build = self.write(f"backend/{marker}")
                    script = self.write(f"backend/{wrapper}", '#!/bin/sh\npwd\nprintf "%s\\n" "$@"\n')
                    script.chmod(0o755 if executable else 0o644)
                    relative_repo = os.path.relpath(self.root)
                    passed, output = run_tests(relative_repo)
                    self.assertTrue(passed, output)
                    self.assertIn(str(self.root / "backend"), output)
                    self.assertIn("\ntest\n", output)
                    self.assertEqual(bool(script.stat().st_mode & 0o111), executable)
                    build.unlink()
                    script.unlink()

    def test_explicit_project_directory_overrides_detection(self):
        self.write("pom.xml")
        self.write("backend/build.gradle")
        self.write("backend/gradlew", "#!/bin/sh\necho gradle-backend\n")
        passed, output = run_tests(str(self.root), project_dir=str(self.root / "backend"))
        self.assertTrue(passed, output)
        self.assertIn("gradle-backend", output)

    def test_nonzero_exit_returns_failure_and_both_output_streams(self):
        self.write("build.gradle")
        self.write("gradlew", "#!/bin/sh\necho assertion-failed\necho diagnostic >&2\nexit 1\n")
        passed, output = run_tests(str(self.root))
        self.assertFalse(passed)
        self.assertIn("assertion-failed", output)
        self.assertIn("diagnostic", output)

    def test_missing_tool_returns_actionable_failure(self):
        for marker, wrapper in [("pom.xml", "mvnw"), ("build.gradle", "gradlew")]:
            with self.subTest(marker=marker):
                path = self.write(marker)
                with patch("agents.test_agent.subprocess.run", side_effect=FileNotFoundError("tool not installed")):
                    passed, output = run_tests(str(self.root))
                self.assertFalse(passed)
                self.assertIn(wrapper, output)
                self.assertIn("AI-service host", output)
                path.unlink()

    def test_timeout_preserves_byte_output_and_tool_context(self):
        self.write("build.gradle")
        timeout = subprocess.TimeoutExpired("gradle", 1, output=b"partial stdout", stderr=b"partial stderr")
        with patch("agents.test_agent.subprocess.run", side_effect=timeout):
            passed, output = run_tests(str(self.root), timeout_seconds=1)
        self.assertFalse(passed)
        self.assertIn("gradle ran in:", output)
        self.assertIn("timed out after 1s", output)
        self.assertIn("partial stdoutpartial stderr", output)

    def test_unsupported_project_does_not_launch_a_process(self):
        self.write("package.json", "{}")
        with patch("agents.test_agent.subprocess.run") as run:
            passed, output = run_tests(str(self.root))
        self.assertFalse(passed)
        self.assertIn("No Maven or Gradle project found", output)
        run.assert_not_called()

    def test_output_is_bounded_and_keeps_final_failure(self):
        self.write("pom.xml")
        result = subprocess.CompletedProcess([], 1, "x" * 10000, "final-error")
        with patch("agents.test_agent.subprocess.run", return_value=result):
            passed, output = run_tests(str(self.root))
        self.assertFalse(passed)
        self.assertTrue(output.endswith("final-error"))
        self.assertLess(len(output), 8500)

    def test_planning_command_uses_detected_wrapper(self):
        self.write("backend/build.gradle.kts")
        self.write("backend/gradlew", "#!/bin/sh\n")
        command = describe_test_command(str(self.root))
        self.assertIn("gradlew", command)
        self.assertTrue(command.endswith(" test"))
        self.assertNotIn("mvn", command)

    def test_java_indexing_excludes_maven_and_gradle_outputs(self):
        self.write("backend/src/main/java/App.java")
        self.write("backend/src/test/java/AppTest.java")
        for directory in ["target/generated-sources", "build/generated", ".gradle", "node_modules"]:
            self.write(f"backend/{directory}/Generated.java")
        self.assertEqual(list_java_files(str(self.root)), [
            "backend/src/main/java/App.java", "backend/src/test/java/AppTest.java",
        ])


if __name__ == "__main__":
    unittest.main()
