"""Exercise resumability when runners fail or retained evidence disappears."""

import copy
import hashlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile


def load_receipts():
    """Load the controller's standalone evidence reader."""
    spec = importlib.util.spec_from_file_location("curseforge_receipts", Path(__file__).parents[1] / "curseforge-receipts.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


receipts = load_receipts()


class CurseForgeReceiptsTest(unittest.TestCase):
    """Only identity-bound, complete Actions evidence can authorize continuation."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.destination = Path(self.temporary.name) / "receipts"
        self.commit = "a" * 40
        self.controller = "b" * 40
        self.run = {"id": 1, "run_attempt": 1, "head_branch": "master", "head_sha": self.controller,
                    "event": "workflow_dispatch", "display_title": f"Publish release v0.2.0 ({self.commit}) - CurseForge true"}
        self.jobs = [{"steps": [{"name": "Stage only missing CurseForge files", "started_at": "2026-09-21T00:00:00Z", "conclusion": "failure"}]}]
        self.receipt = {"schemaVersion": 1, "tag": "v0.2.0", "sourceCommit": self.commit, "projectId": 123,
                        "manifestSha256": "c" * 64, "files": {"a.jar": {"sha256": "d" * 64, "state": "attempting", "fileId": None}}}
        output = io.BytesIO()
        with ZipFile(output, "w") as archive:
            archive.writestr("receipt.json", json.dumps(self.receipt))
        self.archive = output.getvalue()
        self.artifacts = [{"id": 5, "name": "curseforge-v0.2.0-1-1", "expired": False,
                           "size_in_bytes": len(self.archive), "digest": "sha256:" + hashlib.sha256(self.archive).hexdigest(),
                           "workflow_run": {"id": 1, "head_sha": self.controller}}]

    def pages(self, path, key):
        """Expose one completed failed producer without real GitHub access."""
        return {"workflow_runs": [self.run], "jobs": self.jobs, "artifacts": self.artifacts}[key]

    def restore(self):
        """Restore through the public entrypoint while substituting transport only."""
        with patch.object(receipts, "pages", side_effect=self.pages), patch.object(receipts, "github", return_value=self.archive):
            receipts.restore("sya-ri/strata", "v0.2.0", self.commit, 2, 1, self.destination)
        return json.loads((self.destination / "history.json").read_text())

    def test_failed_upload_receipt_survives_new_run(self):
        self.assertTrue(self.restore()["mayUpload"])
        self.assertEqual(self.receipt, json.loads((self.destination / "receipt.json").read_text()))

    def test_missing_and_expired_receipt_forbid_new_uploads(self):
        self.artifacts = []
        self.assertFalse(self.restore()["mayUpload"])
        self.assertFalse((self.destination / "receipt.json").exists())

    def test_skipped_stage_needs_no_receipt(self):
        self.jobs[0]["steps"][0]["conclusion"] = "skipped"
        self.artifacts = []
        self.assertTrue(self.restore()["mayUpload"])

    def test_artifact_tampering_and_foreign_producer_fail(self):
        original = copy.deepcopy(self.artifacts)
        for field in ["digest", "producer", "duplicate", "size"]:
            with self.subTest(field=field):
                self.artifacts = copy.deepcopy(original)
                if field == "digest":
                    self.artifacts[0]["digest"] = "sha256:" + "0" * 64
                elif field == "producer":
                    self.artifacts[0]["workflow_run"]["head_sha"] = "0" * 40
                elif field == "size":
                    self.artifacts[0]["size_in_bytes"] += 1
                else:
                    self.artifacts.append(copy.deepcopy(self.artifacts[0]))
                with self.assertRaises(ValueError):
                    self.restore()

    def test_changed_publication_source_is_not_resumed(self):
        self.run["display_title"] = f"Publish release v0.2.0 ({'f' * 40}) - CurseForge true"
        self.assertTrue(self.restore()["mayUpload"])
        self.assertFalse((self.destination / "receipt.json").exists())

    def test_current_run_previous_attempt_is_inspected(self):
        with patch.object(receipts, "pages", side_effect=self.pages), patch.object(receipts, "github", return_value=self.archive):
            receipts.restore("sya-ri/strata", "v0.2.0", self.commit, 1, 2, self.destination)
        self.assertTrue((self.destination / "receipt.json").exists())

    def test_complete_pagination_is_required(self):
        with patch.object(receipts, "github", side_effect=[{"total_count": 2, "jobs": [{"id": 1}]}, {"total_count": 2, "jobs": []}]):
            with self.assertRaisesRegex(ValueError, "incomplete"):
                receipts.pages("repos/sya-ri/strata/jobs", "jobs")
        with patch.object(receipts, "github", side_effect=[{"total_count": 2, "jobs": [{"id": 1}]}, {"total_count": 3, "jobs": [{"id": 2}]}]):
            with self.assertRaisesRegex(ValueError, "changed"):
                receipts.pages("repos/sya-ri/strata/jobs", "jobs")
        with patch.object(receipts, "github", side_effect=[{"total_count": 2, "jobs": [{"id": 1}]}, {"total_count": 2, "jobs": [{"id": 1}]}]):
            with self.assertRaisesRegex(ValueError, "duplicate"):
                receipts.pages("repos/sya-ri/strata/jobs", "jobs")


if __name__ == "__main__":
    unittest.main()
