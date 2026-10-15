"""Verify shared browser selection, complete unions and failure boundaries without browsers or Java."""
import copy
import importlib.util
import json
import os
import subprocess
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from unittest.mock import Mock, patch

import test_paired_jvm as fixtures


class PairedBrowserTest(unittest.TestCase):
    def test_history_refusal_keeps_fatal_error_and_requires_finalized_cleanup_accounting(self):
        source = Path(__file__).resolve().parent.parent / "performance/verify-windows-boundary.py"
        spec = importlib.util.spec_from_file_location("boundary_refusal", source)
        boundary = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(boundary)
        error = "Owned process history exceeds the existing terminal report bound."
        members = {str(pid): [{"pid": pid, "creationFileTime": pid * 10000, "exitFileTime": pid * 10000 + 1000,
                              "completed": True, "handleClosed": True}] for pid in range(1, 258)}
        terminal = {"error": error, "notificationError": error, "naturalRelease": False, "timedOut": False,
                    "handlesClosed": True, "activeProcessesAfterCleanup": 0, "notificationReaderJoined": True,
                    "members": members, "maximumRetainedHandles": 52, "maximumListedMembers": 22,
                    "memberHistorySerializedByteBound": 3 * 1024**2 + 412, "totalProcesses": 259,
                    "generationAccounting": {"schema": "strata-owned-generation-accounting-v1", "totalProcesses": 259,
                                             "capturedGenerations": 257, "closedGenerations": 257, "complete": False},
                    "processAcquisition": {"pending": 0, "metadataInFlight": 0, "physicalHandles": 0, "unpublishedByteBound": 0}}
        boundary.validate_history_refusal(terminal, 2 * 1024**2)
        self.assertEqual(terminal["error"], error)
        self.assertFalse(terminal["generationAccounting"]["complete"])
        for mode in ("original-cleanup-failure", "metadata-failure", "active", "missing-active", "missing-accounting", "wrong-total", "false-complete",
                     "wrong-closed-count", "unjoined", "unclosed-generation", "missing-exit", "duplicate", "pending", "inflight",
                     "unclosed-handle", "unpublished", "lost-fatal-error", "natural", "timeout", "unrefused-bound", "oversized-report"):
            changed = copy.deepcopy(terminal)
            if mode == "original-cleanup-failure":
                changed.update(handlesClosed=False, cleanupError=error)
                changed.pop("activeProcessesAfterCleanup")
                changed.pop("generationAccounting")
            elif mode == "metadata-failure":
                changed["cleanupError"] = "Acquired owned process metadata could not be observed."
            elif mode == "missing-active":
                changed.pop("activeProcessesAfterCleanup")
            elif mode == "missing-accounting":
                changed.pop("generationAccounting")
            elif mode == "wrong-total":
                changed["generationAccounting"]["totalProcesses"] = 256
            elif mode == "wrong-closed-count":
                changed["generationAccounting"]["closedGenerations"] = 256
            elif mode == "false-complete":
                changed["generationAccounting"]["complete"] = True
            elif mode == "unclosed-generation":
                changed["members"]["1"][0]["handleClosed"] = False
            elif mode == "missing-exit":
                changed["members"]["1"][0]["exitFileTime"] = 0
            elif mode == "duplicate":
                changed["members"]["2"] = copy.deepcopy(changed["members"]["1"])
            elif mode in ("pending", "inflight", "unclosed-handle", "unpublished"):
                changed["processAcquisition"][{"pending": "pending", "inflight": "metadataInFlight",
                                               "unclosed-handle": "physicalHandles", "unpublished": "unpublishedByteBound"}[mode]] = 1
            else:
                key, value = {"active": ("activeProcessesAfterCleanup", 1), "unjoined": ("notificationReaderJoined", False),
                              "lost-fatal-error": ("error", ""), "natural": ("naturalRelease", True), "timeout": ("timedOut", True),
                              "unrefused-bound": ("memberHistorySerializedByteBound", 3 * 1024**2),
                              "oversized-report": ("report_bytes", 4 * 1024**2 + 1)}[mode]
                changed[key] = value
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                boundary.validate_history_refusal(changed, changed.get("report_bytes", 2 * 1024**2))

    def test_independent_immediate_oracle_observes_image_before_resume_and_preserves_same_handle_birth(self):
        source = Path(__file__).resolve().parent.parent / "performance/windows-process-history.py"
        spec = importlib.util.spec_from_file_location("immediate_oracle", source)
        oracle = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(oracle)
        for mode in ("complete", "changed-birth", "missing-image", "zero-birth", "early-exit", "zero-exit", "reverse-lifetime", "nonzero", "resume-failed", "image-query-failed"):
            with self.subTest(mode=mode):
                state = {"finished": False}
                events = []
                birth = 116444736000000000 + 1000 * 10000
                image = str(self.root / "independently-observed-cmd.exe")

                def times(handle, created, exited, system, user):
                    self.assertEqual(handle.value, 101)
                    events.append("exit-times" if state["finished"] else "birth-times")
                    created._obj.value = birth + (1 if state["finished"] and mode == "changed-birth" else 0)
                    if not state["finished"] and mode == "zero-birth":
                        created._obj.value = 0
                    exited._obj.value = (0 if mode == "zero-exit" else birth - 1 if mode == "reverse-lifetime" else birth + 10000) if state["finished"] else birth + 10000 if mode == "early-exit" else 0
                    return True

                def observed_image(handle, flags, target, size):
                    self.assertEqual(handle.value, 101)
                    self.assertFalse(state["finished"], "Image query raced an exited process")
                    events.append("image-before-resume")
                    target.value = "" if mode == "missing-image" else image
                    return mode != "image-query-failed"

                def resume(handle):
                    self.assertEqual(handle.value, 102)
                    events.append("resume")
                    return 0xFFFFFFFF if mode == "resume-failed" else 1

                def wait(handle, timeout):
                    self.assertEqual(handle, 101)
                    events.append("wait")
                    state["finished"] = True
                    return 0

                api = SimpleNamespace(CreateProcess=Mock(return_value=(101, 102, 123, 456)), INFINITE=0xFFFFFFFF,
                    WaitForSingleObject=Mock(side_effect=wait), GetExitCodeProcess=Mock(side_effect=lambda handle: (7 if mode == "nonzero" else 0) if state["finished"] else 259),
                    TerminateProcess=Mock(side_effect=lambda handle, code: state.update(finished=True)), CloseHandle=Mock())
                kernel = SimpleNamespace(GetProcessTimes=Mock(side_effect=times), QueryFullProcessImageNameW=Mock(side_effect=observed_image), ResumeThread=Mock(side_effect=resume))
                with patch.dict(sys.modules, {"_winapi": api}), patch.object(oracle.ctypes, "WinDLL", return_value=kernel, create=True), \
                     patch.object(oracle.ctypes, "WinError", side_effect=lambda code=0: OSError("Synthetic Windows failure"), create=True), \
                     patch.object(oracle.ctypes, "get_last_error", return_value=31, create=True), \
                     patch.object(oracle.subprocess, "STARTUPINFO", return_value=SimpleNamespace(), create=True), \
                     patch.object(oracle.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True), patch.dict(oracle.os.environ, {"COMSPEC": "requested-path-is-not-observed.exe"}):
                    child = None
                    try:
                        if mode == "complete":
                            child = oracle.immediate_child()
                            oracle.resume_immediate(child)
                            record = oracle.completed_identity(child)
                            self.assertEqual(record, {"pid": 123, "creationFileTime": birth, "exitFileTime": birth + 10000, "image": image, "exitCode": 0})
                            self.assertEqual(events, ["birth-times", "image-before-resume", "resume", "wait", "exit-times"])
                        else:
                            with self.assertRaises((ValueError, OSError)):
                                child = oracle.immediate_child()
                                oracle.resume_immediate(child)
                                oracle.completed_identity(child)
                    finally:
                        if child is not None:
                            oracle.close_immediate(child)
                    self.assertEqual([call.args[0] for call in api.CloseHandle.call_args_list], [102, 101])

    def test_immediate_descendant_control_requires_complete_independent_physical_histories(self):
        source = Path(__file__).resolve().parent.parent / "performance/verify-windows-boundary.py"
        spec = importlib.util.spec_from_file_location("boundary_immediate", source)
        boundary = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(boundary)
        members = {}
        for index in range(32):
            pid = index + 1
            birth = 116444736000000000 + index * 1000000
            members[str(pid)] = [{"pid": pid, "creationFileTime": birth, "exitFileTime": birth + 10000,
                                  "image": str(self.root / "cmd.exe"), "inOwnedJob": True, "completed": True,
                                  "handleClosed": True, "exitCode": 0, "affinityMask": 15, "systemAffinityMask": 15}]
        terminal = {"members": members, "naturalRelease": True, "handlesClosed": True, "nativeInvoked": True,
                    "activeProcessesAfterCleanup": 0, "inheritedAffinityMask": 15, "systemAffinityMask": 15,
                    "notificationReaderJoined": True, "totalProcesses": 32,
                    "generationAccounting": {"schema": "strata-owned-generation-accounting-v1", "complete": True,
                                             "totalProcesses": 32, "capturedGenerations": 32, "closedGenerations": 32}}
        byte_bound = sum(1024 + 6 * len(member[0]["image"].encode("utf-16-le")) // 2 for member in members.values())
        terminal.update(processAcquisition={"pending": 0, "metadataInFlight": 0, "physicalHandles": 0, "maximumPhysicalHandles": 32,
                                            "maximumPending": 8, "unpublishedByteBound": 0, "publishedByteBound": byte_bound},
                        memberHistorySerializedByteBound=byte_bound, newProcessNotifications=32,
                        notificationOpenSuccesses=32, notificationOpenFailures=0, notificationImageSuccesses=32,
                        notificationImageFailures=0, notificationMetadataSuccesses=32, notificationMetadataFailures=0)
        actual = {"immediate": True, "concurrentChildren": 8, "generations": [copy.deepcopy(value[0]) for value in members.values()]}
        receipt = boundary.validate_immediate_history(terminal, actual)
        self.assertEqual(receipt["independentGenerations"], 32)
        self.assertEqual(receipt["observedBelowFormerPollInterval"], 32)
        for mode in ("missing", "duplicate", "image", "birth", "exit", "accounting", "unclosed", "no-short-lifetime",
                     "missing-acquisition", "pending-not-drained", "metadata-inflight", "unclosed-acquisition", "metadata-failure",
                     "image-failure", "missing-metadata", "duplicate-delivery", "handle-bound", "byte-bound", "wrong-job", "wrong-affinity"):
            with self.subTest(mode=mode):
                changed, oracle = copy.deepcopy(terminal), copy.deepcopy(actual)
                if mode == "missing":
                    changed["members"].pop("1")
                elif mode == "duplicate":
                    oracle["generations"][1] = copy.deepcopy(oracle["generations"][0])
                elif mode in ("image", "birth", "exit"):
                    field = {"image": "image", "birth": "creationFileTime", "exit": "exitFileTime"}[mode]
                    oracle["generations"][0][field] = str(self.root / "other.exe") if mode == "image" else oracle["generations"][0][field] + 1
                elif mode == "accounting":
                    changed["generationAccounting"]["totalProcesses"] = 33
                elif mode == "unclosed":
                    changed["members"]["1"][0]["handleClosed"] = False
                elif mode == "no-short-lifetime":
                    for row in oracle["generations"]:
                        row["exitFileTime"] = row["creationFileTime"] + 100 * 10000
                        changed["members"][str(row["pid"])][0]["exitFileTime"] = row["exitFileTime"]
                elif mode == "missing-acquisition":
                    changed.pop("processAcquisition")
                elif mode in ("pending-not-drained", "metadata-inflight", "unclosed-acquisition", "handle-bound"):
                    changed["processAcquisition"][{"pending-not-drained": "pending", "metadata-inflight": "metadataInFlight",
                                                   "unclosed-acquisition": "physicalHandles", "handle-bound": "maximumPhysicalHandles"}[mode]] = 257 if mode == "handle-bound" else 1
                elif mode in ("metadata-failure", "image-failure", "missing-metadata", "duplicate-delivery"):
                    changed[{"metadata-failure": "notificationMetadataFailures", "image-failure": "notificationImageFailures",
                             "missing-metadata": "notificationMetadataSuccesses", "duplicate-delivery": "newProcessNotifications"}[mode]] += 1
                elif mode == "byte-bound":
                    changed["processAcquisition"]["unpublishedByteBound"] = 1024
                elif mode == "wrong-job":
                    changed["members"]["1"][0]["inOwnedJob"] = False
                elif mode == "wrong-affinity":
                    changed["members"]["1"][0]["affinityMask"] = 3
                with self.assertRaises(ValueError):
                    boundary.validate_immediate_history(changed, oracle)

        held = []
        for index in range(64):
            pid = 1000 + index
            birth = 116444736000000000 + index * 1000000
            member = {"pid": pid, "creationFileTime": birth, "exitFileTime": birth + 10000000,
                      "image": str(self.root / "cmd.exe"), "inOwnedJob": True, "completed": True,
                      "handleClosed": True, "exitCode": 0, "affinityMask": 15, "systemAffinityMask": 15}
            terminal["members"][str(pid)] = [member]
            held.append(copy.deepcopy(member))
        terminal["totalProcesses"] = 96
        terminal["maximumRetainedHandles"] = 64
        terminal["generationAccounting"].update(totalProcesses=96, capturedGenerations=96, closedGenerations=96)
        byte_bound = sum(1024 + 6 * len(member[0]["image"].encode("utf-16-le")) // 2 for member in terminal["members"].values())
        terminal["processAcquisition"].update(maximumPhysicalHandles=96, publishedByteBound=byte_bound)
        terminal.update(memberHistorySerializedByteBound=byte_bound, newProcessNotifications=96,
                        notificationOpenSuccesses=96, notificationImageSuccesses=96, notificationMetadataSuccesses=96)
        actual["held_generations"] = held
        receipt = boundary.validate_immediate_history(terminal, actual, 64)
        self.assertEqual((receipt["independentGenerations"], receipt["heldMetadataGenerations"], receipt["totalIndependentGenerations"]), (32, 64, 96))
        for mode in ("missing-held", "duplicate-held", "aliased-short", "held-image", "held-birth", "held-exit", "insufficient-live-inventory"):
            changed, oracle = copy.deepcopy(terminal), copy.deepcopy(actual)
            if mode == "missing-held":
                oracle["held_generations"].pop()
            elif mode == "duplicate-held":
                oracle["held_generations"][1] = copy.deepcopy(oracle["held_generations"][0])
            elif mode == "aliased-short":
                oracle["held_generations"][0] = copy.deepcopy(oracle["generations"][0])
            elif mode == "insufficient-live-inventory":
                changed["maximumRetainedHandles"] = 63
            else:
                field = {"held-image": "image", "held-birth": "creationFileTime", "held-exit": "exitFileTime"}[mode]
                oracle["held_generations"][0][field] = str(self.root / "other.exe") if field == "image" else oracle["held_generations"][0][field] + 1
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                boundary.validate_immediate_history(changed, oracle, 64)

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

    def test_linked_js_archive_uses_actual_klib_path_and_retains_both_compiled_pins(self):
        value = copy.deepcopy(self.manifest)
        previous = value["collector_path"]
        actual = "performance-testkit/build/libs/performance-testkit-js-0.2.2.klib"
        value["collector_path"] = actual
        for pins in value["compiled_pins"].values():
            for pin in pins:
                if pin["path"] == previous:
                    pin["path"] = actual
        self.assertEqual(self.load(value), value)
        for suffix in (".zip", ".js", ".exe", ".klib.bak"):
            changed = copy.deepcopy(value)
            changed["collector_path"] = actual.removesuffix(".klib") + suffix
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                self.load(changed)
        missing = copy.deepcopy(value)
        missing["compiled_pins"]["baseline"][0]["path"] = previous
        with self.assertRaisesRegex(ValueError, "actual linked collector"):
            self.load(missing)

    def supplemental(self):
        result = copy.deepcopy(self.manifest["collections"][0])
        result.update(name="supplemental", scenarios=[{"id": f"independent-{index}-{theme}", "phases": ["Update"]} for index in range(12) for theme in ("native", "minecraft")], expected_cases=72)
        return result

    def source_manifest(self, repetitions=3):
        return {"schema": "strata-source-preparation-ci-v1", "sources": self.manifest["sources"], "source_files": ["settings.gradle.kts"],
                "inputs": self.manifest["inputs"], "tasks": [":runtime:web:" + task for task in ("compileKotlinJs", "compileTestKotlinJs", "checkKotlinAbi", "lintKotlin", "detekt")]
                + [":integration:web:" + task for task in ("compileKotlinJs", "lintKotlin", "detekt")],
                "arguments": {"baseline": [], "candidate": ["-Pstrata.webOnly=true"]}, "repetitions": repetitions,
                "artifacts": [{"glob": item["path"], "minimum": 1} for item in self.manifest["compiled_pins"]["candidate"]], "test_gate": None}

    def test_source_and_browser_inputs_share_logical_path_admission_and_preserved_bytes(self):
        for source in (False, True):
            value = copy.deepcopy(self.source_manifest() if source else self.manifest)
            value["inputs"][0]["layout"] = {"root": "shared", "path": "objects/aa/object"}
            self.assertEqual(self.load(value), value)
            owner = self.root / ("source-inputs" if source else "browser-inputs")
            result = self.shared.preserve_inputs(self.root, owner, value["inputs"])
            self.assertEqual(Path(result["fixture"]["path"]), owner / "shared/objects/aa/object")
            self.assertEqual(result["fixture"]["sha256"], self.fixture.pin["sha256"])
            for layout in ({"root": "shared", "path": "../escape"}, {"root": "shared", "path": "CONOUT$"}):
                invalid = copy.deepcopy(value)
                invalid["inputs"][0]["layout"] = layout
                with self.assertRaises(ValueError):
                    self.load(invalid)
            invalid = copy.deepcopy(value)
            invalid["inputs"].append({**copy.deepcopy(invalid["inputs"][0]), "label": "alias"})
            with self.assertRaisesRegex(ValueError, "Duplicate logical input destination"):
                self.load(invalid)

    def test_source_manifest_retains_full_tasks_order_and_existing_model_argument(self):
        value = self.source_manifest()
        self.assertEqual(self.load(value), value)
        self.assertEqual((len(value["tasks"]), value["repetitions"] * 2), (8, 6))
        value["tasks"] = [":integration:web:jsBrowserDistribution", ":integration:web:jvmTest", ":integration:web:installWebBrowsers", ":performance-testkit:jsJar", ":performance-testkit:verifyBrowserPerformanceDriver"]
        value["test_gate"] = {"task": ":integration:web:jvmTest", "required_tests": self.manifest["correctness"]["required_tests"]}
        value["repetitions"] = 1
        self.assertEqual(self.load(value), value)
        for mutation in (
            lambda item: item.update(repetitions=2),
            lambda item: item.update(repetitions=True),
            lambda item: item.update(compiled_pins={}),
            lambda item: item.update(tasks=[":runtime:web:clean"]),
            lambda item: item["tasks"].append(item["tasks"][0]),
            lambda item: item["arguments"].update(candidate=["--max-workers=8"]),
            lambda item: item["arguments"].update(candidate=["-Pstrata.webOnly=false"]),
            lambda item: item["source_files"].append("README.md"),
            lambda item: item["artifacts"][0].update(glob="../build/private.jar"),
            lambda item: item["artifacts"][0].update(minimum=0),
            lambda item: item.update(test_gate=None),
        ):
            invalid = copy.deepcopy(value)
            mutation(invalid)
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.load(invalid)

    def test_source_runtime_archives_and_existing_quality_tasks_keep_full_jvm_gate(self):
        value = self.source_manifest(1)
        value["source_files"] = ["quality/component-benchmarks/src/jmh/resources/runtime-api.tsv", "api/api/api.api", "api/api/api.klib.api"]
        value["tasks"] = [":api:jvmJar", ":runtime:core:jvmJar", ":runtime:headless:jar", ":performance-testkit:jvmJar"]
        value["tasks"] += [":quality:component-benchmarks:" + task for task in ("jmhClasses", "jmhCompileGeneratedClasses", "lintKotlinJmh", "detektJmh", "test", "verifyComponentRenderingWork")]
        value["tasks"] += [":quality:benchmarks:verifyHistoricalWorkloads", ":quality:remote-benchmarks:verifyRemoteWorkloads"]
        value["arguments"] = {side: ["-Pstrata.jvmOnly=true"] for side in ("baseline", "candidate")}
        value["test_gate"] = {"task": ":quality:component-benchmarks:test", "required_tests": ["dev.example.ParityTest#fullTransitions"]}
        self.assertEqual(self.load(value), value)
        for mutation in (
            lambda item: item.update(test_gate=None),
            lambda item: item["tasks"].append(":runtime:core:jvmTest"),
            lambda item: item["test_gate"].update(task=":runtime:core:jvmTest"),
            lambda item: item["arguments"].update(candidate=["-Pstrata.jvmOnly=false"]),
            lambda item: item["source_files"].append("api/build/kotlin/abi/api.api"),
            lambda item: item["tasks"].append(":quality:component-benchmarks:jmhComponents"),
        ):
            invalid = copy.deepcopy(value)
            mutation(invalid)
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.load(invalid)

    def test_source_versioned_project_paths_keep_task_and_full_test_gate_guards(self):
        value = self.source_manifest(1)
        project = ":runtime:minecraft-fabric-26.3"
        value["tasks"] = [project + ":" + task for task in ("jar", "checkKotlinAbi", "lintKotlin", "detekt", "test")]
        value["test_gate"] = {"task": project + ":test", "required_tests": ["dev.example.ParityTest#fullTransitions"]}
        self.assertEqual(self.load(value), value)
        directory = self.root / "versioned-task-evidence"
        directory.mkdir()
        console = "".join("> Task " + task + "\n" for task in value["tasks"])
        (directory / "console.log").write_text(console, encoding="utf-8")
        self.assertEqual(self.browser.verify_source_tasks(self.shared, directory, value["tasks"]),
                         dict.fromkeys(value["tasks"], "executed"))
        for suffix in (" UP-TO-DATE", " FROM-CACHE", " SKIPPED", " NO-SOURCE", " FAILED"):
            (directory / "console.log").write_text(console.replace(value["tasks"][0] + "\n", value["tasks"][0] + suffix + "\n"), encoding="utf-8")
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, "Requested Source task"):
                self.browser.verify_source_tasks(self.shared, directory, value["tasks"])
        for task in (":runtime:minecraft-fabric-26..3:jar", ":runtime:..:jar", ":runtime:.26:jar",
                     ":runtime:minecraft-fabric-26.:jar", "runtime:minecraft-fabric-26.3:jar", project + ":clean", project + ":jmhHistorical",
                     project + ":jar --max-workers=8", project + ":../jar", project + ":jar;help"):
            invalid = copy.deepcopy(value)
            invalid["tasks"][0] = task
            with self.subTest(task=task), self.assertRaisesRegex(ValueError, "existing Source preparation tasks"):
                self.load(invalid)
        for mutation in (lambda item: item.update(test_gate=None), lambda item: item["tasks"].append(project + ":test"),
                         lambda item: item["test_gate"].update(task=":runtime:minecraft-fabric-26.2:test")):
            invalid = copy.deepcopy(value)
            mutation(invalid)
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.load(invalid)

    def test_existing_remote_inventory_capture_preserves_build_output_and_source_fences(self):
        value = self.source_manifest(1)
        task = ":quality:remote-benchmarks:captureRemoteInventory"
        output = "quality/remote-benchmarks/build/performance/remote-api.tsv"
        value.update(tasks=[task], artifacts=[{"glob": output, "minimum": 1}], test_gate=None)
        self.assertEqual(self.load(value), value)
        for invalid_task in (":quality:remote-benchmarks:captureUnknownInventory", ":quality:remote-benchmarks:jmhRemote"):
            with self.subTest(task=invalid_task), self.assertRaisesRegex(ValueError, "existing Source preparation tasks"):
                self.load({**value, "tasks": [invalid_task]})
        workspace, directory = self.root / "capture-workspace", self.root / "capture-evidence"
        artifact = workspace / output
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b"independent actual-inventory placeholder for file-preservation control\n")
        directory.mkdir()
        pins = self.browser.preserve_source_outputs(self.shared, workspace, directory, value["artifacts"])
        self.assertEqual(pins, [{"path": output, "sha256": self.shared.digest(artifact)}])
        self.assertEqual((directory / "artifacts" / output).read_bytes(), artifact.read_bytes())
        for state in ("", " SKIPPED", " FROM-CACHE", " FAILED"):
            (directory / "console.log").write_text("> Task " + task + state + "\n", encoding="utf-8")
            if state:
                with self.subTest(state=state), self.assertRaisesRegex(ValueError, "Requested Source task"):
                    self.browser.verify_source_tasks(self.shared, directory, [task])
            else:
                self.assertEqual(self.browser.verify_source_tasks(self.shared, directory, [task]), {task: "executed"})
        tracked = "quality/remote-benchmarks/src/jmh/resources/remote-api.tsv"
        self.fixture.selector_repository()
        checkout = self.root
        (checkout / ".git/info/attributes").write_bytes(b"* -text\n")
        path = checkout / tracked
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("original registry\n", encoding="utf-8")
        self.fixture.selector_git("add", tracked)
        self.fixture.selector_git("commit", "-m", "Record registry control")
        tree = self.fixture.selector_git("rev-parse", "HEAD^{tree}")
        self.shared.source_guard(checkout, tree)
        path.write_text("changed by capture\n", encoding="utf-8")
        with self.assertRaises(subprocess.CalledProcessError):
            self.shared.source_guard(checkout, tree)

    def test_source_dependency_provisioning_is_explicit_bounded_and_keeps_original_repetitions(self):
        value = self.source_manifest(3)
        for enabled in (False, True):
            value["provision_dependency_caches"] = enabled
            self.assertEqual(self.load(value), value)
            self.assertEqual((len(value["tasks"]), value["repetitions"]), (8, 3))
        for invalid in (None, 0, 1, "true", [], {}):
            value["provision_dependency_caches"] = invalid
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.load(value)

    def controlled_source_manifest(self):
        value = self.source_manifest()
        value["arguments"] = {side: ["-Pstrata.jvmOnly=true"] for side in ("baseline", "candidate")}
        api = [":integration:api:test", ":integration:api:checkApiOnlyClasspath"]
        fixtures = [":quality:benchmarks:jmhClasses", ":quality:benchmarks:jmhRunBytecodeGenerator",
                    ":quality:benchmarks:jmhCompileGeneratedClasses", ":quality:benchmarks:verifyHistoricalWorkloads",
                    ":quality:component-benchmarks:jmhClasses", ":quality:component-benchmarks:jmhRunBytecodeGenerator",
                    ":quality:component-benchmarks:jmhCompileGeneratedClasses", ":quality:component-benchmarks:verifyComponentRenderingWork",
                    ":quality:remote-benchmarks:jmhClasses", ":quality:remote-benchmarks:jmhRunBytecodeGenerator",
                    ":quality:remote-benchmarks:jmhCompileGeneratedClasses", ":quality:benchmarks:verifyReactiveRenderingWork",
                    ":quality:benchmarks:verifyOverlayRenderingWork", ":quality:remote-benchmarks:verifyRemoteWorkloads",
                    ":quality:remote-benchmarks:verifyRemoteSessionWork"]
        value["tasks"] = api + fixtures
        value["artifacts"] = [{"glob": "integration/api/build/test-results/test/TEST-complete.xml", "minimum": 1}]
        value["artifacts"] += [{"glob": project + "/build/classes/java/jmh/generated.class", "minimum": 1}
                               for project in ("quality/benchmarks", "quality/component-benchmarks", "quality/remote-benchmarks")]
        value["test_gate"] = {"task": api[0], "required_tests": ["fixture.CompleteApiTest#whole consumer"]}
        value["preparations"] = [{"name": "api", "tasks": api, "artifacts": value["artifacts"][:1], "test_gate": value["test_gate"]},
                                 {"name": "fixtures", "tasks": fixtures, "artifacts": value["artifacts"][1:], "test_gate": None}]
        value["toolchain_binding"] = {}
        for name in ("profile", "installations"):
            path = self.root / ("controlled-" + name + ".json")
            path.write_text(json.dumps({"synthetic": name}), encoding="utf-8")
            value["toolchain_binding"][name] = {"path": path.name, "sha256": self.shared.digest(path)}
        return value


    def test_source_resource_profile_is_pinned_finite_and_cannot_weaken_admission(self):
        value = self.source_manifest()
        defaults = {"minimumFreePhysicalBytes": 4 * 1024**3, "daemonMetaspaceMiB": 1024}
        self.assertEqual(self.browser.source_resources(self.shared, self.root, value), defaults)
        path = self.root / "resource-profile.json"
        limits = {"minimumFreePhysicalBytes": 4608 * 1024**2, "daemonMetaspaceMiB": 384}
        for mutation in (lambda item: None, lambda item: item.update(minimumFreePhysicalBytes=4 * 1024**3 - 1),
                         lambda item: item.update(minimumFreePhysicalBytes=16 * 1024**3 + 1),
                         lambda item: item.update(minimumFreePhysicalBytes=True), lambda item: item.update(daemonMetaspaceMiB=True),
                         lambda item: item.update(daemonMetaspaceMiB=0), lambda item: item.update(daemonMetaspaceMiB=1025),
                         lambda item: item.update(jobCommittedBytes=4 * 1024**3)):
            actual = dict(limits)
            mutation(actual)
            path.write_text(json.dumps(actual), encoding="utf-8")
            value["resource_profile"] = {"path": path.name, "sha256": self.shared.digest(path)}
            with self.subTest(actual=actual):
                if actual == limits:
                    self.assertEqual(self.load(value), value)
                    self.assertEqual(self.browser.source_resources(self.shared, self.root, value), limits)
                    path.write_text(json.dumps(defaults), encoding="utf-8")
                    with self.assertRaises(ValueError):
                        self.load(value)
                else:
                    with self.assertRaises(ValueError):
                        self.load(value)


    def test_toolchain_input_acquisition_is_separate_from_frozen_binding_and_original_work(self):
        value = self.controlled_source_manifest()
        value.pop("toolchain_binding")
        value.update(capture_toolchain_inputs=True, repetitions=1)
        self.assertEqual(self.load(value), value)
        self.assertEqual([len(item["tasks"]) for item in value["preparations"]], [2, 15])
        for mutation in (lambda item: item.update(capture_toolchain_inputs="true"),
                         lambda item: item.update(repetitions=3), lambda item: item.update(provision_dependency_caches=True),
                         lambda item: item.update(toolchain_binding={}), lambda item: item.pop("preparations"),
                         lambda item: item["arguments"].update(baseline=["-Pstrata.webOnly=true"], candidate=["-Pstrata.webOnly=true"])):
            invalid = copy.deepcopy(value)
            mutation(invalid)
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.load(invalid)


    def test_acquisition_refuses_inherited_toolchain_overrides_before_execution_or_export(self):
        data = self.controlled_source_manifest()
        data.pop("toolchain_binding")
        data.update(capture_toolchain_inputs=True, repetitions=1)
        selection = {"root": self.root, "path": self.root / "manifest.json", "execution_head": "a" * 40}
        for index, suffix in enumerate(("installations.auto-detect", "installations.auto-download", "installations.paths", "installations.fromEnv", "home", "INSTALLATIONS.AUTO-DETECT")):
            with self.subTest(suffix=suffix):
                key = "ORG_GRADLE_PROJECT_org.gradle.java." + suffix
                environment = {key.upper() if suffix.isupper() else key: "false"}
                tools = Mock()
                directory = self.root / ("refused-export-" + str(index))
                with self.assertRaisesRegex(ValueError, "Unreviewed environment installation properties"):
                    self.browser.capture_source_toolchain_inputs(self.shared, tools, self.root, directory, environment, ["help"])
                self.assertEqual(tools.mock_calls, [])
                self.assertFalse(directory.exists())
                api = SimpleNamespace(**vars(self.shared))
                api.git, api.load_manifest, api.invoke, api.materialize = Mock(return_value="a" * 40), Mock(return_value=data), Mock(), Mock()
                output = self.root / ("refused-acquisition-" + str(index))
                with patch.dict(os.environ, environment), patch.object(self.browser, "os", SimpleNamespace(name="nt", path=os.path, environ=os.environ)), patch.object(self.browser.subprocess, "check_output") as process, self.assertRaisesRegex(ValueError, "Unreviewed environment installation properties"):
                    self.browser.run(api, self.root, selection, output)
                api.invoke.assert_not_called()
                api.materialize.assert_not_called()
                process.assert_not_called()
                self.assertEqual(self.shared.read_json(output / "attempt.json")["phases"], [])
                self.assertFalse(any(output.rglob("pins.json")))

    def test_acquired_toolchain_inputs_require_actual_selected_and_unused_vm_metadata(self):
        for mode in ("complete", "missing-runtime", "wrong-vendor", "wrong-vm", "unobserved-home", "selected-byte-drift"):
            with self.subTest(mode=mode):
                workspace = self.root / ("acquire-" + mode)
                directory = workspace / "phases/metadata"
                directory.mkdir(parents=True)
                vms = workspace / "vms"
                vms.mkdir()
                homes, selected, records, entries = {}, [], [], {}
                for major in (17, 21, 25):
                    home = workspace / ("jdk-" + str(major))
                    home.joinpath("bin").mkdir(parents=True)
                    home.joinpath("bin/java.exe").write_bytes(b"synthetic Java; never executed")
                    home.joinpath("release").write_text(str(major), encoding="utf-8")
                    homes[major] = home
                    entry = {"home": home.as_posix(), "major": major, "tree_sha256": "a" * 64}
                    entries[str(home)] = entry
                    if major != 21:
                        selected.append({"home": str(home), "major": major, "vendor": "actual Gradle vendor", "runtime_version": str(major) + ".0.1", "jvm_version": str(major) + ".0.1"})
                    xml = vms / (str(major) + ".log")
                    vendor = "different" if mode == "wrong-vendor" and major == 21 else "actual VM vendor"
                    vm_version = "different" if mode == "wrong-vm" and major == 21 else str(major) + ".0.1"
                    xml.write_text(f"<hotspot_log><vm_version><release>{vm_version}</release></vm_version><vm_arguments><properties>java.vm.vendor={vendor}\njava.vm.version={vm_version}\n</properties></vm_arguments><hotspot_log_done/></hotspot_log>", encoding="utf-8")
                    if not (mode == "unobserved-home" and major == 21):
                        records.append({"home": str(home), "diagnostic_sha256": self.shared.digest(xml)})
                metadata = {"schema": 1, "selected": selected, "jvm_only": True, "gradle_version": "9.8.0", "gradle_user_home": str(workspace / "user"),
                            "gradle_installation_home": str(workspace / "gradle"),
                            "daemon": {"home": str(homes[25]), "major": 25, "vm_vendor": "actual VM vendor", "runtime_version": "25.0.1", "jvm_version": "25.0.1"}}
                self.shared.write_new(directory / "actual-toolchain-metadata.json", metadata)
                self.shared.write_new(directory / "vms.json", records)
                console = []
                for major in (17, 21):
                    if mode == "missing-runtime" and major == 21:
                        continue
                    console.append(f"Received JVM installation metadata from '{homes[major]}': {{RUNTIME_VERSION={major}.0.1, VM_VERSION={major}.0.1, VM_VENDOR=actual VM vendor}}")
                (directory / "console.log").write_text("\n".join(console), encoding="utf-8")
                validator = Mock()
                models = SimpleNamespace(JVM=SimpleNamespace(value="jvmOnly"), COMPLETE=SimpleNamespace(value="complete"))
                tools = SimpleNamespace(Model=models, required_versions=lambda *args: {17, 25},
                                        installation=lambda home: entries[home], installation_tree_digest=lambda home: ("b" if mode == "selected-byte-drift" else "a") * 64,
                                        absolute_home=lambda home: Path(home).resolve(), properties=lambda *args: {}, load_profile=validator)
                if mode == "complete":
                    pins = self.browser.capture_source_toolchain_inputs(self.shared, tools, workspace, directory, {}, ["help", "-Pstrata.jvmOnly=true"])
                    profile = self.shared.read_json(directory / "captured-inputs/profile.json")
                    inventory = self.shared.read_json(directory / "captured-inputs/installations.json")
                    self.assertEqual([item["major"] for item in profile["jdks"]], [17, 25])
                    self.assertEqual(len(inventory["installations"]), 3)
                    self.assertTrue(all(set(item) == {"home", "tree_sha256", "java_sha256", "release_sha256", "vm_vendor", "runtime_version", "jvm_version"} for item in inventory["installations"]))
                    self.assertEqual(set(pins), {"profile", "installations"})
                    validator.assert_called_once()
                else:
                    with self.assertRaises(ValueError):
                        self.browser.capture_source_toolchain_inputs(self.shared, tools, workspace, directory, {}, ["help", "-Pstrata.jvmOnly=true"])
                    self.assertFalse((directory / "captured-inputs/pins.json").exists())

    def test_controlled_source_preserves_native_requests_complete_preparations_and_pinned_inputs(self):
        value = self.controlled_source_manifest()
        self.assertEqual(self.load(value), value)
        for mutation in (lambda item: item.pop("toolchain_binding"), lambda item: item.pop("preparations"),
                         lambda item: item["preparations"][1].update(name="api"),
                         lambda item: item["preparations"][1].update(tasks=[]),
                         lambda item: item["preparations"][0].update(artifacts=[]),
                         lambda item: item["preparations"][0].update(test_gate=None),
                         lambda item: item["arguments"].update(baseline=[]),
                         lambda item: item["toolchain_binding"]["profile"].update(sha256="0" * 64)):
            invalid = copy.deepcopy(value)
            mutation(invalid)
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.load(invalid)

    def test_shared_source_launcher_keeps_default_discovery_and_refuses_prebounded_arguments(self):
        workspace, directory = self.root / "source", self.root / "evidence"
        native = [":integration:api:test", ":integration:api:checkApiOnlyClasspath", "--max-workers=1"]
        for side, bounded in (("baseline", False), ("candidate", True)):
            def bind(profile, root, environment, arguments, evidence, *, bounded):
                manifest = evidence / "binding.json"
                return arguments + (["-Dorg.gradle.java.installations.auto-detect=false"] if bounded else []), dict(environment, STRATA_TOOLCHAIN_BINDING=str(manifest)), manifest

            adapter = Mock(side_effect=bind)
            binding = {"tools": SimpleNamespace(bind=adapter), "profile_path": self.root / "profile.json"}
            result, environment, manifest, effective = self.browser.bind_source_request(self.shared, binding, workspace, side, native, {"JAVA_HOME": "frozen-home"}, directory)
            self.assertEqual(adapter.call_args.kwargs["bounded"], bounded)
            self.assertEqual(result[:len(native)], native)
            self.assertEqual("-Dorg.gradle.java.installations.auto-detect=false" in result, bounded)
            self.assertEqual(environment["STRATA_TOOLCHAIN_BINDING"], str(manifest))
            self.assertEqual(effective["JAVA_HOME"], "frozen-home")
            adapter.reset_mock()
            for arguments, inherited in ((native + ["-Dorg.gradle.java.installations.auto-detect=false"], {}),
                                          (native, {"ORG_GRADLE_PROJECT_org.gradle.java.installations.auto-detect": "false"})):
                with self.subTest(side=side, arguments=arguments), self.assertRaisesRegex(ValueError, "already overrides"):
                    self.browser.bind_source_request(self.shared, binding, workspace, side, arguments, inherited, directory)
                adapter.assert_not_called()

    def test_source_installation_pins_keep_unused_legacy_homes_and_refuse_byte_drift(self):
        home = self.root / "legacy-jre"
        home.joinpath("bin").mkdir(parents=True)
        executable = home / "bin/java.exe"
        executable.write_bytes(b"synthetic-unused-JRE")
        tools = SimpleNamespace(absolute_home=lambda value: Path(value).resolve(), installation_tree_digest=lambda value: self.shared.digest(Path(value) / "bin/java.exe"))
        item = {"home": str(home), "tree_sha256": self.shared.digest(executable), "java_sha256": self.shared.digest(executable), "release_sha256": None}
        binding = {"tools": tools, "installations": [item]}
        self.assertEqual(self.browser.verify_source_installations(self.shared, binding), {os.path.normcase(str(executable)): self.shared.digest(executable)})
        executable.write_bytes(b"changed-unused-JRE")
        with self.assertRaisesRegex(ValueError, "installation bytes changed"):
            self.browser.verify_source_installations(self.shared, binding)

    def test_source_physical_vm_metadata_requires_actual_vendor_version_and_fresh_runtime_authority(self):
        directory = self.root / "phases/owned"
        directory.mkdir(parents=True)
        diagnostics = self.root / "vms"
        diagnostics.mkdir()
        path = diagnostics / "physical.log"
        home = self.root / "java-home"
        (directory / "console.log").write_text("", encoding="utf-8")
        (directory / "terminal.json").write_text(json.dumps({"members": {"11": [{"creationFileTime": 100, "exitFileTime": 150, "completed": True, "handleClosed": True}]}}))
        xml = "<hotspot_log><vm_version><release>17.0.1+4</release></vm_version><vm_arguments><properties>java.vm.vendor=Fixture Vendor\njava.vm.version=17.0.1+4\n</properties></vm_arguments><hotspot_log_done/></hotspot_log>"
        binding = {"installations": [{"home": str(home), "vm_vendor": "Fixture Vendor", "jvm_version": "17.0.1+4", "runtime_version": "17.0.1+4"}]}
        selections = {"builds": [{"daemon": {"home": str(home), "jvm_version": "17.0.1+4", "runtime_version": "17.0.1+4"}}]}
        def write_vm(text):
            path.write_text(text, encoding="utf-8")
            (directory / "vms.json").write_text(json.dumps([{"pid": 31, "home": str(home), "role": "worker", "diagnostic_sha256": self.shared.digest(path), "os_generation": {"parentPid": 11, "creationFileTime": 110}}]))
        write_vm(xml)
        self.browser.verify_source_vm_metadata(self.shared, directory, binding, selections)
        self.assertEqual(self.shared.read_json(directory / "vm-metadata-verified.json")[0]["runtime_metadata"]["authority"], "verified_actual_daemon_receipt")
        (directory / "vm-metadata-verified.json").unlink()
        with self.assertRaisesRegex(ValueError, "runtime metadata is unavailable"):
            self.browser.verify_source_vm_metadata(self.shared, directory, binding, {"builds": []})
        write_vm(xml.replace("Fixture Vendor", "Another Vendor"))
        with self.assertRaisesRegex(ValueError, "identity changed"):
            self.browser.verify_source_vm_metadata(self.shared, directory, binding, selections)

    def test_source_outputs_and_requested_tasks_require_actual_complete_bytes_and_execution(self):
        workspace = self.root / "source"
        directory = self.root / "source-evidence"
        workspace.joinpath("runtime/web/build/libs").mkdir(parents=True)
        directory.mkdir()
        artifact = workspace / "runtime/web/build/libs/actual.jar"
        artifact.write_bytes(b"actual synthetic compiled bytes")
        patterns = [{"glob": "runtime/web/build/libs/*.jar", "minimum": 1}]
        pins = self.browser.preserve_source_outputs(self.shared, workspace, directory, patterns)
        self.assertEqual(pins, [{"path": artifact.relative_to(workspace).as_posix(), "sha256": self.shared.digest(artifact)}])
        self.assertEqual((directory / "artifacts" / pins[0]["path"]).read_bytes(), artifact.read_bytes())
        with self.assertRaisesRegex(ValueError, "Incomplete actual Source"):
            self.browser.preserve_source_outputs(self.shared, workspace, directory, [{"glob": "runtime/web/build/libs/missing.jar", "minimum": 1}])
        tasks = self.source_manifest()["tasks"]
        for suffix in (" UP-TO-DATE", " FROM-CACHE", " NO-SOURCE", " SKIPPED", " FAILED"):
            (directory / "console.log").write_text("\n".join("> Task " + task + (suffix if index == 0 else "") for index, task in enumerate(tasks)) + "\n", encoding="utf-8")
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, "Requested Source task"):
                self.browser.verify_source_tasks(self.shared, directory, tasks)
        (directory / "console.log").write_text("\n".join("> Task " + task for task in tasks) + "\n", encoding="utf-8")
        self.assertEqual(self.browser.verify_source_tasks(self.shared, directory, tasks), dict.fromkeys(tasks, "executed"))

    def test_source_preparation_can_freeze_an_independent_subject_without_relaxing_consumer_identity(self):
        value = self.source_manifest()
        _, producer, head, path, expected = self.fixture.input_consumer(value=value, candidate_patch=True, mismatch="blob")
        with self.shared.selected_inputs(self.root, path, data_ref=producer, expected_head=head) as selection:
            self.assertEqual(self.shared.load_manifest(selection["root"], selection["path"]), expected)
            self.assertNotEqual(expected["sources"]["candidate"]["tree"], self.fixture.selector_git("rev-parse", head + "^{tree}"))
        with self.assertRaisesRegex(ValueError, "expected head"):
            with self.shared.selected_inputs(self.root, path, data_ref=producer, expected_head="0" * 40):
                pass

    def test_source_test_classpath_keeps_empty_output_receipts_but_rejects_missing_dependencies(self):
        workspace = self.root / "test-workspace"
        directory = self.root / "test-evidence"
        retained = directory / "test-classpath"
        workspace.joinpath("runtime/core/build/libs").mkdir(parents=True)
        retained.mkdir(parents=True)
        source = workspace / "runtime/core/build/libs/dependency.jar"
        source.write_bytes(b"synthetic actual dependency")
        copy = retained / "classpath-0.jar"
        copy.write_bytes(source.read_bytes())
        self.shared.write_new(retained / "classpath.json", {"schema": "strata-paired-jvm-classpath-v1", "entries": [{"source": str(source), "path": str(copy), "sha256": self.shared.path_identity(source)}]})
        empty = workspace / "integration/web/build/classes/java/jvmMain"
        absent = {"source": str(empty), "task": ":integration:minecraft-fabric-26.3:compileJvmMainJava", "reason": "NO-SOURCE"}
        original = {"sources": [str(empty), str(source)], "absent_outputs": [absent]}
        (retained / "original-classpath.json").write_text(json.dumps(original), encoding="utf-8")
        (directory / "console.log").write_text("> Task " + absent["task"] + " NO-SOURCE\n", encoding="utf-8")
        self.browser.verify_source_test_classpath(self.shared, workspace, directory)
        self.assertEqual(self.shared.read_json(directory / "test-classpath-verified.json"), {"present_entries": 1, "absent_outputs": [absent]})
        for change in ({"source": str(workspace / "runtime/core/build/libs/missing.jar")}, {"source": str(self.root / "outside/build/classes")}, {"reason": "SKIPPED"}, {"task": ":integration:web:unexecuted"}):
            altered = {**absent, **change}
            (retained / "original-classpath.json").write_text(json.dumps({"sources": [altered["source"], str(source)], "absent_outputs": [altered]}), encoding="utf-8")
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.browser.verify_source_test_classpath(self.shared, workspace, directory)
        for task in (":integration:26..3:compileJava", ":integration:..:compileJava", ":integration:.26:compileJava",
                     ":integration:26.:compileJava", ":integration::compileJava", ":integration:26.3:compileJava --parallel"):
            altered = {**absent, "task": task}
            (retained / "original-classpath.json").write_text(json.dumps({"sources": [str(empty), str(source)], "absent_outputs": [altered]}), encoding="utf-8")
            (directory / "console.log").write_text("> Task " + task + " NO-SOURCE\n", encoding="utf-8")
            with self.subTest(task=task), self.assertRaisesRegex(ValueError, "NO-SOURCE task evidence"):
                self.browser.verify_source_test_classpath(self.shared, workspace, directory)
        root_output = {**absent, "task": ":compileJava"}
        (retained / "original-classpath.json").write_text(json.dumps({"sources": [str(empty), str(source)], "absent_outputs": [root_output]}), encoding="utf-8")
        (directory / "console.log").write_text("> Task :compileJava NO-SOURCE\n", encoding="utf-8")
        self.assertEqual(self.browser.verify_source_classpath(self.shared, workspace, directory, retained)[1]["absent_outputs"], [root_output])
        (retained / "original-classpath.json").write_text(json.dumps(original), encoding="utf-8")
        source.write_bytes(b"changed resolved dependency")
        with self.assertRaisesRegex(ValueError, "Prepared classpath bytes changed"):
            self.browser.verify_source_test_classpath(self.shared, workspace, directory)

    def source_javaexec_classpath(self):
        workspace = self.root / "source-workspace"
        directory = self.root / "source-evidence"
        retained = directory / "source-classpaths/root/fixture-capture"
        retained.mkdir(parents=True)
        sources, entries = [], []
        for index in range(2):
            source = workspace / f"fixture/build/libs/library-{index}.jar"
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(f"actual control bytes {index}".encode())
            copied = retained / f"classpath-{index}.jar"
            copied.write_bytes(source.read_bytes())
            sources.append(str(source))
            entries.append({"source": str(source), "path": str(copied), "sha256": self.shared.digest(source)})
        self.shared.write_new(retained / "classpath.json", {"schema": "strata-paired-jvm-classpath-v1", "entries": entries})
        self.shared.write_new(retained / "original-classpath.json", {"sources": sources, "absent_outputs": []})
        origin = {"task": ":fixture:capture", "main": "example.Main", "executable": str(self.root / "java.exe"), "classpath": os.pathsep.join(sources)}
        capture = {"task": origin["task"], "inventory": "root/fixture-capture/classpath.json"}
        self.source_javaexec_log(directory, [origin], [capture])
        return workspace, directory, retained, origin, capture

    def source_javaexec_log(self, directory, origins, captures, prefix=""):
        lines = ["paired.jvm.sourceClasspath " + json.dumps(row) for row in captures]
        lines += ["paired.jvm.origin " + json.dumps(row) for row in origins]
        (directory / "console.log").write_text(prefix + "\n".join(lines) + "\n", encoding="utf-8")

    def test_source_javaexec_retains_complete_ordered_control_bytes_and_rejects_omissions(self):
        workspace, directory, retained, origin, capture = self.source_javaexec_classpath()
        verified = self.browser.verify_source_classpaths(self.shared, workspace, directory)
        self.assertEqual(verified[0]["present_entries"], 2)
        self.assertEqual(verified[0]["absent_outputs"], [])
        (directory / "source-classpaths-verified.json").unlink()
        for origins, captures in (([origin], []), ([], [capture]), ([origin], [capture, capture]),
                                  ([origin], [{**capture, "task": ":other:capture"}]),
                                  ([{**origin, "classpath": os.pathsep.join(reversed(origin["classpath"].split(os.pathsep)))}], [capture]),
                                  ([origin], [{**capture, "inventory": "../outside/classpath.json"}])):
            with self.subTest(origins=origins, captures=captures):
                (directory / "source-classpaths-verified.json").unlink(missing_ok=True)
                self.source_javaexec_log(directory, origins, captures)
                with self.assertRaises(ValueError):
                    self.browser.verify_source_classpaths(self.shared, workspace, directory)

    def test_source_javaexec_rejects_changed_original_copy_and_unconsumed_inventory(self):
        workspace, directory, retained, origin, capture = self.source_javaexec_classpath()
        for changed in (Path(origin["classpath"].split(os.pathsep)[0]), retained / "classpath-0.jar"):
            original = changed.read_bytes()
            changed.write_bytes(b"different actual bytes")
            with self.subTest(changed=changed), self.assertRaisesRegex(ValueError, "Prepared classpath bytes changed"):
                self.browser.verify_source_classpaths(self.shared, workspace, directory)
            changed.write_bytes(original)
        extra = directory / "source-classpaths/root/unconsumed/classpath.json"
        extra.parent.mkdir(parents=True)
        extra.write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "actual JavaExec origin"):
            self.browser.verify_source_classpaths(self.shared, workspace, directory)

    def test_source_javaexec_preserves_empty_owned_output_receipts_without_dropping_original_entry(self):
        workspace, directory, retained, origin, capture = self.source_javaexec_classpath()
        original = self.shared.read_json(retained / "original-classpath.json")
        empty = workspace / "fixture/build/classes/java/main"
        absent = {"source": str(empty), "task": ":fixture:compileJava", "reason": "NO-SOURCE"}
        original["sources"].append(str(empty))
        original["absent_outputs"] = [absent]
        (retained / "original-classpath.json").write_text(json.dumps(original), encoding="utf-8")
        origin["classpath"] = os.pathsep.join(original["sources"])
        self.source_javaexec_log(directory, [origin], [capture], "> Task :fixture:compileJava NO-SOURCE\n")
        verified = self.browser.verify_source_classpaths(self.shared, workspace, directory)
        self.assertEqual(verified[0]["present_entries"], 2)
        self.assertEqual(verified[0]["absent_outputs"], [absent])
        (directory / "source-classpaths-verified.json").unlink()
        self.source_javaexec_log(directory, [origin], [capture])
        with self.assertRaisesRegex(ValueError, "actual NO-SOURCE"):
            self.browser.verify_source_classpaths(self.shared, workspace, directory)

    def test_source_javaexec_zero_executions_are_explicit_and_capture_count_is_bounded(self):
        workspace, directory = self.root / "workspace", self.root / "evidence"
        directory.mkdir()
        self.source_javaexec_log(directory, [], [])
        self.assertEqual(self.browser.verify_source_classpaths(self.shared, workspace, directory), [])
        (directory / "source-classpaths-verified.json").unlink()
        self.source_javaexec_log(directory, [{}] * 33, [{}] * 33)
        with self.assertRaisesRegex(ValueError, "missing or oversized"):
            self.browser.verify_source_classpaths(self.shared, workspace, directory)

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

    def test_browser_driver_schema_preserves_default_and_rejects_nonexact_bindings(self):
        self.assertNotIn("driver_source", self.load(self.manifest))
        value = copy.deepcopy(self.manifest)
        value["driver_source"] = {"ref": "a" * 40, "tree": "b" * 40}
        self.assertEqual(self.load(value), value)
        for driver in (None, [], {}, {"ref": "a" * 40}, {"ref": "a" * 40, "tree": "b" * 40, "patches": []},
                       {"ref": "master", "tree": "b" * 40}, {"ref": "a" * 40, "tree": "B" * 40}, {"ref": "a" * 40, "tree": None}):
            changed = copy.deepcopy(value)
            changed["driver_source"] = driver
            with self.subTest(driver=driver), self.assertRaisesRegex(ValueError, "driver commit and tree"):
                self.load(changed)

    @patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch"})
    def test_manual_browser_driver_reuses_complete_subject_and_actual_driver_guards(self):
        base, producer, head, path, manifest = self.fixture.input_consumer(value=self.manifest, candidate_patch=True, separate_driver=True)
        with self.shared.selected_inputs(self.root, path, data_ref=producer, expected_head=head) as selection, fixtures.tempfile.TemporaryDirectory() as prepared:
            self.assertTrue(selection["explicit_manual"])
            self.assertNotEqual(manifest["sources"]["candidate"]["tree"], self.fixture.selector_git("rev-parse", head + "^{tree}"))
            self.assertEqual(self.shared.verify_driver_source(self.root, head, manifest, True)["ref"], base)
            output = Path(prepared)
            for side, text in (("baseline", "base source\n"), ("candidate", "candidate source\n")):
                workspace, recorded = self.shared.materialize(self.root, output, side, manifest["sources"][side], selection["root"])
                self.assertEqual((workspace / "source.txt").read_text(), text)
                self.shared.source_guard(workspace, recorded["tree"])
            patch_pin = manifest["sources"]["candidate"]["patches"][0]
            self.shared.pin(selection["root"], patch_pin).write_bytes(b"changed subject patch\n")
            with self.assertRaisesRegex(ValueError, "Pinned bytes changed"):
                self.shared.load_manifest(selection["root"], selection["path"])
        for arguments in ({"manifest_sha256": "0" * 64}, {"data_ref": "0" * 40}, {"expected_head": "0" * 40}):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                with self.shared.selected_inputs(self.root, path, **arguments):
                    self.fail("Changed browser input binding accepted")
        wrong = copy.deepcopy(manifest)
        wrong["driver_source"]["tree"] = manifest["sources"]["candidate"]["tree"]
        with self.assertRaisesRegex(ValueError, "driver commit or tree changed"):
            self.shared.verify_driver_source(self.root, head, wrong, True)
        unrelated = self.fixture.selector_git("commit-tree", manifest["driver_source"]["tree"], "-m", "Independent same-tree driver")
        wrong["driver_source"] = {**manifest["driver_source"], "ref": unrelated}
        with self.assertRaisesRegex(ValueError, "not an execution ancestor"):
            self.shared.verify_driver_source(self.root, head, wrong, True)
        wrong["driver_source"]["ref"] = "0" * 40
        with self.assertRaises(fixtures.subprocess.CalledProcessError):
            self.shared.verify_driver_source(self.root, head, wrong, True)
        self.fixture.selector_git("update-index", "--chmod=+x", "gradle/performance/paired-jvm.py")
        with self.assertRaisesRegex(ValueError, "index or working bytes changed"):
            self.shared.verify_driver_source(self.root, head, manifest, True)
        self.fixture.selector_git("restore", "--staged", "gradle/performance/paired-jvm.py")
        (self.root / "gradle/performance/paired-jvm.py").write_bytes(b"changed live controller\n")
        with self.assertRaisesRegex(ValueError, "working bytes changed"):
            self.shared.select_manifest(self.root, path)
        self.fixture.selector_git("restore", "gradle/performance/paired-jvm.py")
        (self.root / "unrepresented.txt").write_bytes(b"extra non-data source\n")
        self.fixture.selector_commit("Add undeclared execution source")
        with self.assertRaisesRegex(ValueError, "source differs from execution head"):
            self.shared.verify_driver_source(self.root, self.fixture.selector_git("rev-parse", "HEAD"), manifest, True)

    def test_browser_driver_cannot_override_automatic_pr_or_default_candidate_identity(self):
        base, _, head, path, _ = self.fixture.input_consumer(value=self.manifest, candidate_patch=True, separate_driver=True)
        for arguments in ({}, {"base": base, "head": head}, {"explicit": path, "base": base, "head": head}):
            with self.subTest(arguments=arguments), self.assertRaisesRegex(ValueError, "explicit manual"):
                self.shared.select_manifest(self.root, **arguments)
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "pull_request"}), self.assertRaisesRegex(ValueError, "explicit manual"):
            self.shared.select_manifest(self.root, path)
        default = fixtures.PairedJvmTest("runTest")
        default.setUp()
        self.addCleanup(default.temporary.cleanup)
        adapter = default.root / "tools/web/build.mjs"
        adapter.parent.mkdir(parents=True)
        adapter.write_bytes((self.root / "tools/web/build.mjs").read_bytes())
        _, producer, head, path, _ = default.input_consumer(value=self.manifest, candidate_patch=True, mismatch="blob")
        with self.assertRaisesRegex(ValueError, "Candidate source differs from execution head"):
            with self.shared.selected_inputs(default.root, path, data_ref=producer, expected_head=head):
                self.fail("Default candidate identity was bypassed")

    @patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch"})
    def test_browser_run_refuses_replayed_producer_and_changed_planned_manifest_before_launch(self):
        _, producer, head, path, _ = self.fixture.input_consumer(value=self.manifest, candidate_patch=True, separate_driver=True)
        original_check_output = fixtures.subprocess.check_output
        def git_only(arguments, **options):
            self.assertEqual(arguments[0], "git", "No process may start for invalid declared input")
            return original_check_output(arguments, **options)
        with self.shared.selected_inputs(self.root, path, data_ref=producer, expected_head=head) as selected:
            for mode in ("missing-producer", "wrong-producer", "changed-manifest-hash"):
                selection = dict(selected)
                if mode == "changed-manifest-hash":
                    selection["sha256"] = "0" * 64
                else:
                    selection["producer"] = "" if mode == "missing-producer" else "0" * 40
                output = self.root / mode
                with self.subTest(mode=mode), patch.object(self.browser, "os", SimpleNamespace(name="nt", path=os.path, environ=os.environ)), \
                     patch.object(self.browser.subprocess, "check_output", side_effect=git_only), \
                     self.assertRaisesRegex(ValueError, "unchanged immutable declared producer inputs"):
                    self.browser.run(self.shared, self.root, selection, output)
                record = self.shared.read_json(output / "attempt.json")
                self.assertEqual(record["status"], "failed")
                self.assertEqual(record["phases"], [])
                self.assertTrue(output.with_suffix(".zip").is_file())

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
        with self.assertRaisesRegex(ValueError, "Missing actual fresh owned admission"):
            self.browser.validate_terminal(self.shared, 0, terminal, admission, 4608 * 1024**2)
        self.browser.validate_terminal(self.shared, 0, terminal, {**admission, "availablePhysicalBytes": 4608 * 1024**2}, 4608 * 1024**2)
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
        linked_archives = {":api:jsJar": "api/build/libs/api-js.klib", ":runtime:core:jsJar": "runtime/core/build/libs/core-js.klib", ":runtime:web:jsJar": "runtime/web/build/libs/web-js.klib"}
        for pins in data["compiled_pins"].values():
            pins.extend({"path": path, "sha256": "a" * 64} for path in linked_archives.values())
        for pins in data["compiled_pins"].values():
            for item in pins:
                item["sha256"] = self.shared.hashlib.sha256(b"synthetic compiled bytes").hexdigest()

        browser_data = data
        cases = [(None, None, False, False), (None, "collect-standard-baseline", False, False), (None, None, True, False), (None, "collect-standard-baseline", True, False), (1, None, False, False), (3, None, False, False), (3, "source-candidate-1", False, False)]
        cases += [(3, None, False, True), (1, None, False, True), (3, "provision-baseline", False, True), (3, "provision-candidate", False, True), (3, "source-candidate-1", False, True)]
        cases = [(*case, False, False) for case in cases]
        cases += [(None, None, False, False, False, True), (None, "changed-driver-source", False, False, False, True)]
        cases += [(3, None, False, True, True, False), (3, "provision-baseline-fixtures", False, True, True, False),
                  (3, "source-candidate-1-fixtures", False, True, True, False), (3, "gate-source-baseline-0-api", False, True, True, False)]
        mutations = {name + "-" + when: name for name in ("profile", "installations") for when in ("between-phases", "during-phase")}
        cases += [(3, name, False, True, True, False) for name in mutations]
        cases += [(1, "capture-inputs", False, False, True, False), (1, "capture-command-failure", False, False, True, False), (1, "capture-known-java-changed", False, False, True, False)]
        cases += [(1, name, False, False, True, False) for name in ("capture-init-before", "capture-init-during")]
        for repetitions, failure, copy_failure, provisioning, controlled, separate_driver in cases:
            acquiring = failure in ("capture-inputs", "capture-command-failure", "capture-known-java-changed", "capture-init-before", "capture-init-during")
            data = self.controlled_source_manifest() if controlled else self.source_manifest(repetitions) if repetitions is not None else browser_data
            data = copy.deepcopy(data)
            if acquiring:
                data.pop("toolchain_binding")
                data.update(capture_toolchain_inputs=True, repetitions=1)
            if separate_driver:
                data["driver_source"] = {"ref": "d" * 40, "tree": "e" * 40}
            if provisioning:
                data["provision_dependency_caches"] = True
            if controlled:
                resource_path = self.root / "controlled-resources.json"
                resource_path.write_text(json.dumps({"minimumFreePhysicalBytes": 4608 * 1024**2, "daemonMetaspaceMiB": 384}), encoding="utf-8")
                data["resource_profile"] = {"path": resource_path.name, "sha256": self.shared.digest(resource_path)}
            if repetitions == 1 and not acquiring:
                data["tasks"] = [":integration:web:jsBrowserDistribution", ":integration:web:jvmTest", ":integration:web:installWebBrowsers", ":performance-testkit:jsJar", ":performance-testkit:verifyBrowserPerformanceDriver"]
                data["test_gate"] = {"task": ":integration:web:jvmTest", "required_tests": self.manifest["correctness"]["required_tests"]}
            output = self.root / (("complete" if failure is None else "failed") + ("-copy-failure" if copy_failure else "") + ("-source-" + str(repetitions) if repetitions is not None else "") + ("-provisioning" if provisioning else "") + ("-separate-driver" if separate_driver else "") + ("-controlled" if controlled else "") + ("-" + failure if failure is not None else ""))
            calls, side_paths = [], {}
            driver_checks = []
            api = SimpleNamespace(**vars(self.shared))
            api.git = lambda *args: "a" * 40
            api.load_manifest = lambda *args: data
            api.preserve_driver_sources = lambda *args: {}
            api.verify_driver_sources = lambda *args: None
            def verify_driver_source(root, head, actual_data, explicit_manual):
                if separate_driver is False:
                    return None
                self.assertIs(actual_data, data)
                self.assertTrue(explicit_manual)
                driver_checks.append(len(calls))
                if failure == "changed-driver-source" and calls:
                    raise ValueError("Changed frozen browser driver source")
                return {**data["driver_source"], "execution_head": head, "execution_tree": "f" * 40}
            api.verify_driver_source = verify_driver_source
            api.declared_inputs = lambda *args: ("c" * 40, "manifest.json")
            api.source_files = lambda *args: {"same-common-fixture": "same"}
            def source_guard(*args):
                init = output / "capture-toolchain-inputs.init.gradle"
                if failure == "capture-init-before" and init.exists() and not calls:
                    init.write_text("throw new GradleException('changed before invoke')", encoding="utf-8")
            api.source_guard = source_guard
            api.class_load_inventory = lambda *args: {}
            api.preserve_class_loads = lambda *args: {}
            def verify_vms(*args, maximum_diagnostic_bytes, require_all_java_generations, daemon_metaspace_mib):
                self.assertEqual(maximum_diagnostic_bytes, 16 * 1024**2)
                self.assertTrue(require_all_java_generations)
                self.assertEqual(daemon_metaspace_mib, 384 if controlled else 1024)
                if controlled:
                    directory = args[0]
                    self.shared.write_new(directory / "vms.json", [{"pid": 31, "home": str(homes["launcher"]), "role": "daemon", "diagnostic_sha256": self.shared.digest(args[1][0])}])
            api.verify_vms = verify_vms
            api.preserve_test_reports = lambda *args: None
            api.verify_test_gate = lambda directory, gate: self.shared.write_new(directory / "junit-verified.json", {"counts": {"skipped": int(failure == "gate-" + directory.name)}})
            def bind(profile, workspace, environment, arguments, evidence, *, bounded):
                requested = next(item for item in data["preparations"] if item["tasks"][0] == arguments[0])
                self.assertEqual(arguments[:len(requested["tasks"])], requested["tasks"])
                evidence.mkdir()
                manifest = evidence / "binding.json"
                self.shared.write_new(manifest, {"bounded": bounded, "arguments": arguments, "profile": str(profile)})
                return arguments + (["-Dorg.gradle.java.installations.auto-detect=false"] if bounded else []), dict(environment, STRATA_TOOLCHAIN_BINDING=str(manifest)), manifest
            def verify(manifest, workspace, environment, log):
                self.assertEqual(Path(environment["STRATA_TOOLCHAIN_BINDING"]), manifest)
                record = {"status": "passed", "builds": [{"daemon": {"home": str(homes["launcher"]), "runtime_version": "25.0.1", "jvm_version": "25.0.1"}}]}
                self.shared.write_new(manifest.parent / "selection-verified.json", record)
                return record
            def installation_digest(home):
                return self.shared.hashlib.sha256(b"".join(path.read_bytes() for path in sorted(Path(home).rglob("*")) if path.is_file())).hexdigest()
            binding = {"tools": SimpleNamespace(bind=bind, verify=verify, absolute_home=lambda value: Path(value).resolve(), installation_tree_digest=installation_digest),
                       "profile_path": self.root / "controlled-profile.json", "binder_sha256": self.shared.hashlib.sha256(b"synthetic frozen binder").hexdigest(),
                       "init_sha256": self.shared.hashlib.sha256(b"synthetic frozen init").hexdigest(),
                       "profile": {}, "installations": [{"home": str(home), "tree_sha256": installation_digest(home), "java_sha256": self.shared.digest(home / "bin/java.exe"),
                                                         "release_sha256": self.shared.digest(home / "release"), "vm_vendor": "Oracle Corporation" if role == "launcher" else "Eclipse Adoptium",
                                                         "jvm_version": "25.0.1" if role == "launcher" else "17.0.1", "runtime_version": "25.0.1" if role == "launcher" else "17.0.1"}
                                                        for role, home in homes.items()]}
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
                for item in browser_data["compiled_pins"][side]:
                    if item["path"] in linked_archives.values():
                        continue
                    path = workspace / item["path"]
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"synthetic compiled bytes")
                if controlled:
                    for item in data["artifacts"]:
                        path = workspace / item["glob"]
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(b"synthetic compiled bytes")
                    workspace.joinpath("gradle").mkdir()
                    workspace.joinpath("gradle/controlled_toolchains.py").write_bytes(b"synthetic frozen binder")
                    workspace.joinpath("gradle/controlled-toolchains.init.gradle").write_bytes(b"synthetic frozen init")
                path = workspace / browser_data["collections"][0]["adapter"]["path"]
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((self.root / path.relative_to(workspace)).read_bytes())
                return workspace, source

            def invoke(arguments, root, console):
                nonlocal preparation
                request = self.shared.read_json(Path(arguments[arguments.index("-Request") + 1]))
                terminal_path = Path(arguments[arguments.index("-Report") + 1])
                directory = terminal_path.parent
                name = directory.name
                calls.append(name)
                console.write_bytes(b"synthetic command transcript\n")
                if acquiring:
                    if failure == "capture-init-during":
                        (output / "capture-toolchain-inputs.init.gradle").write_text("throw new GradleException('changed during invoke')", encoding="utf-8")
                    self.assertEqual(request["arguments"][0], "help")
                    self.assertIn("-Pstrata.jvmOnly=true", request["arguments"])
                    self.assertFalse(any("org.gradle.java.installations." in item for item in request["arguments"]))
                    self.assertFalse(any(key.startswith("ORG_GRADLE_PROJECT_org.gradle.java.") for key in request["environment"]))
                    self.assertEqual(request["environment"]["STRATA_PAIRED_CAPTURE_MAJORS"], "17,25")
                    self.assertEqual(Path(request["environment"]["STRATA_PAIRED_CAPTURE_METADATA"]), directory / "actual-toolchain-metadata.json")
                    xml = output / "vms/acquisition.log"
                    xml.write_text("<hotspot_log><hotspot_log_done/></hotspot_log>", encoding="utf-8")
                if repetitions is None and name.startswith("prepare-"):
                    self.assertEqual(request["arguments"][:8], [":integration:web:jsBrowserDistribution", ":integration:web:jvmTest", ":integration:web:installWebBrowsers", ":performance-testkit:jsJar", ":performance-testkit:verifyBrowserPerformanceDriver", *linked_archives])
                    for task, relative in linked_archives.items():
                        if task in request["arguments"]:
                            path = Path(request["workspace"]) / relative
                            path.parent.mkdir(parents=True, exist_ok=True)
                            path.write_bytes(b"synthetic compiled bytes")
                if repetitions is not None and not acquiring:
                    preparation = next(item for item in data["preparations"] if name.endswith("-" + item["name"])) if controlled else data
                    self.assertEqual(request["arguments"][:len(preparation["tasks"])], preparation["tasks"])
                    self.assertIn("--rerun-tasks", request["arguments"])
                    self.assertEqual("-Pstrata.webOnly=true" in request["arguments"], "candidate" in name and not controlled)
                    self.assertEqual(request["environment"]["JAVA_HOME"], str(homes["launcher"]))
                    if controlled:
                        self.assertFalse(any(key.startswith("ORG_GRADLE_PROJECT_org.gradle.java.") for key in request["environment"]))
                        self.assertEqual("-Dorg.gradle.java.installations.auto-detect=false" in request["arguments"], "candidate" in name)
                        self.assertEqual(self.shared.read_json(Path(request["environment"]["STRATA_TOOLCHAIN_BINDING"]))["bounded"], "candidate" in name)
                        logs = output / "vms"
                        xml = logs / (name + ".log")
                        xml.write_text("<hotspot_log><vm_version><release>25.0.1</release></vm_version><vm_arguments><properties>java.vm.vendor=Oracle Corporation\njava.vm.version=25.0.1\n</properties></vm_arguments><hotspot_log_done/></hotspot_log>", encoding="utf-8")
                    else:
                        self.assertEqual(request["environment"]["ORG_GRADLE_PROJECT_org.gradle.java.installations.auto-detect"], "false")
                        self.assertEqual(request["environment"]["ORG_GRADLE_PROJECT_org.gradle.java.installations.auto-download"], "false")
                        self.assertEqual(request["environment"]["ORG_GRADLE_PROJECT_org.gradle.java.installations.fromEnv"], "")
                    self.assertEqual(request["environment"]["ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy"], "in-process")
                    console.write_text("\n".join("> Task " + task for task in preparation["tasks"]) + "\n", encoding="utf-8")
                    if preparation["test_gate"] is not None:
                        self.assertIn("-Pstrata.paired.testClasspathTask=" + preparation["test_gate"]["task"], request["arguments"])
                        retained = directory / "test-classpath"
                        retained.mkdir()
                        dependency = Path(request["workspace"]) / "runtime/core/build/libs/dependency.jar"
                        dependency.parent.mkdir(parents=True, exist_ok=True)
                        dependency.write_bytes(b"synthetic resolved dependency")
                        copy = retained / "classpath-0.jar"
                        copy.write_bytes(dependency.read_bytes())
                        self.shared.write_new(retained / "classpath.json", {"schema": "strata-paired-jvm-classpath-v1", "entries": [{"source": str(dependency), "path": str(copy), "sha256": self.shared.path_identity(dependency)}]})
                        empty = Path(request["workspace"]) / "integration/web/build/classes/java/jvmMain"
                        self.shared.write_new(retained / "original-classpath.json", {"sources": [str(empty), str(dependency)], "absent_outputs": [{"source": str(empty), "task": ":integration:web:compileJvmMainJava", "reason": "NO-SOURCE"}]})
                        with console.open("a", encoding="utf-8") as transcript:
                            transcript.write("> Task :integration:web:compileJvmMainJava NO-SOURCE\n")
                floor = 4608 * 1024**2 if controlled else 4 * 1024**3
                self.assertEqual((request["minimumFreePhysicalBytes"], request["jobCommittedBytes"], request["workSeconds"], request["cleanupSeconds"]), (floor, 3 * 1024**3, 1800, 30))
                self.assertEqual(request["environment"]["STRATA_PAIRED_DAEMON_METASPACE_MIB"], "384" if controlled else "1024")
                if "program" not in request:
                    self.assertTrue(any(argument.startswith("-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=" + ("384m" if controlled else "1g") + " ") for argument in request["arguments"]))
                image = str(homes["launcher"] / "bin/java.exe") if acquiring else str(node)
                member = {"pid": 1, "image": image, "primaryBeforeResume": True, "inOwnedJob": True, "completed": True, "handleClosed": True, "exitCode": 0,
                          "creationFileTime": 1, "exitFileTime": 2, "affinityMask": 15, "systemAffinityMask": 15}
                exit_code = 7 if name == failure or failure == "capture-command-failure" else 0
                self.shared.write_new(terminal_path, {"exitCode": exit_code, "naturalRelease": True, "handlesClosed": True, "activeProcessesAfterCleanup": 0,
                                                    "jobName": name, "callerPid": 1, "members": {"1": [member]}, "inheritedAffinityMask": 15, "systemAffinityMask": 15,
                                                    "jobUserCpu100ns": 10, "jobKernelCpu100ns": 20, "peakJobCommittedBytes": 30,
                                                    "primaryAdmission": {"beforeResume": True, "pid": 1, "creationFileTime": 1, "image": str(node), "jobName": name, "inOwnedJob": True, "availablePhysicalBytes": floor}})
                self.shared.write_new(directory / "child-admission.json", {"beforeResume": True, "pid": 1, "creationFileTime": 1, "image": str(node), "inOwnedJob": True, "jobName": name, "availablePhysicalBytes": floor})
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
                if failure in mutations and failure.endswith("during-phase") and name == "provision-baseline-api":
                    path = self.shared.pin(self.root, data["toolchain_binding"][mutations[failure]])
                    path.write_text(json.dumps({"synthetic": "changed-" + mutations[failure]}), encoding="utf-8")
                if failure == "capture-known-java-changed":
                    homes["launcher"].joinpath("bin/java.exe").write_bytes(b"changed after acquisition started")
                return exit_code

            preserve_outputs = self.browser.preserve_source_outputs
            def preserve_then_mutate(shared, workspace, directory, specification):
                result = preserve_outputs(shared, workspace, directory, specification)
                if failure in mutations and failure.endswith("between-phases") and directory.name == "provision-baseline-api":
                    path = self.shared.pin(self.root, data["toolchain_binding"][mutations[failure]])
                    path.write_text(json.dumps({"synthetic": "changed-" + mutations[failure]}), encoding="utf-8")
                return result

            api.materialize, api.invoke = materialize, invoke
            node_metadata = json.dumps({"path": str(node), "version": "synthetic"})
            selection = {"root": self.root, "path": self.root / "manifest.json", "producer": "", "execution_head": "a" * 40}
            if separate_driver:
                selection.update(producer="c" * 40, explicit_manual=True)
            selection["path"].write_text(json.dumps(data), encoding="utf-8")
            selection["sha256"] = self.shared.digest(selection["path"])
            preparation = data
            if controlled:
                self.load(data)
            capture_tools = SimpleNamespace(Model=SimpleNamespace(JVM="jvm", COMPLETE="complete"), required_versions=lambda *args: {17, 25})
            capture_pins = {name: {"path": name + ".json", "sha256": "a" * 64} for name in ("profile", "installations")}
            with patch.object(self.browser, "source_toolchain_tools", return_value=capture_tools), patch.object(self.browser, "capture_source_toolchain_inputs", return_value=capture_pins) as capture_builder, patch.object(self.browser, "controlled_source_inputs", return_value=binding), patch.object(self.browser, "preserve_source_outputs", side_effect=preserve_then_mutate), patch.object(self.browser, "os", SimpleNamespace(name="nt", path=self.browser.os.path, environ=self.browser.os.environ)), patch.object(self.browser, "sys", SimpleNamespace(executable=str(node))), patch.dict(self.browser.os.environ, {"JAVA_HOME": str(homes["launcher"]), "STRATA_PAIRED_TOOLCHAIN": str(homes["toolchain"])}), patch.object(self.browser.shutil, "which", return_value=str(node)), patch.object(self.browser.subprocess, "check_output", side_effect=lambda args, **kwargs: node_metadata if args[0] == str(node) else "{}"):
                if failure in (None, "capture-inputs") and copy_failure is False:
                    self.browser.run(api, self.root, selection, output)
                else:
                    with self.assertRaisesRegex(ValueError, "Acquisition metadata init bytes changed" if failure in ("capture-init-before", "capture-init-during") else "Prepared evidence preservation failed" if failure is None else "Known pinned Java bytes changed" if failure == "capture-known-java-changed" else "Pinned bytes changed" if failure in mutations else "Changed frozen browser driver source" if failure == "changed-driver-source" else "JVM parity tests were skipped" if failure.startswith("gate-") else "Owned browser phase failed"):
                        self.browser.run(api, self.root, selection, output)
            record = self.shared.read_json(output / "attempt.json")
            self.assertNotEqual(side_paths["baseline"], side_paths["candidate"])
            self.assertTrue(output.with_suffix(".zip").is_file())
            if acquiring:
                self.assertEqual(calls, [] if failure == "capture-init-before" else ["toolchain-input-acquisition"])
                self.assertFalse((output / "prepared-pins.json").exists())
                self.assertFalse((output / "report.md").exists())
                self.assertNotIn("completed_source_invocations", record)
                if failure == "capture-inputs":
                    self.assertEqual(record["toolchain_input_init"]["sha256"], self.shared.digest(output / record["toolchain_input_init"]["path"]))
                    capture_builder.assert_called_once()
                    self.assertEqual((record["status"], record["original_source_preparations"], record["runtime_collection"], record["complete_measured_attempts"]), ("toolchain-inputs-prepared", "UNRUN", "UNRUN", 0))
                    self.assertEqual(record["toolchain_input_pins"], capture_pins)
                else:
                    capture_builder.assert_not_called()
                    self.assertEqual(record["status"], "failed")
                continue
            if failure is None and copy_failure is False:
                if repetitions is None:
                    self.assertEqual((record["status"], record["expected_cases"], record["measured_browser_invocations"]), ("passed", 372, 36))
                    self.assertEqual(len(calls), 12)
                    if separate_driver:
                        self.assertEqual(record["driver_source"], {**data["driver_source"], "execution_head": "a" * 40, "execution_tree": "f" * 40})
                        self.assertEqual(record["scope"], {"consumer_ci_establishes_subject_acceptance": False, "prior_rows_adopted": False})
                        self.assertEqual(driver_checks, [0] + [value for index in range(12) for value in (index, index + 1)])
                else:
                    count = 2 if controlled else 1
                    self.assertEqual((record["status"], record["completed_source_invocations"], record["runtime_collection"]), ("prepared", repetitions * 2 * count, "UNRUN"))
                    self.assertEqual(record["source_resources"], {"minimumFreePhysicalBytes": 4608 * 1024**2 if controlled else 4 * 1024**3, "daemonMetaspaceMiB": 384 if controlled else 1024})
                    if not controlled:
                        self.assertEqual(record["tasks_per_invocation"], len(data["tasks"]))
                    provisioning_names = ["provision-baseline", "provision-candidate"] if provisioning else []
                    attempts = provisioning_names + [f"source-{side}-{index}" for index in range(repetitions) for side in ("baseline", "candidate")]
                    self.assertEqual(calls, [name + "-" + item["name"] for name in attempts for item in data["preparations"]] if controlled else attempts)
                    self.assertEqual(record["completed_dependency_provisioning_invocations"], len(provisioning_names) * count)
                    self.assertEqual([phase["source_metric_scope"] for phase in record["phases"]], ["untimed_dependency_provisioning"] * (len(provisioning_names) * count) + ["measured_source_preparation"] * (repetitions * 2 * count))
                    self.assertTrue(all(phase["jobUserCpu100ns"] == 10 and phase["jobKernelCpu100ns"] == 20 and phase["peakJobCommittedBytes"] == 30 and phase["allocation_bytes"] is None and phase["rss_bytes"] is None for phase in record["phases"]))
                    pins = self.shared.read_json(output / "prepared-pins.json")
                    self.assertEqual(set(pins["compiled_pins"]), {"baseline", "candidate"})
                    self.assertTrue(all(len(values) == len(data["artifacts"]) and all(value["sha256"] == self.shared.hashlib.sha256(b"synthetic compiled bytes").hexdigest() for value in values) for values in pins["compiled_pins"].values()))
                    if controlled:
                        self.assertNotIn("tasks_per_invocation", record)
                        self.assertEqual(record["tasks_per_attempt"], 17)
                        self.assertEqual(record["tasks_per_preparation"], {"api": 2, "fixtures": 15})
                        self.assertEqual((output / "source-resource-profile.json").read_bytes(), resource_path.read_bytes())
                        self.assertEqual((record["complete_measured_attempts"], record["preparations_per_attempt"], record["complete_dependency_provisioning_attempts"]), (6, 2, 2))
                        self.assertTrue(all([item["path"] for item in values] == [item["glob"] for item in data["artifacts"]] for values in pins["compiled_pins"].values()))
                        self.assertTrue(all((output / "phases" / name / "toolchains/selection-verified.json").is_file() and (output / "phases" / name / "vm-metadata-verified.json").is_file() for name in calls))
                        self.assertTrue(all(phase["invocation_started_ns"] <= phase["invocation_closed_ns"] for phase in record["phases"]))
                    self.assertFalse((output / "report.md").exists())
                    if data["test_gate"] is not None:
                        self.assertTrue(all(self.shared.read_json(output / "phases" / name / "test-classpath-verified.json")["present_entries"] == 1 for name in calls if not controlled or name.endswith("-api")))
            elif failure in mutations:
                self.assertEqual(record["status"], "failed")
                self.assertEqual(calls, ["provision-baseline-api"])
                self.assertIn("Pinned bytes changed", record["error"])
                self.assertFalse((output / "phases/provision-baseline-fixtures/request.json").exists())
                self.assertFalse((output / "prepared-pins.json").exists())
                self.assertNotIn("completed_source_invocations", record)
                self.assertNotIn("complete_measured_attempts", record)
                self.assertTrue((output / "phases/provision-baseline-api/terminal.json").is_file())
                self.assertTrue((output / "phases/provision-baseline-api/toolchains/selection-verified.json").is_file())
                self.assertNotEqual(self.shared.digest(self.root / data["toolchain_binding"][mutations[failure]]["path"]), data["toolchain_binding"][mutations[failure]]["sha256"])
                self.assertEqual(self.shared.read_json(self.root / data["toolchain_binding"][mutations[failure]]["path"]), {"synthetic": "changed-" + mutations[failure]})
            elif failure == "changed-driver-source":
                self.assertEqual(record["status"], "failed")
                self.assertEqual(calls, ["prepare-baseline"])
                self.assertEqual(driver_checks, [0, 0, 1])
                self.assertFalse((output / "report.md").exists())
                self.assertIn("Changed frozen browser driver source", record["error"])
            elif failure is not None:
                self.assertEqual(record["status"], "failed")
                self.assertEqual(calls[-1], failure.removeprefix("gate-"))
                self.assertNotIn("collect-standard-candidate", calls)
                self.assertFalse((output / "report.md").exists())
                self.assertIn("JVM parity tests were skipped" if failure.startswith("gate-") else "Owned browser phase failed", record["error"])
                if failure.startswith("provision-"):
                    self.assertFalse(any(name.startswith("source-") for name in calls))
                    self.assertFalse((output / "prepared-pins.json").exists())
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
