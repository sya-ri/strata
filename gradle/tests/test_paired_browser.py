"""Verify shared browser selection, complete unions and failure boundaries without browsers or Java."""
import copy
import importlib.util
import json
import os
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from unittest.mock import Mock, patch

import test_paired_jvm as fixtures


class PairedBrowserTest(unittest.TestCase):
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
        actual = {"immediate": True, "concurrentChildren": 8, "generations": [copy.deepcopy(value[0]) for value in members.values()]}
        receipt = boundary.validate_immediate_history(terminal, actual)
        self.assertEqual(receipt["independentGenerations"], 32)
        self.assertEqual(receipt["observedBelowFormerPollInterval"], 32)
        for mode in ("missing", "duplicate", "image", "birth", "exit", "accounting", "unclosed", "no-short-lifetime"):
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
                else:
                    for row in oracle["generations"]:
                        row["exitFileTime"] = row["creationFileTime"] + 100 * 10000
                        changed["members"][str(row["pid"])][0]["exitFileTime"] = row["exitFileTime"]
                with self.assertRaises(ValueError):
                    boundary.validate_immediate_history(changed, oracle)

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
        value["source_files"] = ["quality/component-benchmarks/src/jmh/resources/runtime-api.tsv"]
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
            lambda item: item["tasks"].append(":quality:component-benchmarks:jmhComponents"),
        ):
            invalid = copy.deepcopy(value)
            mutation(invalid)
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.load(invalid)

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
        absent = {"source": str(empty), "task": ":integration:web:compileJvmMainJava", "reason": "NO-SOURCE"}
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

        browser_data = data
        cases = [(None, None, False, False), (None, "collect-standard-baseline", False, False), (None, None, True, False), (None, "collect-standard-baseline", True, False), (1, None, False, False), (3, None, False, False), (3, "source-candidate-1", False, False)]
        cases += [(3, None, False, True), (1, None, False, True), (3, "provision-baseline", False, True), (3, "provision-candidate", False, True), (3, "source-candidate-1", False, True)]
        for repetitions, failure, copy_failure, provisioning in cases:
            data = self.source_manifest(repetitions) if repetitions is not None else browser_data
            if provisioning:
                data["provision_dependency_caches"] = True
            if repetitions == 1:
                data["tasks"] = [":integration:web:jsBrowserDistribution", ":integration:web:jvmTest", ":integration:web:installWebBrowsers", ":performance-testkit:jsJar", ":performance-testkit:verifyBrowserPerformanceDriver"]
                data["test_gate"] = {"task": ":integration:web:jvmTest", "required_tests": self.manifest["correctness"]["required_tests"]}
            output = self.root / (("complete" if failure is None else "failed") + ("-copy-failure" if copy_failure else "") + ("-source-" + str(repetitions) if repetitions is not None else "") + ("-provisioning" if provisioning else "") + ("-" + failure if failure is not None else ""))
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
            def verify_vms(*args, maximum_diagnostic_bytes, require_all_java_generations):
                self.assertEqual(maximum_diagnostic_bytes, 16 * 1024**2)
                self.assertTrue(require_all_java_generations)
            api.verify_vms = verify_vms
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
                for item in browser_data["compiled_pins"][side]:
                    path = workspace / item["path"]
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"synthetic compiled bytes")
                path = workspace / browser_data["collections"][0]["adapter"]["path"]
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
                if repetitions is not None:
                    self.assertEqual(request["arguments"][:len(data["tasks"])], data["tasks"])
                    self.assertIn("--rerun-tasks", request["arguments"])
                    self.assertEqual("-Pstrata.webOnly=true" in request["arguments"], "candidate" in name)
                    self.assertEqual(request["environment"]["JAVA_HOME"], str(homes["launcher"]))
                    self.assertEqual(request["environment"]["ORG_GRADLE_PROJECT_org.gradle.java.installations.auto-detect"], "false")
                    self.assertEqual(request["environment"]["ORG_GRADLE_PROJECT_org.gradle.java.installations.auto-download"], "false")
                    self.assertEqual(request["environment"]["ORG_GRADLE_PROJECT_org.gradle.java.installations.fromEnv"], "")
                    self.assertEqual(request["environment"]["ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy"], "in-process")
                    console.write_text("\n".join("> Task " + task for task in data["tasks"]) + "\n", encoding="utf-8")
                    if data["test_gate"] is not None:
                        self.assertIn("-Pstrata.paired.testClasspathTask=:integration:web:jvmTest", request["arguments"])
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
                self.assertEqual((request["minimumFreePhysicalBytes"], request["jobCommittedBytes"], request["workSeconds"], request["cleanupSeconds"]), (4 * 1024**3, 3 * 1024**3, 1800, 30))
                member = {"pid": 1, "image": str(node), "primaryBeforeResume": True, "inOwnedJob": True, "completed": True, "handleClosed": True, "exitCode": 0,
                          "creationFileTime": 1, "exitFileTime": 2, "affinityMask": 15, "systemAffinityMask": 15}
                exit_code = 7 if name == failure else 0
                self.shared.write_new(terminal_path, {"exitCode": exit_code, "naturalRelease": True, "handlesClosed": True, "activeProcessesAfterCleanup": 0,
                                                    "jobName": name, "callerPid": 1, "members": {"1": [member]}, "inheritedAffinityMask": 15, "systemAffinityMask": 15,
                                                    "jobUserCpu100ns": 10, "jobKernelCpu100ns": 20, "peakJobCommittedBytes": 30,
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
            with patch.object(self.browser, "os", SimpleNamespace(name="nt", path=self.browser.os.path, environ=self.browser.os.environ)), patch.object(self.browser, "sys", SimpleNamespace(executable=str(node))), patch.dict(self.browser.os.environ, {"JAVA_HOME": str(homes["launcher"]), "STRATA_PAIRED_TOOLCHAIN": str(homes["toolchain"])}), patch.object(self.browser.shutil, "which", return_value=str(node)), patch.object(self.browser.subprocess, "check_output", side_effect=lambda args, **kwargs: node_metadata if args[0] == str(node) else "{}"):
                if failure is None and copy_failure is False:
                    self.browser.run(api, self.root, selection, output)
                else:
                    with self.assertRaisesRegex(ValueError, "Prepared evidence preservation failed" if failure is None else "Owned browser phase failed"):
                        self.browser.run(api, self.root, selection, output)
            record = self.shared.read_json(output / "attempt.json")
            self.assertNotEqual(side_paths["baseline"], side_paths["candidate"])
            self.assertTrue(output.with_suffix(".zip").is_file())
            if failure is None and copy_failure is False:
                if repetitions is None:
                    self.assertEqual((record["status"], record["expected_cases"], record["measured_browser_invocations"]), ("passed", 372, 36))
                    self.assertEqual(len(calls), 12)
                else:
                    self.assertEqual((record["status"], record["completed_source_invocations"], record["tasks_per_invocation"], record["runtime_collection"]), ("prepared", repetitions * 2, len(data["tasks"]), "UNRUN"))
                    provisioning_names = ["provision-baseline", "provision-candidate"] if provisioning else []
                    self.assertEqual(calls, provisioning_names + [f"source-{side}-{index}" for index in range(repetitions) for side in ("baseline", "candidate")])
                    self.assertEqual(record["completed_dependency_provisioning_invocations"], len(provisioning_names))
                    self.assertEqual([phase["source_metric_scope"] for phase in record["phases"]], ["untimed_dependency_provisioning"] * len(provisioning_names) + ["measured_source_preparation"] * (repetitions * 2))
                    self.assertTrue(all(phase["jobUserCpu100ns"] == 10 and phase["jobKernelCpu100ns"] == 20 and phase["peakJobCommittedBytes"] == 30 and phase["allocation_bytes"] is None and phase["rss_bytes"] is None for phase in record["phases"]))
                    pins = self.shared.read_json(output / "prepared-pins.json")
                    self.assertEqual(set(pins["compiled_pins"]), {"baseline", "candidate"})
                    self.assertTrue(all(len(values) == 2 and all(value["sha256"] == self.shared.hashlib.sha256(b"synthetic compiled bytes").hexdigest() for value in values) for values in pins["compiled_pins"].values()))
                    self.assertFalse((output / "report.md").exists())
                    if data["test_gate"] is not None:
                        self.assertTrue(all(self.shared.read_json(output / "phases" / name / "test-classpath-verified.json")["present_entries"] == 1 for name in calls))
            elif failure is not None:
                self.assertEqual(record["status"], "failed")
                self.assertEqual(calls[-1], failure)
                self.assertNotIn("collect-standard-candidate", calls)
                self.assertFalse((output / "report.md").exists())
                self.assertIn("Owned browser phase failed", record["error"])
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
