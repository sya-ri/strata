"""Distinguish accepted uploads from public verification in destination summaries."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


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
