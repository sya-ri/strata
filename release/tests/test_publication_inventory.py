"""Check variable publication shapes and approval summaries using temporary evidence."""
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


class PublicationInventoryTest(unittest.TestCase):
    def test_file_counts_cover_plugins_and_klibs_and_reject_foreign_owners(self):
        module = load("maven-file-count")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            coordinates, files = root / "coordinates", root / "files"
            coordinates.write_text("dev.s7a:paper\ndev.s7a:js\n")
            self.assertEqual(10, module.count_files(coordinates, files))
            entries = [f"dev.s7a:{owner}:{suffix}" for owner, suffixes in
                       [("paper", [".pom", ".module", ".jar", "-plugin.jar"]), ("js", [".pom", ".module", ".klib"])] for suffix in suffixes]
            files.write_text("\n".join(entries))
            self.assertEqual(7, module.count_files(coordinates, files))
            for invalid in (entries + entries[:1], entries[:-1], entries + ["dev.s7a:other:.jar"], entries + ["dev.s7a:paper:../escape"]):
                files.write_text("\n".join(invalid))
                with self.assertRaises(ValueError):
                    module.count_files(coordinates, files)

    def test_summary_keeps_pending_uploads_distinct_from_verified_files(self):
        module = load("publication-summary")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "curseforge").mkdir()
            (root / "hangar").mkdir()
            (root / "curseforge/receipt.json").write_text(json.dumps({"files": {"a.jar": {"state": "pending"}}}))
            (root / "hangar/receipt.json").write_text(json.dumps({"state": "exact"}))
            self.assertEqual({"curseforge": "pending", "hangar": "exact", "modrinth": "disabled"},
                             module.summarize(root, {"curseforge": True, "hangar": True, "modrinth": False}, "release"))
