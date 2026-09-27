"""Check job selection and conservative handling of changed source ownership."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("plan_ci", Path(__file__).parents[1] / "plan-ci.py")
planner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(planner)


class PlanCiTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
