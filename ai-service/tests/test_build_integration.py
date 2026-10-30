"""Opt-in smoke tests with real Java compilation and JUnit execution.

RUN_BUILD_INTEGRATION=1 python3.11 -m unittest discover -s tests -v
Requires Maven, Gradle, a compatible JDK, and network access for dependencies.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from agents.test_agent import run_tests


_TEST_SOURCE = """package example;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class SmokeTest {
    @Test public void passesOne() { assertEquals(2, 1 + 1); }
    @Test public void passesTwo() { assertEquals(4, 2 + 2); }
    @Test public void initiallyFails() { assertEquals(3, 1 + 1); }
}
"""


@unittest.skipUnless(os.getenv("RUN_BUILD_INTEGRATION") == "1", "set RUN_BUILD_INTEGRATION=1 for real Java builds")
class RealJavaBuildTests(unittest.TestCase):
    def setUp(self):
        for tool in ["mvn", "gradle"]:
            if not shutil.which(tool):
                self.skipTest(f"{tool} must be on PATH")
        self.temp = tempfile.TemporaryDirectory(prefix="java build smoke ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def verify_fail_filter_fix(self, build_dir, source_dir, report_dir):
        source = self.write(f"{source_dir}/src/test/java/example/SmokeTest.java", _TEST_SOURCE)
        passed, output = run_tests(str(self.root))
        self.assertFalse(passed, output)
        self.assertIn("SmokeTest", output)
        passed, output = run_tests(str(self.root), test_filter="SmokeTest#passesOne+passesTwo")
        self.assertTrue(passed, output)
        reports = list((self.root / report_dir).glob("TEST-*.xml"))
        self.assertTrue(reports, output)
        self.assertEqual(sum(int(ET.parse(report).getroot().get("tests", 0)) for report in reports), 2)
        source.write_text(_TEST_SOURCE.replace("assertEquals(3, 1 + 1)", "assertEquals(2, 1 + 1)"), encoding="utf-8")
        passed, output = run_tests(str(self.root))
        self.assertTrue(passed, output)
        self.assertIn(str(self.root / build_dir), output)

    def create_gradle_wrapper(self, directory):
        result = subprocess.run(
            ["gradle", "--no-daemon", "--console=plain", "wrapper"],
            cwd=self.root / directory, capture_output=True, text=True, timeout=300,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        # Exercise the wrapper shell fallback as well as real Gradle execution.
        (self.root / directory / "gradlew").chmod(0o644)

    def test_maven_failing_filtered_and_corrected_tests(self):
        self.write("backend/pom.xml", """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId><artifactId>runner-smoke</artifactId><version>1</version>
  <properties><maven.compiler.release>17</maven.compiler.release></properties>
  <dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId>
    <version>4.13.2</version><scope>test</scope></dependency></dependencies>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version></plugin>
  </plugins></build>
</project>""")
        self.verify_fail_filter_fix("backend", "backend", "backend/target/surefire-reports")

    def test_gradle_groovy_wrapper_failing_filtered_and_corrected_tests(self):
        self.write("backend/settings.gradle", "rootProject.name = 'runner-smoke'\n")
        self.write("backend/build.gradle", """plugins { id 'java' }
repositories { mavenCentral() }
dependencies { testImplementation 'junit:junit:4.13.2' }
""")
        self.create_gradle_wrapper("backend")
        self.verify_fail_filter_fix("backend", "backend", "backend/build/test-results/test")

    def test_gradle_kotlin_multi_project_root_failing_filtered_and_corrected_tests(self):
        self.write("settings.gradle.kts", 'rootProject.name = "runner-smoke"\ninclude("backend")\n')
        self.write("backend/build.gradle.kts", """plugins { java }
repositories { mavenCentral() }
dependencies { testImplementation("junit:junit:4.13.2") }
""")
        self.create_gradle_wrapper(".")
        self.verify_fail_filter_fix(".", "backend", "backend/build/test-results/test")


if __name__ == "__main__":
    unittest.main()
