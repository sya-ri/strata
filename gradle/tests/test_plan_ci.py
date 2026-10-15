"""Check job selection and conservative handling of changed source ownership."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("plan_ci", Path(__file__).parents[1] / "plan-ci.py")
planner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(planner)


class PlanCiTest(unittest.TestCase):
    def test_default_full_plan_keeps_every_existing_gate(self):
        result = planner.plan([], full=True)
        self.assertEqual([], result.pop("minecraft"))
        self.assertTrue(all(result.values()))

    def test_selected_clients_bind_the_subject_and_declared_backend_contract(self):
        subject = "a" * 40
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for version in ("26.2", "26.3"):
                for kind in ("runtime", "integration"):
                    directory = root / kind / f"minecraft-fabric-{version}"
                    directory.mkdir(parents=True)
                    # Use the real build declarations, rather than inventing another backend inventory.
                    source = Path(__file__).parents[2] / kind / f"minecraft-fabric-{version}" / "build.gradle.kts"
                    (directory / "build.gradle.kts").write_bytes(source.read_bytes())
            with patch.object(planner.subprocess, "check_output", side_effect=lambda args, **kwargs: subject + "\n" if args[1] == "rev-parse" else b""):
                for backend in ("opengl", "vulkan"):
                    with self.subTest(backend=backend):
                        result = planner.client_plan(root, "26.3,26.2", backend, subject)
                        self.assertEqual(["26.2", "26.3"], result["minecraft"])
                        self.assertEqual(subject, result["subject_ref"])
                        self.assertFalse(result["common"] or result["all_minecraft"] or result["preflight"])
                        for entry in result["client_matrix"]["include"]:
                            self.assertIn(":runClientGameTest ", entry["gradle_arguments"])
                            self.assertIn(":runProductionClientGameTest ", entry["gradle_arguments"])
                            self.assertIn(f"-Pstrata.canvas.backend={backend} -Pstrata.canvas.scope=full", entry["gradle_arguments"])
                            self.assertFalse(entry["documentation"])
                self.assertEqual(["26.3"], planner.client_plan(root, "26.3", "vulkan", subject)["minecraft"])
                for versions, backend, ref in (("", "vulkan", subject), ("26.3,", "vulkan", subject),
                                               ("26.3,26.3", "vulkan", subject), ("26.3;whoami", "vulkan", subject),
                                               ("26.9", "vulkan", subject), ("26.3", "default", subject),
                                               ("26.3", "shell", subject), ("26.3", "vulkan", "master"),
                                               ("26.3", "vulkan", "b" * 40)):
                    with self.subTest(versions=versions, backend=backend, ref=ref), self.assertRaises(ValueError):
                        planner.client_plan(root, versions, backend, ref)
                (root / "integration/minecraft-fabric-26.2/build.gradle.kts").write_text('tasks.named("runClientGameTest")')
                with self.assertRaisesRegex(ValueError, "does not declare"):
                    planner.client_plan(root, "26.2", "vulkan", subject)
                with patch.object(planner.subprocess, "check_output", side_effect=[subject + "\n", b" M build.gradle.kts"]):
                    with self.assertRaisesRegex(ValueError, "modified tracked"):
                        planner.client_plan(root, "26.3", "vulkan", subject)

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
