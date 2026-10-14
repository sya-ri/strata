"""Check job selection and conservative handling of changed source ownership."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch

import test_paired_browser as browser_fixtures
import test_paired_jvm as paired_fixtures

spec = importlib.util.spec_from_file_location("plan_ci", Path(__file__).parents[1] / "plan-ci.py")
planner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(planner)


class PlanCiTest(unittest.TestCase):
    def paired_fixture(self, schema="jvm"):
        """Reuse complete real producer/consumer histories without starting a collector."""
        case = paired_fixtures.PairedJvmTest("runTest") if schema == "jvm" else browser_fixtures.PairedBrowserTest("runTest")
        case.setUp()
        self.addCleanup(case.doCleanups)
        fixture = case if schema == "jvm" else case.fixture
        value = case.source_manifest() if schema == "source" else case.manifest
        return fixture, value

    def cli_plan(self, root, *arguments, event="workflow_dispatch"):
        """Require failed selection to publish neither a JSON plan nor GitHub outputs."""
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "plan.json"
            github_output = Path(temporary) / "github-output"
            command = [sys.executable, "-B", str(Path(__file__).resolve().parents[1] / "plan-ci.py"), *arguments, "--output", str(output)]
            completed = subprocess.run(command, cwd=root, env={**os.environ, "GITHUB_EVENT_NAME": event, "GITHUB_OUTPUT": str(github_output)}, capture_output=True, text=True)
            if completed.returncode:
                self.assertFalse(output.exists(), completed.stderr)
                self.assertFalse(github_output.exists(), completed.stderr)
                return completed, None
            result = json.loads(output.read_text())
            self.assertEqual(result, json.loads(completed.stdout))
            self.assertEqual(result, {key: json.loads(value) for key, value in (line.split("=", 1) for line in github_output.read_text().splitlines())})
            return completed, result

    def check_outcomes(self, results):
        """Execute the workflow's actual always-running aggregation script."""
        workflow = Path(__file__).resolve().parents[2] / ".github/workflows/jvm.yml"
        script = workflow.read_text().split("python3 - <<'PY'\n", 1)[1].split("\n        PY", 1)[0]
        with patch.dict(os.environ, {"RESULTS": json.dumps(results)}):
            exec(compile(textwrap.dedent(script), str(workflow), "exec"), {})

    def test_explicit_manual_inputs_keep_complete_jvm_browser_and_source_contracts(self):
        expected = {"common": False, "web": False, "docs": False, "docs_full": False, "workflow": False,
                    "qodana": False, "all_minecraft": False, "preflight": True, "minecraft": []}
        for schema in ("jvm", "browser", "source"):
            with self.subTest(schema=schema):
                fixture, value = self.paired_fixture(schema)
                _, _, head, path, _ = fixture.input_consumer(value=value)
                completed, result = self.cli_plan(fixture.root, "--paired-manifest", path)
                self.assertEqual(0, completed.returncode, completed.stderr)
                self.assertEqual(expected, result)
                self.assertEqual(head, fixture.selector_git("rev-parse", "HEAD"))
                self.assertEqual("", fixture.selector_git("status", "--porcelain=v1"))

    def test_full_and_pr_planning_keep_existing_verification_with_a_valid_trailer(self):
        fixture, _ = self.paired_fixture()
        base, _, head, _, _ = fixture.input_consumer(candidate_patch=True)
        for event, arguments in (("workflow_dispatch", ("--full",)), ("push", ("--full",)), ("pull_request", ("--base", base, "--head", head))):
            with self.subTest(event=event):
                completed, result = self.cli_plan(fixture.root, *arguments, event=event)
                self.assertEqual(0, completed.returncode, completed.stderr)
                self.assertEqual(planner.plan([], full=True), result)

    def test_paired_mode_rejects_empty_nonmanual_and_conflicting_requests(self):
        fixture, _ = self.paired_fixture()
        base, _, head, path, _ = fixture.input_consumer()
        controls = [(event, ("--paired-manifest", path)) for event in ("pull_request", "push", "workflow_call", "")]
        controls += [("workflow_dispatch", arguments) for arguments in (
            ("--paired-manifest", ""), ("--paired-manifest", path, "--full"),
            ("--paired-manifest", path, "--base", base), ("--paired-manifest", path, "--head", head),
            ("--paired-manifest", path, "--model", "missing.json"))]
        for event, arguments in controls:
            with self.subTest(event=event, arguments=arguments):
                completed, _ = self.cli_plan(fixture.root, *arguments, event=event)
                self.assertNotEqual(0, completed.returncode)

    def test_invalid_frozen_inputs_fail_without_fallback_or_skip_outputs(self):
        for control in ("missing", "retained", "malformed", "duplicate", "wrong-parent", "wrong-path", "changed-source", "schema", "pin"):
            with self.subTest(control=control):
                fixture, value = self.paired_fixture()
                path = ".github/performance/owned/manifest.json"
                if control in ("missing", "retained"):
                    fixture.selector_repository()
                    if control == "retained":
                        fixture.selector_manifest(path)
                        fixture.selector_commit()
                else:
                    malformed = {"malformed": "Performance-Inputs: invalid:{path}",
                                 "duplicate": "Performance-Inputs: {producer}:{path}\nPerformance-Inputs: {producer}:{path}",
                                 "wrong-parent": "Performance-Inputs: {base}:{path}"}.get(control)
                    if control == "schema":
                        value["schema"] = "unknown"
                    if control == "pin":
                        value["inputs"][0]["pin"]["sha256"] = "0" * 64
                    fixture.input_consumer(value=value, malformed=malformed)
                    if control == "wrong-path":
                        path = ".github/performance/other/manifest.json"
                    if control == "changed-source":
                        fixture.input.write_text("changed consumer source\n")
                        fixture.selector_git("add", "--all")
                        fixture.selector_git("commit", "--quiet", "--amend", "--no-edit")
                completed, _ = self.cli_plan(fixture.root, "--paired-manifest", path)
                self.assertNotEqual(0, completed.returncode)

    def test_aggregate_requires_exact_selected_successes_and_planned_skips(self):
        paired = {"paired-jvm", "preflight-qualification"}
        ordinary = {"checks", "web-demos", "web", "workflow-checks", "minecraft-matrix", "minecraft", "documentation", "docs"}
        for full, manifest in ((False, ""), (False, ".github/performance/owned/manifest.json"), (True, ""), (True, ".github/performance/owned/manifest.json")):
            outputs = {key: json.dumps(value) for key, value in planner.plan([], full=full).items()}
            outputs.update(paired_manifest=manifest, preflight=json.dumps(full or bool(manifest)))
            selected = ({"paired-jvm"} if manifest else set()) | ({"preflight-qualification"} if full or manifest else set())
            expected = selected | (ordinary - {"docs"} if full else set())
            results = {job: {"result": "success" if job in expected else "skipped"} for job in paired | ordinary}
            results["plan"] = {"result": "success", "outputs": outputs}
            self.check_outcomes(results)
            for job in results:
                statuses = ("failure", "cancelled", "skipped") if job == "plan" or job in expected else ("failure", "cancelled", "success")
                for status in statuses:
                    with self.subTest(full=full, manifest=manifest, job=job, status=status):
                        changed = {**results, job: {**results[job], "result": status}}
                        with self.assertRaises(AssertionError):
                            self.check_outcomes(changed)

    def test_git_rename_diff_keeps_both_source_owners(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def git(*args):
                return subprocess.check_output(["git", "-c", "commit.gpgsign=false", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", *args], cwd=root)

            git("init", "--quiet")
            original = root / "runtime/shared/old/Native.kt"
            original.parent.mkdir(parents=True)
            original.write_text("// Retained source moves between shared families.\n" * 10)
            git("add", ".")
            git("commit", "--quiet", "-m", "Create fixture")
            base = git("rev-parse", "HEAD").decode().strip()
            renamed = root / "runtime/shared/new/Native.kt"
            renamed.parent.mkdir(parents=True)
            original.rename(renamed)
            git("add", "--all")
            git("commit", "--quiet", "-m", "Move source")
            model = root / "model.json"
            model.write_text(json.dumps({"1.20": ["runtime/shared/old"], "26.3": ["runtime/shared/new"]}))
            script = Path(__file__).parents[1] / "plan-ci.py"
            output = subprocess.check_output([sys.executable, str(script), "--base", base, "--model", str(model)], cwd=root)
            self.assertEqual(["1.20", "26.3"], json.loads(output)["minecraft"])

    def test_tooling_and_prose_changes_do_not_start_clients(self):
        for path in ("release/hangar-release.py", "docs/guides/screens.md", ".github/workflows/publish-release.yml", "gradle/tests/test_plan_ci.py", "gradle/verify-qodana-model-fixtures.sh"):
            paths = [path]
            result = planner.plan(paths)
            self.assertFalse(result["all_minecraft"])
            self.assertEqual([], result["minecraft"])
            self.assertFalse(result["qodana"])

    def test_shared_and_renamed_sources_include_both_owners(self):
        model = {"1.20": ["runtime/shared/old"], "26.3": ["runtime/shared/new"]}
        result = planner.plan(["runtime/shared/old/Deleted.kt", "runtime/shared/new/Added.kt"], model)
        self.assertEqual(["1.20", "26.3"], result["minecraft"])
        self.assertFalse(result["all_minecraft"])

    def test_unknown_ownership_and_global_inputs_expand_checks(self):
        self.assertTrue(planner.plan(["runtime/shared/unmapped/New.kt"], {})["all_minecraft"])
        for path in ("build.gradle.kts", "new-build-tool.toml"):
            result = planner.plan([path])
            self.assertTrue(result["all_minecraft"] and result["common"] and result["qodana"])

    def test_later_web_assets_do_not_disable_requested_analysis(self):
        result = planner.plan(["runtime/web/src/Web.kt", "tools/web/package.json"])
        self.assertTrue(result["qodana"])

    def test_compiled_preflight_qualification_selects_its_actual_boundary(self):
        for path in ("gradle/performance/paired-jvm.py", "gradle/performance/fixtures/QualifiedFixtureController.java", "gradle/tests/test_paired_jvm.py", ".github/workflows/jvm.yml"):
            with self.subTest(path=path):
                self.assertTrue(planner.plan([path])["preflight"])
        self.assertFalse(planner.plan(["runtime/core/src/commonMain/Runtime.kt"])["preflight"])
        self.assertTrue(planner.plan([], full=True)["preflight"])

    def test_transient_input_removal_and_shared_example_validate_the_controller(self):
        for path in (".github/performance/owned/baseline.patch", ".github/performance/owned/manifest.json",
                     ".github/performance/owned/Observer.kt", "gradle/performance/paired-jvm.example.json"):
            with self.subTest(path=path):
                result = planner.plan([path])
                self.assertTrue(result["workflow"] and result["preflight"])
                self.assertFalse(result["common"] or result["web"] or result["all_minecraft"] or result["qodana"])
        mixed = planner.plan([".github/performance/owned/manifest.json", "runtime/core/src/main/Changed.kt"])
        self.assertTrue(mixed["preflight"] and mixed["workflow"] and mixed["common"] and mixed["all_minecraft"])
        self.assertTrue(planner.plan([".github/performance-other/manifest.json"])["common"])

    def test_public_modules_keep_the_same_checks_after_moving_to_the_root(self):
        for module in ("detekt-rules", "performance-testkit"):
            with self.subTest(module=module):
                result = planner.plan([f"{module}/src/main/kotlin/Changed.kt"])
                self.assertEqual(planner.plan(["quality/checks/src/main/kotlin/Changed.kt"]), result)
                self.assertTrue(result["common"] and result["web"] and result["all_minecraft"] and result["qodana"])
                self.assertFalse(result["workflow"])


if __name__ == "__main__":
    unittest.main()
