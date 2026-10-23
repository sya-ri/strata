"""Exercise release gates, failure evidence and non-publishing admission without service writes."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

RELEASE = Path(__file__).parents[1]
sys.path.insert(0, str(RELEASE))
import verification
import preparation


def load(name, filename):
    """Import the existing hyphenated release controller scripts."""
    spec = importlib.util.spec_from_file_location(name, RELEASE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


recovery = load("recovery", "recovery-summary.py")
rehearsal = load("rehearsal", "rehearsal-summary.py")
prepared = preparation.prepared


class ReleaseVerificationTest(unittest.TestCase):
    def setUp(self):
        self.identity = dict(schemaVersion=1, scope="production", sourceCommit="a" * 40, controllerCommit="b" * 40,
                             tagObject="c" * 40, runId=123, runAttempt=1)

    def write_receipt(self, root, **changes):
        """Retain independently known task evidence and its exact digest inventory."""
        root.mkdir(parents=True)
        (root / "check.log").write_text("all selected tasks succeeded\n", encoding="utf-8")
        value = dict(self.identity, command=verification.FULL_CHECK,
                     tasks=[dict(build="strata", path=":check")], files={"check.log": verification.digest(root / "check.log")})
        value.update(changes)
        verification.write_json(root / "receipt.json", value)

    def test_missing_wrong_revision_and_changed_artifact_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "artifact"
            self.write_receipt(root)
            verification.validate_receipt(root, self.identity)
            for changes in ({"sourceCommit": "d" * 40}, {"controllerCommit": "d" * 40}, {"runAttempt": 2},
                            {"scope": "rehearsal"}, {"tagObject": "d" * 40}):
                with self.subTest(changes=changes), self.assertRaises(ValueError):
                    verification.validate_receipt(root, dict(self.identity, **changes))
            (root / "check.log").write_text("replaced evidence", encoding="utf-8")
            with self.assertRaises(ValueError):
                verification.validate_receipt(root, self.identity)

    def test_failed_cancelled_and_unexpected_skipped_work_cannot_reach_signing(self):
        for job in ("source", "analysis", "verification"):
            for state in ("failure", "cancelled", "skipped"):
                results = {name: {"result": "success"} for name in ("source", "analysis", "verification")}
                results[job]["result"] = state
                with self.subTest(job=job, state=state), patch.object(verification.subprocess, "check_output") as api:
                    with self.assertRaises(ValueError):
                        verification.gate(Path("product"), results)
                    api.assert_not_called()

    def test_gate_accepts_same_run_successful_prior_attempt_and_checks_downloaded_digest(self):
        for corrupt in (False, True):
            with self.subTest(corrupt=corrupt), tempfile.TemporaryDirectory() as temporary:
                workspace = Path(temporary)
                source = workspace / "original"
                self.write_receipt(source)
                archive = workspace / "verification.zip"
                with ZipFile(archive, "w") as files:
                    for file in source.iterdir():
                        files.write(file, file.name)
                archive_digest = verification.digest(archive)
                artifact = dict(name="production-verification-1", digest="sha256:" + archive_digest,
                                size_in_bytes=archive.stat().st_size, expired=False,
                                workflow_run=dict(id=123, head_sha="b" * 40))
                outputs = dict(artifact_id="789", artifact_digest=archive_digest,
                               receipt_digest=verification.digest(source / "receipt.json"), producer_attempt="1")
                results = {name: {"result": "success"} for name in ("source", "analysis", "verification")}
                results["verification"]["outputs"] = outputs
                results["analysis"]["outputs"] = dict(source_commit="a" * 40, controller_commit="b" * 40,
                                                       producer_run_id="123", producer_attempt="1",
                                                       product_report_digest="d" * 64, build_logic_report_digest="e" * 64)

                def download(command, **kwargs):
                    self.assertEqual(["gh", "api", "repos/owner/repo/actions/artifacts/789/zip"], command)
                    kwargs["stdout"].write(b"corrupted archive" if corrupt else archive.read_bytes())

                previous = Path.cwd()
                try:
                    os.chdir(workspace)
                    with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                            patch.object(verification, "identity", return_value=dict(self.identity, runAttempt=2)), \
                            patch.object(verification.subprocess, "check_output", return_value=json.dumps(artifact)), \
                            patch.object(verification.subprocess, "run", side_effect=download):
                        if corrupt:
                            with self.assertRaisesRegex(ValueError, "digest or size"):
                                verification.gate(Path("product"), results)
                        else:
                            verification.gate(Path("product"), results)
                            gate = json.loads(Path("build/verification-gate.json").read_text())
                            self.assertEqual(1, gate["runAttempt"])
                            self.assertEqual(789, gate["verificationArtifactId"])
                finally:
                    os.chdir(previous)

    def test_full_receipt_requires_real_full_command_graph_and_every_phase(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            product, evidence = root / "product", root / "evidence"
            verification.write_json(product / "build/release-verification/tasks.json",
                                    [dict(build="strata", path=p) for p in (":check", ":koverHtmlReport", ":koverXmlReport")])
            for name in ("native", "npm", "browsers", "check"):
                verification.write_json(evidence / f"{name}.json", dict(self.identity, exitCode=0, command=verification.check_command()))
            with patch.object(verification, "identity", return_value=self.identity):
                verification.receipt(product, evidence)
                record = json.loads((evidence / "check.json").read_text())
                record["command"].remove("koverXmlReport")
                verification.write_json(evidence / "check.json", record)
                with self.assertRaisesRegex(ValueError, "command differs"):
                    verification.receipt(product, evidence)

    def test_late_browser_failure_and_transient_read_keep_original_evidence_without_retry(self):
        cases = (("check", "AssertionError: advance width differs", "deterministic verification failure"),
                 ("npm", "HTTP 503 timed out", "possible transient infrastructure failure"))
        for name, message, classification in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                command = [sys.executable, "-c", f"import sys; print({message!r}); print('token-value'); sys.exit(7)"]
                with patch.object(verification, "identity", return_value=self.identity), \
                        patch.dict(os.environ, {"SOME_TOKEN": "token-value"}), contextlib.redirect_stdout(io.StringIO()):
                    code = verification.phase(name, root, command, root / "evidence")
                self.assertEqual(7, code)
                record = json.loads((root / f"evidence/{name}.json").read_text())
                self.assertTrue(record["classification"].startswith(classification))
                log = (root / f"evidence/{name}.log").read_text()
                self.assertIn(message, log)
                self.assertNotIn("token-value", log)
                self.assertEqual(1, log.count(message))
                summary = verification.summarize_phases(root / "evidence")
                self.assertEqual(name, summary["phase"])
                self.assertTrue(summary["classification"].startswith(classification))

    def test_missing_producer_diagnostics_do_not_hide_the_primary_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            product = root / "product"
            product.mkdir()
            verification.collect(product, root / "evidence")
            report = product / "integration/client/build/run/logs/latest.log"
            report.parent.mkdir(parents=True)
            report.write_text("compile FAILED\n", encoding="utf-8")
            verification.collect(product, root / "evidence")
            self.assertEqual("compile FAILED\n", (root / "evidence/diagnostics" / report.relative_to(product)).read_text())

    def test_rehearsal_and_corrupt_archives_are_rejected_by_production_validation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "signed.jar").write_bytes(b"real temporary key output")
            value = dict(schemaVersion=2, scope="rehearsal", tag="v7.8.9", sourceCommit="a" * 40, files=prepared.inventory(root))
            prepared.write_json(root / "prepared.json", value)
            prepared.validate(root, "v7.8.9", "a" * 40, scope="rehearsal")
            with self.assertRaises(ValueError):
                prepared.validate(root, "v7.8.9", "a" * 40)
            (root / "signed.jar").write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                prepared.validate(root, "v7.8.9", "a" * 40, scope="rehearsal")

    def test_rehearsal_requires_only_selected_successful_topology(self):
        for selected, unselected in (("serial", "overlap"), ("overlap", "serial")):
            inputs = dict(topology=selected)
            results = {name: {"result": "success"} for name in ("source", "analysis", "prepare", selected)}
            results[unselected] = {"result": "skipped"}
            self.assertEqual([], rehearsal.check_results(inputs, results))
            results[selected]["result"] = "cancelled"
            self.assertTrue(rehearsal.check_results(inputs, results))

    def test_recovery_never_reuses_failure_and_selects_only_failed_destinations(self):
        inputs = dict(operation="release", tag="v7.8.9", source_commit="a" * 40, prepared_run_id="",
                      maven_central=True, github_release=True, modrinth=True, curseforge=False, hangar=False)
        jobs = [dict(id=1, name="Maven Central", conclusion="success"),
                dict(id=2, name="Publish github", conclusion="success"),
                dict(id=3, name="Publish modrinth", conclusion="failure")]
        self.assertIsNone(recovery.retry_inputs(inputs, None, jobs))
        retry = recovery.retry_inputs(inputs, {"runId": 123}, jobs)
        self.assertEqual("123", retry["prepared_run_id"])
        self.assertTrue(retry["modrinth"])
        self.assertFalse(retry["maven_central"] or retry["github_release"] or retry["curseforge"] or retry["hangar"])
        self.assertEqual("success", recovery.expected_results(inputs)["analysis"])
        inputs["prepared_run_id"] = "123"
        self.assertEqual("skipped", recovery.expected_results(inputs)["analysis"])


if __name__ == "__main__":
    unittest.main()
