"""Exercise actual-analysis admission independently of synthetic compiler/model fixtures."""

import importlib.util
import copy
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

    def test_legacy_shared_sources_require_both_context_options_in_the_actual_analysis_jvm(self):
        shared = copy.deepcopy(self.model["modules"][0])
        shared["name"] = "second-context"
        self.model["modules"].append(shared)
        options = ["-Dintellij.platform.shared.source.support=true", "-Dbatch.inspections.inspect.all.code.insight.contexts=true"]
        self.log.write_text("JVM options: [" + ", ".join(options) + "]\n", encoding="utf-8")
        self.verify()
        for supplied in ([], options[:1], options[1:], [option.replace("=true", "=false") for option in options]):
            with self.subTest(options=supplied):
                self.log.write_text("JVM options: [" + ", ".join(supplied) + "]\n", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "require the analysis JVM option"):
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

    def test_semantic_errors_are_rejected_without_waiting_for_inspection_suspension(self):
        run = {"versionControlProvenance": [{"revisionId": "current"}], "invocations": [{"executionSuccessful": True}]}
        report = {"runs": [run]}
        CHECKER.verify_report(report, "current")
        run["properties"] = {"qodana.sanity.results": [{"ruleId": "QodanaKotlinSanity", "level": "error", "message": {"text": "Unresolved reference."}}]}
        with self.assertRaisesRegex(ValueError, "semantic sanity problems"):
            CHECKER.verify_report(report, "current")

    def test_every_run_requires_its_own_selected_revision(self):
        valid = {"versionControlProvenance": [{"revisionId": "current"}], "invocations": [{"executionSuccessful": True}]}
        for provenance in (None, [], [{"revisionId": "other"}]):
            with self.subTest(provenance=provenance):
                additional = copy.deepcopy(valid)
                if provenance is None:
                    additional.pop("versionControlProvenance")
                else:
                    additional["versionControlProvenance"] = provenance
                with self.assertRaisesRegex(ValueError, "selected declaration revision"):
                    CHECKER.verify_report({"runs": [valid, additional]}, "current")

    def test_missing_or_non_boolean_invocation_success_is_rejected(self):
        valid = {"versionControlProvenance": [{"revisionId": "current"}], "invocations": [{"executionSuccessful": True}]}
        CHECKER.verify_report({"runs": [valid, copy.deepcopy(valid)]}, "current")
        for invocations in (None, [], [{}], [{"executionSuccessful": None}], [{"executionSuccessful": 1}],
                            [{"executionSuccessful": "true"}], [{"executionSuccessful": False}],
                            [{"executionSuccessful": True}, {"executionSuccessful": False}]):
            with self.subTest(invocations=invocations):
                additional = copy.deepcopy(valid)
                if invocations is None:
                    additional.pop("invocations")
                else:
                    additional["invocations"] = invocations
                with self.assertRaisesRegex(ValueError, "analysis invocation did not complete successfully"):
                    CHECKER.verify_report({"runs": [valid, additional]}, "current")


if __name__ == "__main__":
    unittest.main()
