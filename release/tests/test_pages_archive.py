"""Keep rendered release history intact without invoking a historical build."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("pages", Path(__file__).parents[1] / "pages-archive.py")
pages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pages)


class PagesArchiveTest(unittest.TestCase):
    def test_legacy_html_gains_native_dokka_template_metadata_only_once(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "api").mkdir()
            page = root / "api/index.html"
            page.write_text('<html><head></head><body><div class="library-version" id="library-version">2.3.4</div><p>Original API</p></body></html>')
            pages.version_metadata(root, "2.3.4")
            content = page.read_text()
            self.assertIn("ReplaceVersionsCommand", content)
            self.assertIn("../../../styles/multimodule.css", content)
            self.assertIn("<p>Original API</p>", content)
            pages.version_metadata(root, "2.3.4")
            self.assertEqual(content, page.read_text())

    def test_snapshot_preserves_source_and_html_without_recursive_history(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "site"
            source.mkdir()
            (source / "index.html").write_text("rendered once")
            (source / "source-receipt.json").write_text(json.dumps({"revision": "v2.3.4", "commit": "a" * 40}))
            (source / "older").mkdir()
            (source / "older/nested.html").write_text("not copied")
            target = root / "store/older/2.3.4"
            pages.snapshot(source, target, "2.3.4")
            self.assertEqual({"version": "2.3.4"}, json.loads((target / "version.json").read_text()))
            self.assertFalse((target / "older").exists())
            (source / "index.html").write_text("later generator")
            pages.snapshot(source, target, "2.3.4")
            self.assertEqual("rendered once", (target / "index.html").read_text())
            (source / "source-receipt.json").write_text(json.dumps({"revision": "v2.3.4", "commit": "b" * 40}))
            with self.assertRaises(ValueError):
                pages.snapshot(source, target, "2.3.4")
            with self.assertRaises(ValueError):
                pages.snapshot(source, root / "wrong", "2.3.5")


if __name__ == "__main__":
    unittest.main()
