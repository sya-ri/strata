"""Verify shared browser selection, complete unions and failure boundaries without browsers or Java."""
import copy
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import test_paired_jvm as fixtures


class PairedBrowserTest(unittest.TestCase):
    def test_ready_leak_requires_actual_survival_in_the_owned_job(self):
        source = Path(__file__).resolve().parent.parent / "performance/verify-windows-boundary.py"
        spec = importlib.util.spec_from_file_location("boundary", source)
        boundary = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(boundary)
        program = {"path": str(self.root / "node.exe")}
        primary = {"pid": 1, "primaryBeforeResume": True, "exitCode": 0, "exitFileTime": 3}
        child = {"pid": 2, "image": program["path"], "inOwnedJob": True, "completed": True, "handleClosed": True,
                 "creationFileTime": 2, "exitFileTime": 4, "affinityMask": 3, "systemAffinityMask": 3}
        terminal = {"callerPid": 1, "exitCode": 0, "members": {"1": [primary], "2": [child]}, "naturalRelease": False, "timedOut": False,
                    "activeProcessesAtRelease": 1, "handlesClosed": True, "activeProcessesAfterCleanup": 0,
                    "inheritedAffinityMask": 3, "systemAffinityMask": 3, "observedJobLimitBytes": 3 * 1024**3, "observedJobFlags": 0x2200}
        console = '{"readyChildPid": 2}\nException: Bounded invocation failed; inspect retained terminal\n'
        boundary.validate_node_leak(terminal, program, console)
        for malformed in ("", "Exception before acknowledgement\n", "[]\n", '{"readyChildPid": true}\n'):
            with self.subTest(console=malformed), self.assertRaises(ValueError):
                boundary.validate_node_leak(terminal, program, malformed)
        for identity in ({}, {"readyChildPid": 1}, {"readyChildPid": 99}):
            with self.subTest(identity=identity), self.assertRaises(ValueError):
                boundary.validate_node_leak(terminal, program, json.dumps(identity) + "\n")
        for change in ({"inOwnedJob": False}, {"completed": False}, {"handleClosed": False}, {"exitFileTime": 3}, {"creationFileTime": 4},
                       {"affinityMask": 1}, {"image": str(self.root / "other.exe")}):
            changed = copy.deepcopy(terminal)
            changed["members"]["2"][0].update(change)
            with self.subTest(child=change), self.assertRaises(ValueError):
                boundary.validate_node_leak(changed, program, console)
        for change in ({"naturalRelease": True}, {"timedOut": True}, {"activeProcessesAtRelease": 0}, {"activeProcessesAfterCleanup": 1},
                       {"handlesClosed": False}, {"exitCode": 7}, {"observedJobLimitBytes": 4 * 1024**3}, {"observedJobFlags": 0x3200}):
            with self.subTest(terminal=change), self.assertRaises(ValueError):
                boundary.validate_node_leak({**terminal, **change}, program, console)

    def setUp(self):
        self.fixture = fixtures.PairedJvmTest("runTest")
        self.fixture.setUp()
        self.addCleanup(self.fixture.temporary.cleanup)
        self.root = self.fixture.root
        self.shared = fixtures.PAIRED
        self.browser = self.shared.browser_controller()
        adapter = self.root / "tools/web/build.mjs"
        adapter.parent.mkdir(parents=True)
        adapter.write_text("// Synthetic controller input; starts no browser.\n", encoding="utf-8")
        standard = [{"id": "reactive-" + theme, "phases": ["Initial", "Idle", "Update", "Input", "Resize", "Release"]} for theme in ("native", "minecraft")]
        standard += [{"id": f"component-{index}-{theme}", "phases": ["Create", "Idle", "Update", "Release"]} for theme in ("native", "minecraft") for index in range(11)]
        self.manifest = {
            "schema": "strata-paired-browser-ci-v1",
            "sources": {side: {"ref": "a" * 40, "tree": "b" * 40, "patches": []} for side in ("fixture", "baseline", "candidate")},
            "runtime_files": [], "collector_path": "performance-testkit/build/libs/testkit-js.jar",
            "inputs": [{"label": "fixture", "pin": self.fixture.pin}],
            "compiled_pins": {side: [{"path": path, "sha256": "a" * 64} for path in ("performance-testkit/build/libs/testkit-js.jar", "integration/web/build/dist/js/productionExecutable/application.js")] for side in ("baseline", "candidate")},
            "java_pins": [{"role": role, "executable_sha256": "a" * 64, "release_sha256": "b" * 64} for role in ("toolchain", "launcher")],
            "correctness": {"required_tests": ["dev.example.ParityTest#fullTransitions"], "files": [{"glob": "parity/*.png", "count": 6}]},
            "collections": [{"name": "standard", "adapter": {"path": "tools/web/build.mjs", "sha256": self.shared.digest(adapter)},
                             "arguments": ["performance", "{build}", "{collector}", "{output}", "{inputs}"],
                             "input_paths": {"fixture": {"scope": "evidence", "path": "inputs/input-0.bin"}, "fixture-input-manifest": {"scope": "evidence", "path": "fixture-inputs.json"}, "standard-component-inventory": {"scope": "workspace", "path": "integration/web/build/site/performance-inventory.json"}},
                             "scenarios": standard, "conditions": {"viewport": {"width": 640, "height": 480}, "warmup": 30, "samples": 60}, "expected_cases": 300}],
        }

    def load(self, value):
        path = self.root / "manifest.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return self.shared.load_manifest(self.root, path)

    def supplemental(self):
        result = copy.deepcopy(self.manifest["collections"][0])
        result.update(name="supplemental", scenarios=[{"id": f"independent-{index}-{theme}", "phases": ["Update"]} for index in range(12) for theme in ("native", "minecraft")], expected_cases=72)
        return result

    def test_whole_standard_and_supplemental_unions_keep_original_profiles(self):
        self.assertEqual(self.load(self.manifest), self.manifest)
        value = copy.deepcopy(self.manifest)
        value["collections"].append(self.supplemental())
        self.assertEqual(sum(item["expected_cases"] for item in self.load(value)["collections"]), 372)
        self.assertEqual(len(value["collections"]) * len(self.browser.ENGINES) * 3 * 2, 36)
        roles = {role: f"C:/owned path/{role}" for role in self.browser.TOKENS}
        self.assertEqual(self.browser.expand_arguments(value["collections"][0], roles), ["performance", roles["build"], roles["collector"], roles["output"], roles["inputs"]])

    def test_incomplete_or_changed_manifest_cannot_select_a_partial_run(self):
        for mutation in (
            lambda value: value["collections"][0].update(expected_cases=299),
            lambda value: value["collections"][0]["scenarios"].pop(),
            lambda value: value["collections"].append(copy.deepcopy(value["collections"][0])),
            lambda value: value["collections"][0]["conditions"].update(samples=59),
            lambda value: value["collections"][0]["conditions"].update(warmup=0),
            lambda value: value["collections"][0]["arguments"].append("{shell}"),
            lambda value: value["collections"][0]["input_paths"].pop("fixture"),
            lambda value: value["compiled_pins"]["baseline"].pop(),
            lambda value: value["correctness"].update(required_tests=[]),
            lambda value: value.update(runtime_files=["integration/web/src/jsMain/ChangedFixture.kt"]),
            lambda value: value.update(workSeconds=3600),
        ):
            value = copy.deepcopy(self.manifest)
            mutation(value)
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.load(value)

    def test_browser_manifest_uses_the_same_real_deleted_producer_consumer_selection(self):
        self.fixture.manifest = self.manifest
        _, producer, head, path, expected = self.fixture.input_consumer()
        self.assertFalse((self.root / ".github/performance").exists())
        with self.shared.selected_inputs(self.root, path, data_ref=producer, expected_head=head) as selection:
            self.assertEqual(self.shared.load_manifest(selection["root"], selection["path"]), expected)
            self.assertEqual(selection["producer"], producer)
        self.assertEqual(self.fixture.selector_git("status", "--porcelain=v1"), "")
        self.assertEqual(self.fixture.selector_git("rev-parse", "HEAD"), head)

    def test_fixture_and_collector_source_changes_are_rejected_before_launch(self):
        inventories = {"fixture": {"adapter.mjs": "same", "runtime/web/src/jsMain/Renderer.kt": "old"},
                       "baseline": {"adapter.mjs": "same", "runtime/web/src/jsMain/Renderer.kt": "old"},
                       "candidate": {"adapter.mjs": "same", "runtime/web/src/jsMain/Renderer.kt": "new"}}
        value = copy.deepcopy(self.manifest)
        value["runtime_files"] = ["runtime/web/src/jsMain/Renderer.kt"]
        workspaces = {side: side for side in inventories}
        api = SimpleNamespace(require=self.shared.require, source_files=lambda side, tree: inventories[side])
        self.browser.common_sources(api, workspaces, value)
        inventories["baseline"]["adapter.mjs"] = "changed fixture"
        with self.assertRaisesRegex(ValueError, "Common browser fixture"):
            self.browser.common_sources(api, workspaces, value)

    def test_terminal_failure_refusal_and_leak_cannot_credit_success(self):
        terminal = {"exitCode": 0, "naturalRelease": True, "handlesClosed": True, "activeProcessesAfterCleanup": 0, "jobName": "owned"}
        admission = {"inOwnedJob": True, "jobName": "owned", "availablePhysicalBytes": 4 * 1024**3}
        self.browser.validate_terminal(self.shared, 0, terminal, admission)
        for change in ({"exitCode": 7}, {"naturalRelease": False}, {"handlesClosed": False}, {"activeProcessesAfterCleanup": 1}, {"error": "pin failure"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.browser.validate_terminal(self.shared, 0, {**terminal, **change}, admission)
        for change in ({"inOwnedJob": False}, {"availablePhysicalBytes": 4 * 1024**3 - 1}, {"jobName": "other"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.browser.validate_terminal(self.shared, 0, terminal, {**admission, **change})

    def test_actual_node_generation_requires_the_pinned_image_birth_release_and_no_java(self):
        program = {"path": str(self.root / "node.exe")}
        member = {"pid": 1, "image": program["path"], "primaryBeforeResume": True, "inOwnedJob": True, "completed": True, "handleClosed": True, "exitCode": 0,
                  "creationFileTime": 1, "exitFileTime": 2, "affinityMask": 15, "systemAffinityMask": 15}
        terminal = {"members": {"1": [member]}, "inheritedAffinityMask": 15, "systemAffinityMask": 15, "callerPid": 1, "jobName": "owned",
                    "primaryAdmission": {"beforeResume": True, "pid": 1, "creationFileTime": 1, "image": program["path"], "jobName": "owned", "inOwnedJob": True, "availablePhysicalBytes": 4 * 1024**3}}
        self.browser.validate_node_generation(self.shared, terminal, program)
        for change in ({"image": str(self.root / "other/node.exe")}, {"primaryBeforeResume": False}, {"pid": 2}, {"completed": False}, {"exitCode": 7}, {"exitFileTime": 0}, {"affinityMask": 1}):
            value = {**terminal, "members": {"1": [{**member, **change}]}}
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.browser.validate_node_generation(self.shared, value, program)
        for members in ({}, {"1": [member, member]}, {"1": [member], "2": [{**member, "image": str(self.root / "java.exe")}]}):
            with self.subTest(members=members), self.assertRaises(ValueError):
                self.browser.validate_node_generation(self.shared, {**terminal, "members": members}, program)
        for change in ({"beforeResume": False}, {"pid": 2}, {"creationFileTime": 3}, {"image": "wrong"}, {"inOwnedJob": False}, {"availablePhysicalBytes": 1}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.browser.validate_node_generation(self.shared, {**terminal, "primaryAdmission": {**terminal["primaryAdmission"], **change}}, program)

    def test_shared_table_retains_regressions_independent_runs_and_unavailable_ratio(self):
        comparison = {"baseline_runs": [0, 0, 0], "candidate_runs": [3, 1, 2], "baseline": 0, "candidate": 2, "delta": 2, "candidate_to_baseline": None}
        row = {"engine": "chromium", "scenario": "independent", "phase": "Update", "operation_wall": {"p99_ns": comparison}, "action_to_animation_frame": {"p99_ns": comparison}, "work_counts": {"setters": comparison}}
        path = self.root / "comparison.json"
        path.write_text(json.dumps({"rows": [row]}), encoding="utf-8")
        self.browser.table(self.root, [{"name": "whole", "path": str(path)}])
        report = (self.root / "report.md").read_text(encoding="utf-8")
        self.assertIn("[3, 1, 2]", report)
        self.assertIn("| 0 | 2 | 2 | None |", report)
        self.assertIn("work_counts.setters | count", report)
        self.assertIn("CPU/allocation/GPU/FPS and loaded-game gains are unavailable", report)

    def test_whole_orchestration_uses_distinct_side_workspaces_and_stops_after_failure(self):
        """Synthetic command outputs test sequencing and evidence retention, never performance or OS ownership."""
        data = copy.deepcopy(self.manifest)
        data["collections"].append(self.supplemental())
        self.root.joinpath("gradle").mkdir()
        self.root.joinpath("gradle/libs.versions.toml").write_text('[versions]\nperformance-toolchain="17.0.1+1"\nperformance-launcher="25.0.1"\n', encoding="utf-8")
        homes = {}
        for role, provider, version in (("toolchain", "Eclipse Adoptium", "17.0.1"), ("launcher", "Oracle Corporation", "25.0.1")):
            home = self.root / role
            home.joinpath("bin").mkdir(parents=True)
            home.joinpath("bin/java.exe").write_bytes(b"synthetic Java; never launched")
            home.joinpath("release").write_text(f'IMPLEMENTOR="{provider}"\nJAVA_VERSION="{version}"\n', encoding="utf-8")
            homes[role] = home
            item = next(item for item in data["java_pins"] if item["role"] == role)
            item.update(executable_sha256=self.shared.digest(home / "bin/java.exe"), release_sha256=self.shared.digest(home / "release"))
        node = self.root / "node.exe"
        node.write_bytes(b"synthetic Node; never launched")
        for pins in data["compiled_pins"].values():
            for item in pins:
                item["sha256"] = self.shared.hashlib.sha256(b"synthetic compiled bytes").hexdigest()

        for failure, copy_failure in ((None, False), ("collect-standard-baseline", False), (None, True), ("collect-standard-baseline", True)):
            output = self.root / (("complete" if failure is None else "failed") + ("-copy-failure" if copy_failure else ""))
            calls, side_paths = [], {}
            api = SimpleNamespace(**vars(self.shared))
            api.git = lambda *args: "a" * 40
            api.load_manifest = lambda *args: data
            api.preserve_driver_sources = lambda *args: {}
            api.verify_driver_sources = lambda *args: None
            api.source_files = lambda *args: {"same-common-fixture": "same"}
            api.source_guard = lambda *args: None
            api.class_load_inventory = lambda *args: {}
            api.preserve_class_loads = lambda *args: {}
            api.verify_vms = lambda *args: None
            api.preserve_test_reports = lambda *args: None
            api.verify_test_gate = lambda directory, gate: self.shared.write_new(directory / "junit-verified.json", {"counts": {"skipped": 0}})
            if copy_failure:
                def preserve_file(source, target):
                    if target.is_relative_to(output / "prepared"):
                        raise OSError("Injected prepared-only copy failure")
                    return self.shared.preserve_file(source, target)
                api.preserve_file = preserve_file

            def materialize(root, attempt, side, source, input_root):
                workspace = attempt / "workspaces" / side
                workspace.mkdir(parents=True)
                side_paths[side] = workspace
                for item in data["compiled_pins"][side]:
                    path = workspace / item["path"]
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"synthetic compiled bytes")
                path = workspace / data["collections"][0]["adapter"]["path"]
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((self.root / path.relative_to(workspace)).read_bytes())
                return workspace, source

            def invoke(arguments, root, console):
                request = self.shared.read_json(Path(arguments[arguments.index("-Request") + 1]))
                terminal_path = Path(arguments[arguments.index("-Report") + 1])
                directory = terminal_path.parent
                name = directory.name
                calls.append(name)
                console.write_bytes(b"synthetic command transcript\n")
                self.assertEqual((request["minimumFreePhysicalBytes"], request["jobCommittedBytes"], request["workSeconds"], request["cleanupSeconds"]), (4 * 1024**3, 3 * 1024**3, 1800, 30))
                member = {"pid": 1, "image": str(node), "primaryBeforeResume": True, "inOwnedJob": True, "completed": True, "handleClosed": True, "exitCode": 0,
                          "creationFileTime": 1, "exitFileTime": 2, "affinityMask": 15, "systemAffinityMask": 15}
                exit_code = 7 if name == failure else 0
                self.shared.write_new(terminal_path, {"exitCode": exit_code, "naturalRelease": True, "handlesClosed": True, "activeProcessesAfterCleanup": 0,
                                                    "jobName": name, "callerPid": 1, "members": {"1": [member]}, "inheritedAffinityMask": 15, "systemAffinityMask": 15,
                                                    "primaryAdmission": {"beforeResume": True, "pid": 1, "creationFileTime": 1, "image": str(node), "jobName": name, "inOwnedJob": True, "availablePhysicalBytes": 4 * 1024**3}})
                self.shared.write_new(directory / "child-admission.json", {"beforeResume": True, "pid": 1, "creationFileTime": 1, "image": str(node), "inOwnedJob": True, "jobName": name, "availablePhysicalBytes": 4 * 1024**3})
                workspace = Path(request["workspace"])
                if name.startswith("build-"):
                    site = workspace / self.browser.BUILD / "site"
                    site.mkdir(parents=True)
                    site.joinpath("application.js").write_bytes(b"synthetic compiled bytes")
                    site.joinpath("performance-inventory.json").write_text("{}", encoding="utf-8")
                if name.startswith("verify-"):
                    parity = workspace / self.browser.BUILD / "parity"
                    parity.mkdir()
                    for index in range(6):
                        parity.joinpath(str(index) + ".png").write_bytes(b"synthetic PNG placeholder")
                if name.startswith("collect-") and exit_code == 0:
                    Path(request["arguments"][4]).write_text("{}", encoding="utf-8")
                if name.startswith("compare-"):
                    comparison_request = self.shared.read_json(Path(request["arguments"][1]))
                    collection = next(item for item in data["collections"] if "compare-" + item["name"] == name)
                    Path(comparison_request["outputPath"]).write_text(json.dumps({"status": "passed", "rows": [dict(engine="synthetic", scenario="synthetic", phase="synthetic", operation_wall={}, action_to_animation_frame={}, work_counts=None) for _ in range(collection["expected_cases"])]}), encoding="utf-8")
                return exit_code

            api.materialize, api.invoke = materialize, invoke
            node_metadata = json.dumps({"path": str(node), "version": "synthetic"})
            selection = {"root": self.root, "path": self.root / "manifest.json", "producer": "", "execution_head": "a" * 40}
            selection["path"].write_text(json.dumps(data), encoding="utf-8")
            with patch.dict(self.browser.os.environ, {"JAVA_HOME": str(homes["launcher"]), "STRATA_PAIRED_TOOLCHAIN": str(homes["toolchain"])}), patch.object(self.browser.shutil, "which", return_value=str(node)), patch.object(self.browser.subprocess, "check_output", side_effect=lambda args, **kwargs: node_metadata if args[0] == str(node) else "{}"):
                if failure is None and copy_failure is False:
                    self.browser.run(api, self.root, selection, output)
                else:
                    with self.assertRaisesRegex(ValueError, "Prepared evidence preservation failed" if failure is None else "Owned browser phase failed"):
                        self.browser.run(api, self.root, selection, output)
            record = self.shared.read_json(output / "attempt.json")
            self.assertNotEqual(side_paths["baseline"], side_paths["candidate"])
            self.assertTrue(output.with_suffix(".zip").is_file())
            if failure is None and copy_failure is False:
                self.assertEqual((record["status"], record["expected_cases"], record["measured_browser_invocations"]), ("passed", 372, 36))
                self.assertEqual(len(calls), 12)
            elif failure is not None:
                self.assertEqual(record["status"], "failed")
                self.assertEqual(calls[-1], failure)
                self.assertNotIn("collect-standard-candidate", calls)
                self.assertFalse((output / "report.md").exists())
                self.assertIn("Owned browser phase failed", record["error"])
            else:
                self.assertEqual(record["status"], "failed")
                self.assertEqual(calls[-1], "compare-supplemental")
                self.assertTrue((output / "phases/compare-supplemental/terminal.json").is_file())
                self.assertTrue((output / "standard-comparison.json").is_file())
            if copy_failure:
                self.assertTrue(record["preservation_errors"])
                self.assertTrue(all("Injected prepared-only copy failure" in item["error"] for item in record["preservation_errors"]))


if __name__ == "__main__":
    unittest.main()
