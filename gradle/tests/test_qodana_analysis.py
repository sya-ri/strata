"""Exercise actual-analysis admission independently of synthetic compiler/model fixtures."""

import importlib.util
import pathlib
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("qodana_declarations", pathlib.Path(__file__).parents[1] / "verify-qodana-declarations.py")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class QodanaAnalysisTest(unittest.TestCase):
    """Require positive Kotlin work without rejecting compiled-only variant modules."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name).resolve()
        source = self.root / "src"
        source.mkdir()
        (source / "Current.kt").write_text("internal class Current\n", encoding="utf-8")
        self.model = {"modules": [{"name": "code", "contentEntries": [{"sourceFolders": [{"type": "Source", "path": "file://" + source.as_posix()}]}]},
                                  {"name": "compiled-variant", "contentEntries": []}]}
        logs = self.root / "log"
        logs.mkdir()
        self.log = logs / "idea.log"
        self.log.write_text("No source roots detected for module 'compiled-variant'\n", encoding="utf-8")
        self.statistics = logs / "qodana_inspections_summary.csv"
        self.write_statistics("kotlin;3\nJAVA;200\n")

    def write_statistics(self, rows):
        self.statistics.write_text("Inspection Language;Times Inspection was Performed\n" + rows, encoding="utf-8")

    def verify(self):
        CHECKER.verify_analysis(self.root, self.model, self.root)

    def test_resolved_kotlin_and_compiled_only_variant_are_accepted(self):
        self.verify()

    def test_unresolved_source_owner_is_rejected(self):
        self.log.write_text("No source roots detected for module 'code'\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "IDE did not resolve Kotlin source owners"):
            self.verify()

    def test_omitted_kotlin_sources_are_rejected(self):
        self.model["modules"][0]["contentEntries"] = []
        with self.assertRaisesRegex(ValueError, "no declared Kotlin source owners"):
            self.verify()

    def test_zero_kotlin_work_is_not_replaced_by_other_languages(self):
        for rows in ("kotlin;0\nJAVA;200\n", "JAVA;200\n"):
            with self.subTest(rows=rows):
                self.write_statistics(rows)
                with self.assertRaisesRegex(ValueError, "performed no Kotlin inspections"):
                    self.verify()

    def test_missing_analysis_evidence_is_rejected(self):
        for path in (self.log, self.statistics):
            with self.subTest(path=path.name):
                original = path.read_bytes()
                path.unlink()
                with self.assertRaisesRegex(ValueError, "missing regular analysis evidence"):
                    self.verify()
                path.write_bytes(original)

    def test_invalid_kotlin_counts_are_rejected(self):
        for count in ("invalid", "-1", ""):
            with self.subTest(count=count):
                self.write_statistics(f"kotlin;{count}\n")
                with self.assertRaisesRegex(ValueError, "invalid Kotlin inspection count"):
                    self.verify()

    def test_missing_statistics_columns_are_rejected(self):
        self.statistics.write_text("Unexpected header\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "missing Kotlin inspection statistics columns"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
