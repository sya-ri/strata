"""Check job selection and conservative handling of changed source ownership."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("plan_ci", Path(__file__).parents[1] / "plan-ci.py")
planner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(planner)


class PlanCiTest(unittest.TestCase):
    def test_release_and_prose_changes_do_not_start_clients(self):
        for paths in (["release/hangar-release.py"], ["docs/guides/screens.md"], [".github/workflows/publish-release.yml"]):
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
