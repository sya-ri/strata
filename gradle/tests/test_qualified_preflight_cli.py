"""Check CI case coverage and reject invalid qualification requests before preparing workspaces."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).parents[1] / "performance/verify-qualified-preflight.py"
SPEC = importlib.util.spec_from_file_location("qualified_preflight", SCRIPT)
QUALIFICATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUALIFICATION)


class QualifiedPreflightCliTest(unittest.TestCase):
    """Checks case coverage and distinguishes intended rejection from unrelated runtime failure."""

    def invoke(self, *arguments):
        """Run the real public CLI without replacing its admission or argument parsing."""
        return subprocess.run([sys.executable, "-B", str(SCRIPT), *arguments], capture_output=True, text=True, check=False)

    def test_matrix_keeps_every_real_case_once_and_one_host_boundary(self):
        result = self.invoke("--ci-matrix")
        self.assertEqual(result.returncode, 0, result.stderr)
        groups = json.loads(result.stdout)["include"]
        self.assertEqual({group["id"] for group in groups}, {"complete", "negative"})
        cases = [case for group in groups for case in group["cases"]]
        self.assertEqual(len(cases), 4)
        self.assertEqual(set(cases), {"complete", "missing-control", "altered-bytes", "controller-failure"})
        complete = next(group for group in groups if group["id"] == "complete")
        negative = next(group for group in groups if group["id"] == "negative")
        self.assertEqual(complete["cases"], ["complete"])
        self.assertIs(complete["host_boundary"], True)
        self.assertIs(negative["host_boundary"], False)

    def test_unknown_or_duplicate_cases_create_no_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "qualification"
            for cases in (("unknown",), ("complete", "complete")):
                with self.subTest(cases=cases):
                    result = self.invoke("--case", *cases, "--output", str(output))
                    self.assertEqual(result.returncode, 2, result.stderr)
                    self.assertFalse(output.exists())

    def test_matrix_query_cannot_execute_cases_or_create_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "qualification"
            for arguments in (("--case", "complete"), ("--output", str(output))):
                with self.subTest(arguments=arguments):
                    result = self.invoke("--ci-matrix", *arguments)
                    self.assertEqual(result.returncode, 2, result.stderr)
                    self.assertFalse(output.exists())

    def test_execution_still_requires_an_output(self):
        for arguments in ((), ("--case", "complete")):
            with self.subTest(arguments=arguments):
                result = self.invoke(*arguments)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn("--output is required", result.stderr)

    def test_real_rejection_reasons_are_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            attempt = Path(temporary)
            name = "preflight-qualified-control-route-baseline"
            console = attempt / "phases" / name / "console.log"
            console.parent.mkdir(parents=True)
            console.write_text("java.lang.IllegalStateException: Required independent controller failure\n", encoding="utf-8")
            for case, error, code in (
                (QUALIFICATION.QualificationCase.MISSING_CONTROL, "ValueError: Incomplete or reordered whole preflight control inventory", 0),
                (QUALIFICATION.QualificationCase.ALTERED_BYTES, "ValueError: Control changed prepared or original classpath bytes", 0),
                (QUALIFICATION.QualificationCase.CONTROLLER_FAILURE, f"ValueError: Owned phase failed: {name}; retained evidence", 1),
            ):
                with self.subTest(case=case):
                    phase = {"name": name, "exit_code": code, "terminal": {"exitCode": code}}
                    QUALIFICATION.verify_negative_case(case, attempt, {"error": error}, phase)

    def test_wrong_runtime_failure_cannot_satisfy_negative_controls(self):
        with tempfile.TemporaryDirectory() as temporary:
            attempt = Path(temporary)
            name = "preflight-qualified-control-route-baseline"
            console = attempt / "phases" / name / "console.log"
            console.parent.mkdir(parents=True)
            console.write_text("java.lang.ClassNotFoundException: independent.fixture.QualifiedFixtureController\n", encoding="utf-8")
            for case in (QUALIFICATION.QualificationCase.MISSING_CONTROL, QUALIFICATION.QualificationCase.ALTERED_BYTES, QUALIFICATION.QualificationCase.CONTROLLER_FAILURE):
                with self.subTest(case=case):
                    phase = {"name": name, "exit_code": 1, "terminal": {"exitCode": 1}}
                    with self.assertRaises(ValueError):
                        QUALIFICATION.verify_negative_case(case, attempt, {"error": f"ValueError: Owned phase failed: {name}; retained evidence"}, phase)

    def test_successful_process_with_the_wrong_violation_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            attempt = Path(temporary)
            phase = {"name": "preflight-baseline", "exit_code": 0, "terminal": {"exitCode": 0}}
            with self.assertRaises(ValueError):
                QUALIFICATION.verify_negative_case(QUALIFICATION.QualificationCase.ALTERED_BYTES, attempt,
                                                   {"error": "ValueError: Incomplete or reordered whole preflight control inventory"}, phase)


if __name__ == "__main__":
    unittest.main()
