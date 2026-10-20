"""Check job selection and conservative handling of changed source ownership."""
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
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
            self.assertEqual(result, {key: value if isinstance(result[key], str) else json.loads(value)
                                      for key, value in (line.split("=", 1) for line in github_output.read_text().splitlines())})
            return completed, result

    def check_outcomes(self, results):
        """Check the same planner-owned aggregation used by the workflow."""
        planner.check_results(results)

    def test_explicit_manual_inputs_keep_complete_jvm_browser_and_source_contracts(self):
        expected = {"common": False, "web": False, "docs": False, "docs_full": False, "workflow": False,
                    "qodana": False, "all_minecraft": False, "preflight": True, "minecraft": [], "source_model": False}
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
                self.assertFalse(result.pop("source_model"))
                self.assertEqual(planner.plan([], full=True), result)

    def test_paired_mode_rejects_empty_nonmanual_and_conflicting_requests(self):
        fixture, _ = self.paired_fixture()
        base, _, head, path, _ = fixture.input_consumer()
        controls = [(event, ("--paired-manifest", path)) for event in ("pull_request", "push", "workflow_call", "")]
        controls += [("workflow_dispatch", arguments) for arguments in (
            ("--paired-manifest", ""), ("--paired-manifest", path, "--full"),
            ("--paired-manifest", path, "--base", base), ("--paired-manifest", path, "--head", head),
            ("--paired-manifest", path, "--model", "missing.json"),
            ("--paired-manifest", path, "--minecraft-versions", "26.3"),
            ("--paired-manifest", path, "--minecraft-backend", "vulkan"),
            ("--paired-manifest", path, "--subject-ref", head))]
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

    def test_outcome_cli_checks_results_without_creating_a_new_plan(self):
        jobs = {key: {"result": "skipped"} for key in ("paired-jvm", "preflight-qualification", "checks", "web-demos", "web", "workflow-checks", "minecraft-matrix", "minecraft", "documentation", "docs")}
        jobs["plan"] = {"result": "success", "outputs": {key: json.dumps(value) for key, value in planner.plan([]).items()}}
        with tempfile.TemporaryDirectory() as temporary:
            command = [sys.executable, "-B", str(Path(__file__).resolve().parents[1] / "plan-ci.py"), "--check-results"]
            environment = {**os.environ, "RESULTS": json.dumps(jobs)}
            completed = subprocess.run(command, cwd=temporary, env=environment, capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual([], list(Path(temporary).iterdir()))
            jobs["checks"]["result"] = "success"
            environment["RESULTS"] = json.dumps(jobs)
            completed = subprocess.run(command, cwd=temporary, env=environment, capture_output=True, text=True)
            self.assertNotEqual(0, completed.returncode)
            self.assertIn("checks: expected skipped, got success", completed.stderr)

    def test_workflow_isolates_selected_client_concurrency_and_paired_intent(self):
        workflow = (Path(__file__).parents[2] / ".github/workflows/jvm.yml").read_text(encoding="utf-8")
        group = next(line.removeprefix("  group: ") for line in workflow.splitlines() if line.startswith("  group: "))
        self.assertEqual("${{ github.workflow }}-${{ github.ref }}"
                         "${{ inputs.subject_ref != '' && format('-clients-{0}-{1}-{2}', inputs.subject_ref, inputs.minecraft_backend, inputs.minecraft_versions) || '' }}"
                         "${{ inputs.paired_inputs != '' && format('-paired-{0}', github.run_id) || '' }}", group)
        self.assertIn("  cancel-in-progress: true\n", workflow)
        self.assertIn("    - name: Select opt-in paired JVM measurement\n      if: inputs.subject_ref == ''\n", workflow)
        minecraft = workflow.split("\n  minecraft:\n", 1)[1].split("\n  documentation:", 1)[0]
        checkout_ref = next(line.removeprefix("        ref: ") for line in minecraft.splitlines() if line.startswith("        ref: "))
        # Empty input preserves checkout's event-ref and event-SHA default; a selected SHA remains explicit.
        self.assertEqual("${{ needs.plan.outputs.subject_ref }}", checkout_ref)

    def test_default_full_plan_keeps_every_existing_gate(self):
        result = planner.plan([], full=True)
        self.assertEqual([], result.pop("minecraft"))
        self.assertTrue(all(result.values()))

    def test_multiplatform_js_and_common_tests_keep_their_platform_checks(self):
        paths = []
        for module in ("api", "runtime/core", "performance-testkit"):
            for source_set in ("jsMain", "jsTest", "commonTest"):
                path = f"{module}/src/{source_set}/kotlin/Contract.kt"
                paths.append(path)
                expected = planner.plan([])
                expected.update(common=True, web=True, qodana=True)
                with self.subTest(path=path):
                    self.assertEqual(expected, planner.plan([path]))
                    self.assertEqual(planner.plan([], full=True), planner.plan([path], full=True))
        expected = planner.plan([])
        expected.update(common=True, web=True, qodana=True)
        self.assertEqual(expected, planner.plan(paths))

    def test_multiplatform_inputs_do_not_narrow_other_changed_inputs(self):
        for path in ("api/src/jsMain/kotlin/Implementation.kt", "runtime/core/src/commonTest/kotlin/Contract.kt"):
            selected = planner.plan([path])
            for other in ("runtime/minecraft/src/test/kotlin/Test.kt", "api/src/commonMain/kotlin/Element.kt",
                          "runtime/shared/minecraft-fabric/canvas/common/src/main/kotlin/Canvas.kt",
                          "runtime/core/src/jsTest/resources/fixture.json", "settings.gradle.kts", "unknown-input"):
                expected = planner.plan([other])
                expected = {key: (expected[key] or selected[key]) if type(expected[key]) is bool else expected[key] for key in expected}
                for paths in ([path, other], [other, path]):
                    with self.subTest(paths=paths):
                        self.assertEqual(expected, planner.plan(paths))

    def test_multiplatform_resources_and_unrecognized_source_roots_keep_platform_checks(self):
        expected = planner.plan(["api/src/commonMain/kotlin/Element.kt"])
        for path in ("api/src/jsMain/resources/image.png", "runtime/core/src/jsTest/resources/fixture.json",
                     "performance-testkit/src/commonTest/resources/evidence.json", "api/src/webMain/kotlin/Implementation.kt",
                     "runtime/future/src/jsTest/kotlin/Test.kt", "runtime/headless/src/commonTest/kotlin/Test.kt",
                     "performance-testkit/src/jsMain/kotlin-fixtures/Fixture.kt", "api/src/jsTest/kotlin/Test.kt.disabled"):
            with self.subTest(path=path):
                self.assertEqual(expected, planner.plan([path]))

    def test_private_jvm_test_sources_keep_jvm_checks_coverage_and_analysis(self):
        expected = planner.plan([])
        expected.update(common=True, qodana=True)
        paths = [f"{module}/src/{source_set}/kotlin/PrivateJvmTest.kt"
                 for module, source_sets in (("api", ("test", "jvmTest")), ("runtime/core", ("test", "jvmTest")),
                                             ("runtime/remote", ("test",)), ("runtime/headless", ("test",)),
                                             ("runtime/minecraft", ("test",)), ("runtime/minecraft-fonts-lwjgl", ("test",)))
                 for source_set in source_sets]
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(expected, planner.plan([path]))
                self.assertEqual(planner.plan([], full=True), planner.plan([path], full=True))
        self.assertEqual(expected, planner.plan(paths))

    def test_private_jvm_tests_do_not_narrow_other_changed_inputs(self):
        test = "runtime/minecraft/src/test/kotlin/PrivateJvmTest.kt"
        for other in ("api/src/commonMain/kotlin/Component.kt", "runtime/minecraft/src/main/kotlin/Rendering.kt",
                      "runtime/shared/minecraft-fabric/canvas/common/src/main/kotlin/Canvas.kt",
                      "runtime/minecraft-fonts-lwjgl/src/test/resources/fonts/fixture.ttf", "build.gradle.kts", "unknown-input"):
            expected = planner.plan([other])
            expected.update(common=True, qodana=True)
            for paths in ([test, other], [other, test]):
                with self.subTest(paths=paths):
                    self.assertEqual(expected, planner.plan(paths))

    def test_test_resources_and_unknown_roots_keep_platform_checks(self):
        expected = planner.plan(["api/src/main/kotlin/Component.kt"])
        for path in ("runtime/minecraft-fonts-lwjgl/src/test/resources/fonts/fixture.ttf",
                     "runtime/core/src/test/resources/fixture.json", "runtime/future/src/test/kotlin/Test.kt",
                     "runtime/remote/src/jvmTest/kotlin/Test.kt", "runtime/core/src/test/kotlin-fixtures/Test.kt",
                     "runtime/core/src/test/kotlin/Test.kt.disabled"):
            with self.subTest(path=path):
                self.assertEqual(expected, planner.plan([path]))

    def test_idea_analysis_model_keeps_real_compiler_and_inspection_checks(self):
        path = "gradle/qodana-idea-model.gradle.kts"
        expected = planner.plan([])
        expected.update(common=True, workflow=True, qodana=True)
        self.assertEqual(expected, planner.plan([path]))
        for other in ("build.gradle.kts", "settings.gradle.kts", "gradle/unknown-model.gradle.kts", "gradle/qodana-idea-model-other.gradle.kts",
                      "gradle/libs.versions.toml", "build-logic/src/main/kotlin/Plugin.kt"):
            for paths in ([path, other], [other, path]):
                with self.subTest(paths=paths):
                    self.assertEqual(planner.plan([other]), planner.plan(paths))
        self.assertEqual(planner.plan([], full=True), planner.plan([path], full=True))
        mixed = planner.plan(["api/src/main/kotlin/Element.kt"])
        mixed["workflow"] = True
        self.assertEqual(mixed, planner.plan([path, "api/src/main/kotlin/Element.kt"]))

    def test_workflow_tool_configuration_keeps_all_linter_checks_without_loading_clients(self):
        expected = planner.plan([])
        expected["workflow"] = True
        paths = [".github/mise.toml", ".github/mise.lock", ".github/.mise/locks/pypi-pyflakes/4.0.3/uv.lock"]
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(expected, planner.plan([path]))
        self.assertEqual(expected, planner.plan(paths))
        self.assertEqual(planner.plan([], full=True), planner.plan(paths, full=True))
        for other in ("api/src/main/kotlin/Element.kt", "mise.toml", "unknown-input"):
            for mixed in ([paths[0], other], [other, paths[0]]):
                with self.subTest(paths=mixed):
                    result = planner.plan([other])
                    result["workflow"] = True
                    self.assertEqual(result, planner.plan(mixed))

    def test_build_logic_tests_keep_jvm_analysis_without_unrelated_platform_acceptance(self):
        expected = planner.plan([])
        expected.update(common=True, workflow=True, qodana=True)
        tests = ["build-logic/src/test/kotlin/dev/s7a/strata/gradle/ProjectModelFunctionalTest.kt",
                 "build-logic/src/test/resources/model/settings.gradle.kts"]
        for path in tests:
            with self.subTest(path=path):
                self.assertEqual(expected, planner.plan([path]))
        self.assertEqual(expected, planner.plan(tests))
        for path in ("build-logic/src/main/kotlin/dev/s7a/strata/gradle/Plugin.kt", "build-logic/build.gradle.kts",
                     "build-logic/unknown-input", "gradle/libs.versions.toml"):
            for paths in ([tests[0], path], [path, tests[0]]):
                with self.subTest(paths=paths):
                    self.assertEqual(planner.plan([path]), planner.plan(paths))
        self.assertEqual(planner.plan([], full=True), planner.plan(tests, full=True))
        mixed = planner.plan(["api/src/main/kotlin/Element.kt"])
        mixed["workflow"] = True
        self.assertEqual(mixed, planner.plan(tests + ["api/src/main/kotlin/Element.kt"]))

    def test_source_model_setup_is_limited_to_unresolved_native_changes(self):
        native = "runtime/shared/minecraft-fabric/input/Changed.kt"
        cases = [([native], None, (True, True, False)),
                 (["integration/minecraft-fabric-26.3/Changed.kt"], None, (True, True, False)),
                 ([native, "api/Changed.kt"], None, (False, True, True)),
                 (["api/Changed.kt"], None, (False, True, True)),
                 (["docs/development/ci.md"], None, (False, False, False)),
                 (["tools/web/package.json"], None, (False, False, False)),
                 (["unknown-input"], None, (False, True, True)),
                 ([native], {}, (False, True, True)),
                 ([native], {"minecraft": {}, "documentation": []}, (False, True, False)),
                 ([native], {"minecraft": {"26.3": ["runtime/shared/minecraft-fabric/input"]}, "documentation": []}, (False, False, False))]
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "plan.json"
            model_file = Path(temporary) / "model.json"
            for paths, model, expected in cases:
                with self.subTest(paths=paths, model=model):
                    arguments = ["plan-ci.py", "--base", "a" * 40, "--head", "b" * 40, "--output", str(output)]
                    if model is not None:
                        model_file.write_text(json.dumps(model))
                        arguments += ["--model", str(model_file)]
                    with patch.object(sys, "argv", arguments), patch.dict(os.environ, {"GITHUB_OUTPUT": ""}), \
                            patch.object(planner.subprocess, "check_output", return_value="\0".join(paths).encode()), redirect_stdout(io.StringIO()):
                        planner.main()
                    result = json.loads(output.read_text())
                    self.assertEqual(expected, tuple(result[key] for key in ("source_model", "all_minecraft", "common")))

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
                            self.assertEqual([f':integration:minecraft-fabric-{entry["version"]}:check',
                                              f'-Pstrata.minecraftVersions={entry["version"]}',
                                              f"-Pstrata.canvas.backend={backend}", "-Pstrata.canvas.scope=full", "--continue"],
                                             entry["gradle_arguments"].split())
                            self.assertFalse(entry["documentation"])
                self.assertEqual(["26.3"], planner.client_plan(root, "26.3", "vulkan", subject)["minecraft"])
                build_file = root / "integration/minecraft-fabric-26.3/build.gradle.kts"
                build = build_file.read_text(encoding="utf-8")
                build_file.write_text(build.replace('dependsOn("runClientGameTest", runProductionClientGameTest)', 'dependsOn("runClientGameTest")'), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "does not declare"):
                    planner.client_plan(root, "26.3", "vulkan", subject)
                build_file.write_text(build, encoding="utf-8")
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

    def test_selected_clients_have_an_isolated_cli_mode_and_typed_outputs(self):
        fixture, _ = self.paired_fixture()
        fixture.selector_repository()
        for kind in ("runtime", "integration"):
            relative = Path(kind) / "minecraft-fabric-26.3/build.gradle.kts"
            target = fixture.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((Path(__file__).parents[2] / relative).read_bytes())
        fixture.selector_git("add", "--all")
        fixture.selector_commit()
        head = fixture.selector_git("rev-parse", "HEAD")
        arguments = ("--subject-ref", head, "--subject-root", str(fixture.root),
                     "--minecraft-versions", "26.3", "--minecraft-backend", "vulkan")
        completed, result = self.cli_plan(fixture.root, *arguments)
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(head, result["subject_ref"])
        self.assertEqual("vulkan", result["client_backend"])
        self.assertEqual(["26.3"], result["minecraft"])
        self.assertFalse(result["preflight"] or result["common"] or result["all_minecraft"])
        controls = [arguments + extra for extra in (("--full",), ("--base", head),
                    ("--paired-manifest", "missing.json"), ("--head", head), ("--model", "missing.json"))]
        controls += [("--subject-ref", ""), ("--full", "--minecraft-versions", "26.3"),
                     ("--base", head, "--minecraft-backend", "vulkan")]
        for control in controls:
            with self.subTest(arguments=control):
                completed, _ = self.cli_plan(fixture.root, *control)
                self.assertNotEqual(0, completed.returncode)

    def test_selected_check_uses_the_existing_subject_parity_dependencies(self):
        root = Path(__file__).parents[2]
        build = (root / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertRegex(build, r'fontParityComparisonsByVersion\[target\.version\]\?\.let\s*\{\s*comparison\s*->\s*tasks\.named\("check"\)\s*\{\s*dependsOn\(comparison\)\s*\}')
        self.assertIn("ciMinecraftVersions.isNotEmpty() ->", build)
        verification = (root / "runtime/minecraft-fonts-lwjgl/verification.gradle.kts").read_text(encoding="utf-8")
        comparison = verification.split('tasks.register<Test>("compareOfflineFont${contract.name}")', 1)[1].split("verifyOfflineFontParity.configure", 1)[0]
        self.assertIn("dependsOn(verification)", comparison)
        self.assertIn("mustRunAfter(nativeRunner)", comparison)
        self.assertIn('useJUnitPlatform { includeTags("font-offline-parity") }', comparison)
        self.assertIn("outputs.upToDateWhen { false }", comparison)
        self.assertIn("outputs.cacheIf { false }", comparison)

    def test_loaded_client_upload_retains_archives_tests_and_declared_offline_outputs(self):
        root = Path(__file__).parents[2]
        workflow = (root / ".github/workflows/jvm.yml").read_text(encoding="utf-8")
        upload = workflow.split("    - name: Upload loaded-client evidence for", 1)[1]
        paths = upload.split("        path: |\n", 1)[1].split("        if-no-files-found:", 1)[0]
        patterns = [line.strip() for line in paths.splitlines() if line.strip()]
        verification = (root / "runtime/minecraft-fonts-lwjgl/verification.gradle.kts").read_text(encoding="utf-8")
        offline_paths = re.findall(r'layout\.buildDirectory\.dir\("([^"\n]*offline[^"\n]*)"\)', verification)
        self.assertEqual(2, len(offline_paths))
        required = [f"{module}/build/libs/verified.jar" for module in ("api", "runtime/core", "runtime/minecraft", "runtime/minecraft-fonts-lwjgl", "performance-testkit", "integration/minecraft-fabric-26.3")]
        required += ["runtime/minecraft-fonts-lwjgl/build/test-results/compareOfflineFontMinecraft263/TEST-parity.xml"]
        required += [f'runtime/minecraft-fonts-lwjgl/build/{path.replace("$minecraftVersion", "26.3")}/result.properties' for path in offline_paths]
        required += [f"integration/minecraft-fabric-26.3/build/{directory}/receipt.properties" for directory in ("minecraft-parity-vulkan", "minecraft-production-parity-vulkan")]
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)
            for name in required + ["runtime/minecraft/build/classes/Unrelated.class", "runtime/minecraft/build/run/world/region.mca"]:
                file = destination / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text("verified", encoding="utf-8")
            retained = set()
            for pattern in patterns:
                for match in destination.glob(pattern):
                    retained.update(file.relative_to(destination).as_posix() for file in ([match] if match.is_file() else match.rglob("*")) if file.is_file())
            self.assertTrue(set(required) <= retained, sorted(set(required) - retained))
            self.assertNotIn("runtime/minecraft/build/classes/Unrelated.class", retained)
            self.assertNotIn("runtime/minecraft/build/run/world/region.mca", retained)

    def test_git_rename_diff_keeps_both_source_owners(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def git(*args):
                return subprocess.check_output(["git", "-c", "commit.gpgsign=false", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", *args], cwd=root)

            git("init", "--quiet")
            git("config", "maintenance.auto", "false")
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
            model.write_text(json.dumps({"minecraft": {"1.20": ["runtime/shared/old"], "26.3": ["runtime/shared/new"]},
                                         "documentation": ["runtime/shared/old"]}))
            script = Path(__file__).parents[1] / "plan-ci.py"
            output = subprocess.check_output([sys.executable, str(script), "--base", base, "--model", str(model)], cwd=root)
            result = json.loads(output)
            self.assertEqual(["1.20", "26.3"], result["minecraft"])
            self.assertTrue(result["docs_full"])

    def test_tooling_and_prose_changes_do_not_start_clients(self):
        for path in ("release/hangar-release.py", "docs/guides/screens.md", ".github/workflows/publish-release.yml", "gradle/tests/test_plan_ci.py", "gradle/verify-qodana-model-fixtures.sh"):
            paths = [path]
            result = planner.plan(paths)
            self.assertFalse(result["all_minecraft"])
            self.assertEqual([], result["minecraft"])
            self.assertFalse(result["qodana"])

    def test_shared_and_renamed_sources_include_both_owners(self):
        model = {"minecraft": {"1.20": ["runtime/shared/old"], "26.3": ["runtime/shared/new"]}, "documentation": []}
        result = planner.plan(["runtime/shared/old/Deleted.kt", "runtime/shared/new/Added.kt"], model)
        self.assertEqual(["1.20", "26.3"], result["minecraft"])
        self.assertFalse(result["all_minecraft"])

    def test_shared_documentation_sources_keep_native_and_documentation_checks(self):
        root = "integration/shared/minecraft-fabric/scenarios/gui-extractor/src/gametest/kotlin"
        model = {"minecraft": {"26.3": [root], "1.20": ["runtime/shared/native"]}, "documentation": [root]}
        for path in (root, root + "/MinecraftProgressExample.kt", root + "/RemovedExample.kt"):
            with self.subTest(path=path):
                result = planner.plan([path], model)
                self.assertEqual(["26.3"], result["minecraft"])
                self.assertTrue(result["docs_full"] and result["qodana"])
                self.assertFalse(result["all_minecraft"] or result["common"] or result["web"])
        for path in ("runtime/shared/native/Changed.kt", root + "-other/Changed.kt"):
            with self.subTest(path=path):
                self.assertFalse(planner.plan([path], model)["docs_full"])
        renamed = planner.plan([root + "/Deleted.kt", "runtime/shared/native/Added.kt"], model)
        self.assertEqual(["1.20", "26.3"], renamed["minecraft"])
        self.assertTrue(renamed["docs_full"])

    def test_native_inventory_source_keeps_documentation_without_broadening_siblings(self):
        root = "integration/shared/minecraft-fabric/screen/gui-extractor/src/gametest/kotlin"
        inventory = root + "/dev/s7a/strata/integration/minecraft/fabric/MinecraftInventoryExample.kt"
        model = {"minecraft": {version: [root] for version in ("26.1", "26.2", "26.3")}, "documentation": [inventory]}
        result = planner.plan([inventory], model)
        self.assertEqual(["26.1", "26.2", "26.3"], result["minecraft"])
        self.assertTrue(result["docs_full"] and result["qodana"])
        self.assertFalse(result["all_minecraft"] or result["common"] or result["web"])
        sibling = planner.plan([inventory.replace("MinecraftInventoryExample", "InventorySlotSynchronizationGameTest")], model)
        self.assertEqual(result["minecraft"], sibling["minecraft"])
        self.assertFalse(sibling["docs_full"])

    def test_unknown_source_model_shapes_keep_full_verification(self):
        for model in ({}, [], {"26.3": ["runtime/shared/native"]},
                      {"minecraft": [], "documentation": []}, {"minecraft": {}, "documentation": {}}):
            with self.subTest(model=model):
                self.assertEqual(planner.plan([], full=True), planner.plan(["runtime/shared/native/Changed.kt"], model))

    def test_unknown_ownership_and_global_inputs_expand_checks(self):
        self.assertTrue(planner.plan(["runtime/shared/unmapped/New.kt"], {})["all_minecraft"])
        for path in ("build.gradle.kts", "new-build-tool.toml"):
            result = planner.plan([path])
            self.assertTrue(result["all_minecraft"] and result["common"] and result["qodana"])

    def test_later_web_assets_do_not_disable_requested_analysis(self):
        result = planner.plan(["runtime/web/src/Web.kt", "tools/web/package.json"])
        self.assertTrue(result["qodana"])

    def test_compiled_preflight_qualification_selects_its_actual_boundary(self):
        for path in ("gradle/performance/paired-jvm.py", "gradle/performance/fixtures/QualifiedFixtureController.kt", "gradle/tests/test_paired_jvm.py", ".github/workflows/jvm.yml"):
            with self.subTest(path=path):
                self.assertTrue(planner.plan([path])["preflight"])
        self.assertFalse(planner.plan(["runtime/core/src/commonMain/Runtime.kt"])["preflight"])
        self.assertTrue(planner.plan([], full=True)["preflight"])

    def test_python_performance_controllers_keep_workflow_and_compiled_native_qualification(self):
        for path in ("paired-jvm.py", "paired-browser.py", "windows-process-history.py", "future-controller.py"):
            with self.subTest(path=path):
                result = planner.plan(["gradle/performance/" + path, "gradle/tests/test_paired_jvm.py"])
                self.assertEqual({"workflow", "preflight"}, {name for name, value in result.items() if value is True})
                self.assertEqual([], result["minecraft"])
        full = planner.plan(["gradle/performance/paired-jvm.py"], full=True)
        self.assertTrue(all(value for name, value in full.items() if name != "minecraft"))

    def test_controller_selection_preserves_mixed_runtime_and_native_or_unknown_build_checks(self):
        for paths in (["gradle/performance/paired-jvm.py", "runtime/core/src/commonMain/Runtime.kt"],
                      ["runtime/core/src/commonMain/Runtime.kt", "gradle/performance/paired-jvm.py"]):
            mixed = planner.plan(paths)
            self.assertTrue(mixed["workflow"] and mixed["preflight"] and mixed["common"] and mixed["all_minecraft"] and mixed["qodana"])
        for path in ("gradle/performance/invoke-windows.ps1", "gradle/performance/bounded-jvm.init.gradle.kts",
                     "gradle/performance/fixtures/QualifiedFixtureController.kt", "gradle/performance/new-build-input.bin",
                     "gradle/performance-other/controller.py"):
            with self.subTest(path=path):
                result = planner.plan([path])
                self.assertTrue(all(value for name, value in result.items() if name != "minecraft"))

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
