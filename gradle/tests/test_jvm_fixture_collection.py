"""Keep scoped fixture admission strict as the existing JVM model includes Velocity."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import unittest


SPEC = importlib.util.spec_from_file_location("jvm_fixture_collection", Path(__file__).resolve().parents[1] / "verify-jvm-fixture-collection.py")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class ScopedFixtureCollectionTest(unittest.TestCase):
    """Missing or unrelated projects cannot certify the required scoped task closure."""
    PROJECTS = {":api", ":runtime:core", ":performance-testkit", ":quality:benchmarks", ":quality:component-benchmarks",
                ":quality:remote-benchmarks", ":runtime:headless", ":runtime:minecraft", ":runtime:minecraft-fonts-lwjgl",
                ":runtime:remote", ":quality:detekt-rules", ":integration:api", ":velocity-api", ":runtime:velocity"}

    def closure(self, projects):
        return "Ordinary Gradle output\nEXPLICIT_JVM_PROJECTS=" + ",".join(sorted(projects)) + "\nBUILD SUCCESSFUL\n"

    def test_complete_model_including_velocity_is_admitted(self):
        VERIFIER.verify_project_selection(self.closure(self.PROJECTS), self.PROJECTS)

    def test_either_missing_velocity_project_is_rejected(self):
        for project in (":velocity-api", ":runtime:velocity"):
            with self.subTest(project=project), self.assertRaises(ValueError):
                VERIFIER.verify_project_selection(self.closure(self.PROJECTS - {project}), self.PROJECTS)

    def test_unrelated_project_is_rejected(self):
        with self.assertRaises(ValueError):
            VERIFIER.verify_project_selection(self.closure(self.PROJECTS | {":fabric:minecraft-1-20"}), self.PROJECTS)

    def test_absent_model_receipt_is_rejected(self):
        with self.assertRaises(StopIteration):
            VERIFIER.verify_project_selection("BUILD SUCCESSFUL\n", self.PROJECTS)

    def test_selection_checks_survive_python_optimization(self):
        cases = (self.test_complete_model_including_velocity_is_admitted, self.test_either_missing_velocity_project_is_rejected,
                 self.test_unrelated_project_is_rejected, self.test_absent_model_receipt_is_rejected)
        command = [sys.executable, "-B", str(Path(__file__).resolve()),
                   *(f"{type(self).__name__}.{case.__name__}" for case in cases)]
        for optimize in ("0", "1", "2"):
            with self.subTest(optimize=optimize):
                completed = subprocess.run(command, env={**os.environ, "PYTHONOPTIMIZE": optimize}, capture_output=True, text=True)
                self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)


if __name__ == "__main__":
    unittest.main()
