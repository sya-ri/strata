"""Distinguish accepted uploads from public verification in destination summaries."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PublicationSummaryTest(unittest.TestCase):
    def test_disabled_central_does_not_require_or_reuse_a_remote_receipt(self):
        module = load("publication-summary")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for receipt in (False, True):
                if receipt:
                    (root / "central-verify.json").write_text(json.dumps({"state": "exact"}))
                self.assertEqual({"maven_central": "disabled", "hangar": "not completed"},
                                 module.summarize(root, {"maven_central": False, "hangar": True}, "verify"))

    def test_modrinth_file_results_are_independent_of_project_review(self):
        module = load("publication-summary")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "modrinth-receipts").mkdir()
            for operation, state in (("stage", "versions listed; public verification pending"), ("verify", "verified files")):
                (root / f"modrinth-receipts/{operation}.json").write_text(json.dumps({"operation": operation, "projectStatus": "withheld", "listed": ["1.2.3+mc1.20"], "absent": []}))
                self.assertEqual({"modrinth": state}, module.summarize(root, {"modrinth": True}, "verify" if operation == "verify" else "release"))

    def test_summary_keeps_pending_uploads_distinct_from_verified_files(self):
        module = load("publication-summary")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "curseforge").mkdir()
            (root / "hangar").mkdir()
            (root / "curseforge/receipt.json").write_text(json.dumps({"files": {"a.jar": {"state": "pending"}}}))
            (root / "hangar/receipt.json").write_text(json.dumps({"state": "exact"}))
            self.assertEqual({"curseforge": "uploads recorded; public status not checked (no read API key)"},
                             module.summarize(root, {"curseforge": True}, "release", curseforge_read_enabled=False))
            (root / "curseforge/receipt.json").write_text(json.dumps({"files": {"a.jar": {"state": "verified"}}}))
            self.assertEqual({"curseforge": "verification skipped (no read API key)"},
                             module.summarize(root, {"curseforge": True}, "verify", curseforge_read_enabled=False))
            (root / "curseforge/receipt.json").write_text(json.dumps({"files": {"a.jar": {"state": "pending"}}}))
            self.assertEqual({"curseforge": "pending", "hangar": "exact", "modrinth": "disabled"},
                             module.summarize(root, {"curseforge": True, "hangar": True, "modrinth": False}, "release"))

    def test_actions_entrypoint_records_request_and_normalizes_downloaded_receipts(self):
        module = load("publication-summary")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            release = root / "build/release"
            release.mkdir(parents=True)
            for operation in ("stage", "verify"):
                (release / f"modrinth-{operation}.json").write_text(json.dumps({"operation": operation, "listed": ["1.2.3"], "absent": []}))
            inputs = dict(operation="verify", tag="v1.2.3", source_commit="a" * 40, modrinth=True,
                          maven_central=False, github_release=False, hangar=False, curseforge=False)
            environment = dict(RELEASE_INPUTS=json.dumps(inputs), PREPARED_RUN_ID="123", GITHUB_RUN_ID="456",
                               GITHUB_RUN_ATTEMPT="2", GITHUB_SHA="b" * 40, GITHUB_STEP_SUMMARY=str(root / "summary.md"))
            with patch.dict(os.environ, environment), patch.object(module, "Path", side_effect=lambda value: root / value):
                module.main()
            request = json.loads((release / "publication-request.json").read_text())
            self.assertEqual("a" * 40, request["sourceCommit"])
            self.assertEqual("b" * 40, request["controllerCommit"])
            self.assertEqual((123, 456, 2), (request["preparedRunId"], request["runId"], request["runAttempt"]))
            self.assertEqual("verified files", request["results"]["modrinth"])
            self.assertEqual("disabled", request["results"]["maven_central"])
            for operation in ("stage", "verify"):
                self.assertFalse((release / f"modrinth-{operation}.json").exists())
                self.assertEqual(operation, json.loads((release / f"modrinth-receipts/{operation}.json").read_text())["operation"])
            self.assertIn("| modrinth | verified files |", (root / "summary.md").read_text())
