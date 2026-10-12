"""Exercise generic manifest selection and immutable evidence boundaries without Java or timing."""
import copy
import csv
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location("paired_jvm", Path(__file__).resolve().parents[1] / "performance/paired-jvm.py")
PAIRED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAIRED)
QUALIFICATION_SPEC = importlib.util.spec_from_file_location("qualified_preflight", Path(__file__).resolve().parents[1] / "performance/verify-qualified-preflight.py")
QUALIFICATION = importlib.util.module_from_spec(QUALIFICATION_SPEC)
QUALIFICATION_SPEC.loader.exec_module(QUALIFICATION)


class PairedJvmTest(unittest.TestCase):
    """Invalid manifests cannot weaken the fixed three-by-two-mode collection contract."""
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)
        self.input = self.root / "fixture.txt"
        self.input.write_text("fixed input\n", encoding="utf-8")
        self.pin = {"path": "fixture.txt", "sha256": PAIRED.digest(self.input)}
        self.manifest = {
            "schema": "strata-paired-jvm-ci-v1",
            "sources": {side: {"ref": "a" * 40, "tree": "b" * 40, "patches": []} for side in ("fixture", "baseline", "candidate")},
            "runtime_projects": [":api", ":runtime:core", ":runtime:headless"],
            "suites": [{"project": ":quality:benchmarks", "name": "arbitrary-new-fixture", "benchmarks": ["IndependentFixtureBenchmark"],
                        "workloads": ["IndependentFixtureBenchmark.operation"], "parameters": {"size": ["1", "8"]}, "expected_cases": 2}],
            "inputs": [{"label": "external-input", "pin": self.pin}],
            "compiled_pins": {side: [] for side in ("fixture", "baseline", "candidate")},
            "dependency_pins": [],
            "java_pins": [],
        }

    def load(self, value):
        path = self.root / "manifest.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return PAIRED.load_manifest(self.root, path)

    def test_generated_selection_without_fixture_registry(self):
        self.assertEqual(self.load(self.manifest), self.manifest)
        value = copy.deepcopy(self.manifest)
        value["suites"][0].update(project=":quality:component-benchmarks", benchmarks=["AnotherNewBenchmark"], workloads=[], parameters={})
        self.assertEqual(self.load(value), value)
        value["suites"][0].update(project=":quality:remote-benchmarks", benchmarks=[], workloads=["RemoteProtocolBenchmark.diff"])
        self.assertEqual(self.load(value), value)

    def test_logical_input_trees_preserve_consumed_object_and_pack_paths_with_flat_compatibility(self):
        inputs = []
        for group in ("first", "second"):
            payload = (group + " object").encode()
            object_hash = hashlib.sha1(payload).hexdigest()
            files = {"index.json": b"index", f"objects/{object_hash[:2]}/{object_hash}": payload,
                     "pack/assets/example/font/default.json": b"font", "pack/assets/example/font/extra.json": b"extra"}
            for index, (relative, data) in enumerate(files.items()):
                source = self.root / "source" / group / relative
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_bytes(data)
                inputs.append({"label": f"{group}-{index}", "pin": {"path": source.relative_to(self.root).as_posix(), "sha256": PAIRED.digest(source)},
                               "layout": {"root": group, "path": relative}})
        inputs.append({"label": "legacy", "pin": self.pin})
        value = copy.deepcopy(self.manifest)
        value["inputs"] = inputs
        self.assertEqual(self.load(value), value)
        owner = self.root / "prepared"
        result = PAIRED.preserve_inputs(self.root, owner, inputs)
        self.assertEqual((owner / "input-8.bin").read_bytes(), self.input.read_bytes())
        for group in ("first", "second"):
            object_path = Path(result[f"{group}-1"]["path"])
            objects = object_path.parent.parent
            self.assertEqual(objects, owner / group / "objects")
            self.assertEqual((objects / object_path.name[:2] / object_path.name).read_bytes(), (group + " object").encode())
            custom = Path(result[f"{group}-2"]["path"])
            pack = custom.parent.parent.parent.parent
            self.assertEqual((pack / "assets/example/font/extra.json").read_bytes(), b"extra")
        self.assertEqual(set(result), {item["label"] for item in inputs})
        for item in inputs:
            self.assertEqual(PAIRED.digest(Path(result[item["label"]]["path"])), item["pin"]["sha256"])

    def test_logical_input_layout_rejects_traversal_nonportable_names_and_destination_collisions(self):
        def item(root, path, label="input"):
            return {"label": label, "pin": self.pin, "layout": {"root": root, "path": path}}
        invalid = [[item(root, "file")] for root in ("", "../root", "root/child", "C:", "a" * 65, "NUL")]
        invalid += [[item("group", path)] for path in ("", ".", "../file", "nested/../file", "/absolute", "C:/file", "folder\\file",
                                                        "nested//file", "./file", ".git/file", ".GIT/file", "NUL.txt", "nested/COM1.dat", "file.", "a" * 4097)]
        invalid += [[item("group", "file", "a"), item("group", "file", "b")],
                    [item("group", "file", "a"), item("group", "FILE", "b")],
                    [item("group", "folder/a", "a"), item("group", "Folder/b", "b")],
                    [item("group", "a", "a"), item("Group", "b", "b")],
                    [item("group", "file", "a"), item("group", "file/child", "b")],
                    [item("group", "file/child", "a"), item("group", "file", "b")]]
        for layout in (None, [], {}, {"root": "group"}, {"root": "group", "path": "file", "extra": True}, {"root": 1, "path": "file"}, {"root": "group", "path": 1}):
            invalid.append([{"label": "input", "pin": self.pin, "layout": layout}])
        for inputs in invalid:
            value = copy.deepcopy(self.manifest)
            value["inputs"] = inputs
            with self.subTest(inputs=inputs), self.assertRaises(ValueError):
                self.load(value)

    def test_logical_inputs_reject_linked_destination_without_touching_its_target(self):
        for nested in (False, True):
            owner = self.root / f"prepared-{nested}"
            owner.mkdir()
            target = (owner if nested else self.root) / f"protected-{nested}"
            target.mkdir()
            protected = target / "sentinel"
            protected.write_bytes(b"untouched")
            link = owner / "group"
            if os.name == "nt":
                result = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(target)], capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
            else:
                link.symlink_to(target, target_is_directory=True)
            inputs = [{"label": "nested", "pin": self.pin, "layout": {"root": "group", "path": "new-file"}}]
            with self.subTest(nested=nested), self.assertRaises(ValueError):
                PAIRED.preserve_inputs(self.root, owner, inputs)
            self.assertEqual(protected.read_bytes(), b"untouched")
            self.assertFalse((target / "new-file").exists())
            self.assertEqual(self.input.read_text(encoding="utf-8"), "fixed input\n")

    def test_logical_inputs_reject_changed_pins_and_undeclared_tree_entries(self):
        inputs = [{"label": "nested", "pin": self.pin, "layout": {"root": "group", "path": "nested/file"}}]
        self.input.write_bytes(b"changed")
        with self.assertRaises(ValueError):
            PAIRED.preserve_inputs(self.root, self.root / "changed", inputs)
        self.input.write_text("fixed input\n", encoding="utf-8")
        owner = self.root / "extra"
        owner.mkdir()
        (owner / "undeclared").write_bytes(b"extra")
        with self.assertRaises(ValueError):
            PAIRED.preserve_inputs(self.root, owner, inputs)
        self.assertEqual((owner / "undeclared").read_bytes(), b"extra")
        empty = self.root / "empty-directory"
        (empty / "undeclared").mkdir(parents=True)
        with self.assertRaises(ValueError):
            PAIRED.preserve_inputs(self.root, empty, inputs)

    def test_preserve_existing_dotted_input_labels_and_compiled_profiler_names(self):
        value = copy.deepcopy(self.manifest)
        value["inputs"][0]["label"] = "unihex-bounds-assets.zip"
        value["suites"][0]["profilers"] = ["dev.s7a.strata.quality.benchmark.UnihexBoundsCpuProfiler"]
        self.assertEqual(self.load(value), value)
        for label in ("../assets.zip", "assets/file", "line\nfeed", "key=value", "key:value"):
            changed = copy.deepcopy(value)
            changed["inputs"][0]["label"] = label
            with self.subTest(label=label), self.assertRaises(ValueError):
                self.load(changed)

        for profilers in (["gc"], ["bad;command.Profiler"], ["example..Profiler"], ["example.Profiler", "example.Profiler"]):
            changed = copy.deepcopy(value)
            changed["suites"][0]["profilers"] = profilers
            with self.subTest(profilers=profilers), self.assertRaises(ValueError):
                self.load(changed)

    def test_operation_connection_is_optional_and_rejects_partial_or_stale_registration(self):
        value = copy.deepcopy(self.manifest)
        value["suites"][0]["operation_reports"] = {"adapters": {"example.Fixture": "example.Adapter"}, "warmup": 30,
                                                  "samples": 60, "expected_rows": 2, "expected_controls": 1}
        self.assertEqual(self.load(value), value)
        for key, invalid in (("samples", 0), ("samples", True), ("warmup", -1), ("expected_rows", 1),
                             ("expected_controls", 3), ("adapters", {}), ("adapters", {"example.Fixture": "bad;Adapter"})):
            changed = copy.deepcopy(value)
            changed["suites"][0]["operation_reports"][key] = invalid
            with self.subTest(key=key, invalid=invalid), self.assertRaises(ValueError):
                self.load(changed)

    def test_operation_table_keeps_nullable_processor_deltas_and_units(self):
        comparison = {"phases": [{"operation_id": "complete", "before": {"owner_thread_cpu_ns_per_sample": None,
                                                                                 "owner_thread_allocated_bytes_per_sample": 10.0},
                                  "after": {"owner_thread_cpu_ns_per_sample": None, "owner_thread_allocated_bytes_per_sample": 6.0},
                                  "delta": {"owner_thread_cpu_ns_per_sample": None, "owner_thread_allocated_bytes_per_sample": -4.0}}]}
        PAIRED.table(self.root, [], [], [("full", comparison)])
        with (self.root / "table.csv").open(encoding="utf-8", newline="") as source:
            rows = list(csv.DictReader(source))
        self.assertEqual(rows[0]["baseline"], "")
        self.assertEqual(rows[0]["unit"], "ns/sample")
        self.assertEqual(rows[1]["unit"], "B/sample")
        self.assertEqual(rows[1]["delta"], "-4.0")
        self.assertIn("unavailable", (self.root / "report.md").read_text(encoding="utf-8"))

    def test_reject_changed_missing_or_duplicate_inputs_and_selectors(self):
        mutations = [
            lambda value: value.update(repetitions=1),
            lambda value: value["sources"]["baseline"].update(ref="master"),
            lambda value: value["sources"].pop("candidate"),
            lambda value: value["runtime_projects"].append(":api"),
            lambda value: value["suites"][0].update(project=":unknown"),
            lambda value: value["suites"][0].update(benchmarks=["Foo", "Foo"]),
            lambda value: value["suites"][0].update(expected_cases=0),
            lambda value: value["suites"][0].update(expected_cases=True),
            lambda value: value["suites"][0].update(parameters={"size": ["1", "1"]}),
            lambda value: value["inputs"].append(value["inputs"][0]),
            lambda value: value["inputs"][0]["pin"].update(sha256="0" * 64),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                value = copy.deepcopy(self.manifest)
                mutation(value)
                with self.assertRaises(ValueError):
                    self.load(value)
        self.input.write_text("changed input\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.load(self.manifest)

    def test_optional_compiled_preflight_keeps_whole_control_inventory_and_pins(self):
        value = copy.deepcopy(self.manifest)
        value["suites"][0]["preflight"] = {
            "main": "independent.Controller", "qualification": self.pin,
            "runtime_classes": {project: "independent.Runtime" for project in value["runtime_projects"]},
            "controls": [{"id": "row-1", "arguments": ["untimed", "1"]}, {"id": "row-2", "arguments": ["untimed", "2"]}],
        }
        self.assertEqual(self.load(value), value)
        value["suites"][0]["operation_reports"] = {"adapters": {"example.Fixture": "example.Adapter"}, "warmup": 30,
                                                  "samples": 60, "expected_rows": 2, "expected_controls": 1}
        self.assertEqual(self.load(value), value)
        changes = [lambda control: control.update(command="arbitrary shell"),
                   lambda control: control.update(main="Controller"),
                   lambda control: control["runtime_classes"].pop(":api"),
                   lambda control: control.update(controls=[]),
                   lambda control: control["controls"].append(control["controls"][0]),
                   lambda control: control["controls"][0].update(arguments=["zero\x00byte"]),
                   lambda control: control["qualification"].update(sha256="0" * 64)]
        for change in changes:
            changed = copy.deepcopy(value)
            change(changed["suites"][0]["preflight"])
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.load(changed)

    def test_independent_test_gates_reject_filters_nonruntime_tasks_and_missing_bindings(self):
        value = copy.deepcopy(self.manifest)
        value["suites"][0]["test_gates"] = [{"task": ":runtime:headless:test", "required_tests": ["independent.Controls#full owner failure"]}]
        self.assertEqual(self.load(value), value)
        changes = [lambda gate: gate.update(task=":runtime:headless:check"),
                   lambda gate: gate.update(task=":quality:benchmarks:test"),
                   lambda gate: gate.update(task=":runtime:headless:test --tests One"),
                   lambda gate: gate.update(required_tests=[]),
                   lambda gate: gate.update(required_tests=["Controls"]),
                   lambda gate: gate.update(arguments=["--tests", "One"])]
        for change in changes:
            changed = copy.deepcopy(value)
            change(changed["suites"][0]["test_gates"][0])
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.load(changed)
        value["suites"][0]["test_gates"].append(value["suites"][0]["test_gates"][0])
        with self.assertRaises(ValueError):
            self.load(value)

    def test_independent_junit_gate_requires_actual_complete_method_results_and_preserves_reports(self):
        gate = {"task": ":runtime:headless:test", "required_tests": ["independent.Controls#owner", "independent.Controls#failure"]}
        reports = self.root / "runtime/headless/build/test-results/test"
        reports.mkdir(parents=True)
        owner = '<testcase classname="independent.Controls" name="owner()"/>'
        failure = '<testcase classname="independent.Controls" name="failure()"/>'
        for index, (counts, body) in enumerate((
                ((2, 0, 0, 0), owner + failure),
                ((1, 0, 0, 0), owner),
                ((2, 0, 0, 1), owner + '<testcase classname="independent.Controls" name="failure()"><skipped/></testcase>'),
                ((2, 1, 0, 0), owner + '<testcase classname="independent.Controls" name="failure()"><failure/></testcase>'),
                ((3, 0, 0, 0), owner + failure + failure),
                ((3, 0, 1, 0), owner + failure + '<testcase classname="independent.Controls" name="other()"><error/></testcase>'),
                ((3, 0, 0, 0), owner + failure))):
            directory = self.root / f"junit-{index}"
            directory.mkdir()
            report = reports / "TEST-independent.Controls.xml"
            attributes = ' '.join(f'{name}="{value}"' for name, value in zip(("tests", "failures", "errors", "skipped"), counts))
            report.write_text('<testsuite ' + attributes + '>' + body + '</testsuite>', encoding="utf-8")
            PAIRED.preserve_test_reports(self.root, directory, gate)
            self.assertEqual((directory / "junit" / report.name).read_bytes(), report.read_bytes())
            if index == 0:
                PAIRED.verify_test_gate(directory, gate)
                verified = PAIRED.read_json(directory / "junit-verified.json")
                self.assertEqual(verified["required_tests"], gate["required_tests"])
                self.assertEqual(verified["reports"][0]["sha256"], PAIRED.digest(report))
                self.assertEqual((directory / "junit" / report.name).read_bytes(), report.read_bytes())
            else:
                with self.subTest(index=index), self.assertRaises(ValueError):
                    PAIRED.verify_test_gate(directory, gate)
                self.assertFalse((directory / "junit-verified.json").exists())
                self.assertTrue((directory / "junit-inventory.json").is_file())

    def preflight_fixture(self, name):
        """Model independently retained class loads and OS generations for source-only rejection checks."""
        output = self.root / name
        output.mkdir()
        original = output / "original.jar"
        names = ["independent.Controller", "independent.Runtime", "dev.s7a.strata.performance.JvmPerformanceMeter", "org.openjdk.jmh.runner.Runner"]
        with zipfile.ZipFile(original, "x") as archive:
            for item in names:
                archive.writestr(item.replace(".", "/") + ".class", b"source-only class identity fixture")
        runtime = {project: PAIRED.preserve_file(original, output / "prepared" / project.strip(":").replace(":", "-") / "target.jar")
                   for project in self.manifest["runtime_projects"]}
        retained = output / "classpath"
        item = {"source": str(original), **PAIRED.preserve_file(original, retained / "classpath-0.jar")}
        PAIRED.write_new(retained / "classpath.json", {"schema": "strata-paired-jvm-classpath-v1", "entries": [item]})
        suite = copy.deepcopy(self.manifest["suites"][0])
        suite["preflight"] = {"main": names[0], "qualification": self.pin,
                              "runtime_classes": {project: names[1] for project in runtime},
                              "controls": [{"id": "independent-a", "arguments": []}, {"id": "independent-b", "arguments": []}]}
        inputs = {"input": PAIRED.preserve_file(self.input, output / "inputs" / "input.bin")}
        directory = PAIRED.preflight_request(self.root, output, suite, "baseline", retained / "classpath.json", runtime, {}, {}, inputs)
        request = PAIRED.read_json(directory / "request.json")
        controls = []
        for control in suite["preflight"]["controls"]:
            artifact = directory / (control["id"] + ".txt")
            artifact.write_bytes(b"actual observation fixture")
            controls.append({"id": control["id"], "status": "passed", "observations": {"entries": 3},
                             "origins": {item: {"pid": 123} for item in names},
                             "artifacts": [{"path": artifact.name, "sha256": PAIRED.digest(artifact)}]})
        receipt = {"schema": "strata-paired-jvm-preflight-receipt-v1", "status": "passed",
                   "request_sha256": PAIRED.digest(directory / "request.json"), "controls": controls}
        phase = output / "phase"
        phase.mkdir()
        (phase / "class-loads").mkdir()
        (phase / "console.log").write_text("".join(f"[123][info][class,load] {item} source: {Path(request['required_classes'][item]['path']).as_uri()}\n" for item in names), encoding="utf-8")
        PAIRED.write_new(phase / "vms.json", [{"pid": 123, "vm_start_ms": 1001, "role": "java_exec", "home": str(output / "jdk")}])
        terminal = {"inheritedAffinityMask": 15, "systemAffinityMask": 15,
                    "members": {"123": {"inOwnedJob": True, "completed": True, "exitCode": 0,
                                        "affinityMask": 15, "systemAffinityMask": 15, "image": str(output / "jdk/bin/java.exe"),
                                        "creationFileTime": 116444736010000000}}}
        return directory, phase, terminal, receipt

    def test_complete_preflight_rejects_missing_failed_changed_or_wrong_origin_controls(self):
        def replace_request(directory, phase, terminal, receipt):
            request = PAIRED.read_json(directory / "request.json")
            request["side"] = "candidate"
            (directory / "request.json").write_text(json.dumps(request), encoding="utf-8")
            receipt["request_sha256"] = PAIRED.digest(directory / "request.json")

        changes = [lambda directory, phase, terminal, receipt: None,
                   lambda directory, phase, terminal, receipt: receipt["controls"].pop(),
                   lambda directory, phase, terminal, receipt: receipt["controls"].reverse(),
                   lambda directory, phase, terminal, receipt: receipt["controls"][0].update(status="failed"),
                   lambda directory, phase, terminal, receipt: receipt["controls"][0].update(observations={}),
                   lambda directory, phase, terminal, receipt: receipt.update(request_sha256="0" * 64),
                   lambda directory, phase, terminal, receipt: receipt["controls"][0]["origins"].pop("independent.Runtime"),
                   lambda directory, phase, terminal, receipt: receipt["controls"][0]["origins"]["independent.Runtime"].update(pid=999),
                   lambda directory, phase, terminal, receipt: terminal["members"]["123"].update(completed=False),
                   lambda directory, phase, terminal, receipt: terminal["members"]["123"].update(creationFileTime=116444736100000000),
                   lambda directory, phase, terminal, receipt: (phase / "console.log").write_text("missing class origins\n"),
                   lambda directory, phase, terminal, receipt: (directory / "independent-a.txt").write_bytes(b"changed observation"),
                   lambda directory, phase, terminal, receipt: receipt["controls"][0]["artifacts"][0].update(path="../outside"),
                   lambda directory, phase, terminal, receipt: (directory / "qualification.json").write_bytes(b"changed qualification"),
                   replace_request]
        for index, change in enumerate(changes):
            with self.subTest(change=index):
                directory, phase, terminal, receipt = self.preflight_fixture(f"control-{index}")
                request_hash = PAIRED.digest(directory / "request.json")
                change(directory, phase, terminal, receipt)
                PAIRED.write_new(directory / "receipt.json", receipt)
                if index == 0:
                    PAIRED.verify_preflight(directory, phase, terminal, request_hash)
                    self.assertEqual(PAIRED.read_json(directory / "verified.json")["controls"], ["independent-a", "independent-b"])
                else:
                    with self.assertRaises(ValueError):
                        PAIRED.verify_preflight(directory, phase, terminal, request_hash)
                    self.assertFalse((directory / "verified.json").exists())

    def test_preflight_class_files_preserve_pins_and_independent_origin_corroboration(self):
        for mode in ("corroborated", "file-only", "changed-file", "removed-file", "wrong-origin", "duplicate-observation"):
            with self.subTest(mode=mode):
                directory, phase, terminal, receipt = self.preflight_fixture("class-file-" + mode)
                console = phase / "console.log"
                observed = console.read_text(encoding="utf-8")
                diagnostic = phase / "class-loads/classes-123-2026-10-12_12-00-00.log"
                diagnostic.write_text(observed, encoding="utf-8")
                vms = PAIRED.read_json(phase / "vms.json")
                vms[0]["class_load_diagnostic"] = {"path": str(diagnostic), "sha256": PAIRED.digest(diagnostic)}
                (phase / "vms.json").write_text(json.dumps(vms), encoding="utf-8")
                PAIRED.write_new(directory / "receipt.json", receipt)
                if mode == "file-only":
                    console.write_text("", encoding="utf-8")
                elif mode == "changed-file":
                    diagnostic.write_text(observed + "[123][info][class,load] extra.Class source: shared objects file\n", encoding="utf-8")
                elif mode == "removed-file":
                    diagnostic.unlink()
                elif mode == "wrong-origin":
                    wrong = (phase / "different.jar").as_uri()
                    console.write_text(re.sub(r"source: [^\n]+", "source: " + wrong, observed), encoding="utf-8")
                elif mode == "duplicate-observation":
                    console.write_text(observed + observed, encoding="utf-8")
                request_hash = PAIRED.digest(directory / "request.json")
                if mode in ("corroborated", "file-only"):
                    PAIRED.verify_preflight(directory, phase, terminal, request_hash)
                    self.assertEqual(PAIRED.read_json(directory / "verified.json")["controls"], ["independent-a", "independent-b"])
                else:
                    message = "captured class-load diagnostic" if mode in ("changed-file", "removed-file") else "ambiguous PID-qualified"
                    with self.assertRaisesRegex(ValueError, message):
                        PAIRED.verify_preflight(directory, phase, terminal, request_hash)
                    self.assertFalse((directory / "verified.json").exists())

    def test_prepared_bytes_survive_first_control_failure_and_cannot_be_rehashed(self):
        directory, phase, terminal, receipt = self.preflight_fixture("failed-control")
        root = directory.parents[2]
        inventory = root / "classpath/classpath.json"
        PAIRED.prepared_classpath(inventory)
        (root / "classpath/classpath-0.jar").write_bytes(b"altered prepared bytes")
        with self.assertRaises(ValueError):
            PAIRED.prepared_classpath(inventory)
        receipt["controls"][0]["status"] = "failed"
        PAIRED.write_new(directory / "receipt.json", receipt)
        with self.assertRaises(ValueError):
            PAIRED.verify_preflight(directory, phase, terminal, PAIRED.digest(directory / "request.json"))
        with zipfile.ZipFile(PAIRED.pack(root)) as archive:
            self.assertIn("prepared/api/target.jar", archive.namelist())
            self.assertIn("classpath/classpath-0.jar", archive.namelist())
            self.assertIn("preflight/arbitrary-new-fixture/baseline/receipt.json", archive.namelist())

    def test_directory_pins_use_jvm_case_sensitive_utf8_entry_order_on_windows(self):
        tree = self.root / "compiled-tree"
        files = {"META-INF/fixture.kotlin_module": b"module", "dev/example/Fixture.class": b"compiled class", "z-resource.txt": b"asset"}
        for name, value in files.items():
            path = tree.joinpath(*name.split("/"))
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(value)
        # JVM String order places uppercase META-INF before lowercase packages on every host.
        expected = hashlib.sha256()
        for name in ("META-INF/fixture.kotlin_module", "dev/example/Fixture.class", "z-resource.txt"):
            expected.update(f"{name}={hashlib.sha256(files[name]).hexdigest()}\n".encode("utf-8"))
        self.assertEqual(PAIRED.path_identity(tree), expected.hexdigest())

    def test_qualification_upload_keeps_attempt_receipts_without_reusable_workspaces(self):
        output = self.root / "qualification"
        workspace = output / "complete/workspaces/fixture"
        workspace.mkdir(parents=True)
        (workspace / "cache.bin").write_bytes(b"reusable intermediate")
        PAIRED.write_new(output / "result.json", {"status": "failed"})
        attempt = output / "complete.zip"
        with zipfile.ZipFile(attempt, "x") as archive:
            archive.writestr("attempt.json", '{"status":"failed"}')
            archive.writestr("prepared/target-0.jar", b"actual early runtime bytes")
        with zipfile.ZipFile(QUALIFICATION.pack_qualification(output)) as retained:
            self.assertEqual(set(retained.namelist()), {"result.json", "complete.zip"})
            self.assertEqual(retained.read("complete.zip"), attempt.read_bytes())

    def test_directory_pins_order_supplementary_names_like_java_utf16_strings(self):
        tree = self.root / "unicode-tree"
        tree.mkdir()
        supplementary = "\U00010000-resource.bin"
        high_bmp = "\ue000-resource.bin"
        (tree / supplementary).write_bytes(b"supplementary")
        (tree / high_bmp).write_bytes(b"high BMP")
        # The supplementary surrogate D800 sorts before E000 in Java, despite its larger Unicode scalar.
        expected = hashlib.sha256()
        for name, value in ((supplementary, b"supplementary"), (high_bmp, b"high BMP")):
            expected.update(f"{name}={hashlib.sha256(value).hexdigest()}\n".encode("utf-8"))
        self.assertEqual(PAIRED.path_identity(tree), expected.hexdigest())

    def test_paths_and_json_cannot_escape_or_override_pins(self):
        for value in ("../outside", "C:/outside", "a\\b", "/outside", "a/../b", "./a", ".git/index", "a\nb", "a//b"):
            with self.subTest(path=value), self.assertRaises(ValueError):
                PAIRED.relative_path(self.root, value)
        path = self.root / "bad.json"
        for value in ('{"schema":1,"schema":2}', '{"count":NaN}'):
            path.write_text(value, encoding="utf-8")
            with self.assertRaises(ValueError):
                PAIRED.read_json(path)

    def test_table_preserves_processor_secondary_units_without_reaggregating(self):
        comparison = {"cases": [{"benchmark": "example.Fixture.operation", "params": {}, "primary_unit": "us/op",
                                  "metrics": {"primary_score_median": {"baseline": 1.0, "candidate": 2.0, "delta": 1.0, "candidate_to_baseline": 2.0}},
                                  "secondary_metrics": {"unihex.processCpu": {"unit": "ns/op", "baseline": 10.0, "candidate": 6.0, "delta": -4.0, "candidate_to_baseline": 0.6}}}]}
        PAIRED.table(self.root, [("complete", "avgt", comparison)], [])
        with (self.root / "table.csv").open(encoding="utf-8", newline="") as source:
            rows = list(csv.DictReader(source))
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[1]["metric"], "jmh_secondary.unihex.processCpu")
        self.assertEqual(rows[1]["unit"], "ns/op")
        self.assertEqual(rows[1]["delta"], "-4.0")
        self.assertIn("ns/op", (self.root / "report.md").read_text(encoding="utf-8"))

    def test_preserve_failed_attempt_and_every_hidden_evidence_byte(self):
        output = self.root / "attempt"
        output.mkdir()
        PAIRED.write_new(output / "attempt.json", {"status": "failed"})
        (output / ".hidden-provenance").write_bytes(b"actual bytes")
        intermediate = output / "workspaces"
        intermediate.mkdir()
        (intermediate / "cache.bin").write_bytes(b"cache")
        retained = output / "raw/workspaces"
        retained.mkdir(parents=True)
        (retained / "receipt.json").write_bytes(b"complete suite evidence")
        archive = PAIRED.pack(output)
        with zipfile.ZipFile(archive) as saved:
            self.assertEqual(set(saved.namelist()), {"attempt.json", ".hidden-provenance", "raw/workspaces/receipt.json"})
            self.assertEqual(saved.read(".hidden-provenance"), b"actual bytes")
            self.assertEqual(saved.read("raw/workspaces/receipt.json"), b"complete suite evidence")
        with self.assertRaises(FileExistsError):
            PAIRED.write_new(output / "attempt.json", {"status": "passed"})
        with self.assertRaises(FileExistsError):
            PAIRED.pack(output)

    def test_frozen_crlf_blob_retains_raw_bytes_and_rejects_checkout_or_index_changes(self):
        repository = self.root / "raw-checkout"
        subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
        PAIRED.git(repository, "config", "core.autocrlf", "false")
        (repository / ".gitattributes").write_bytes(b"* text eol=crlf\n")
        batch = repository / "gradlew.bat"
        original = b"@echo off\r\necho frozen\r\n"
        batch.write_bytes(original)
        (repository / "provider").write_bytes(b"fixture.Provider\n")
        # Model an older published CRLF blob without normalizing it while constructing the index.
        for name in (".gitattributes", "gradlew.bat", "provider"):
            blob = PAIRED.git(repository, "hash-object", "-w", "--no-filters", "--", name)
            PAIRED.git(repository, "update-index", "--add", "--cacheinfo", f"100644,{blob},{name}")
        tree = PAIRED.git(repository, "write-tree")
        # A synthetic fixture object avoids invoking any commit/signing machinery in this test.
        commit = subprocess.check_output(
            ["git", "-C", str(repository), "hash-object", "-w", "-t", "commit", "--stdin"],
            input=f"tree {tree}\nauthor Fixture <fixture@example.invalid> 0 +0000\ncommitter Fixture <fixture@example.invalid> 0 +0000\n\nFreeze synthetic source fixture\n".encode(),
        ).decode().strip()
        output = self.root / "materialized"
        output.mkdir()
        repository, source = PAIRED.materialize(repository, output, "fixture", {"ref": commit, "tree": tree, "patches": []})
        batch = repository / "gradlew.bat"
        attributes = repository / ".git/info/attributes"
        PAIRED.source_guard(repository, tree)
        self.assertEqual(batch.read_bytes(), original)
        self.assertEqual((repository / "provider").read_bytes(), b"fixture.Provider\n")
        self.assertEqual(source["raw_checkout_attributes_sha256"], PAIRED.digest(attributes))
        batch.write_bytes(original.replace(b"\r\n", b"\n"))
        with self.assertRaises(subprocess.CalledProcessError):
            PAIRED.source_guard(repository, tree)
        attributes.write_bytes(b"* text\n")
        with self.assertRaisesRegex(ValueError, "Frozen checkout attributes changed"):
            PAIRED.source_guard(repository, tree)
        attributes.write_bytes(b"* -text\n")
        PAIRED.git(repository, "add", "gradlew.bat")
        with self.assertRaisesRegex(ValueError, "Frozen source index changed"):
            PAIRED.source_guard(repository, tree)

    def test_properties_preserve_colons_and_windows_paths(self):
        output = self.root / "runtime.properties"
        PAIRED.properties(output, {":runtime:core": r"C:\owned source\core.jar"})
        self.assertEqual(output.read_text(encoding="utf-8"), "\\:runtime\\:core=C\\:\\\\owned\\ source\\\\core.jar\n")

    def test_actual_fork_requires_terminal_generation_caps_and_unchanged_affinity(self):
        home = self.root / "jdk"
        executable = home / "bin/java.exe"
        executable.parent.mkdir(parents=True)
        executable.write_bytes(b"frozen executable")
        allowed = {os.path.normcase(str(executable)): PAIRED.digest(executable)}
        member = {"inOwnedJob": True, "completed": True, "exitCode": 0, "creationFileTime": 116444736000000000 + 10000000,
                  "image": str(executable), "affinityMask": 15, "systemAffinityMask": 15}
        terminal = {"members": {"123": member}, "inheritedAffinityMask": 15, "systemAffinityMask": 15}
        document = ("<hotspot_log process='123' time_ms='1001'>\n<vm_arguments><command>org.openjdk.jmh.runner.ForkedMain 1</command>\n"
                    f"java.home={home}\njava.class.path=frozen.jar\n</vm_arguments>\n"
                    "-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n<hotspot_log_done stamp='1'/>\n</hotspot_log>\n")
        changes = [lambda value: None, lambda value: value["members"]["123"].update(completed=False),
                   lambda value: value["members"]["123"].update(exitCode=1), lambda value: value["members"]["123"].update(affinityMask=3),
                   lambda value: value["members"]["123"].update(creationFileTime=116444736100000000), lambda value: value["members"].clear(), lambda value: None, lambda value: None]
        for index, change in enumerate(changes):
            directory = self.root / str(index)
            directory.mkdir()
            (directory / "console.log").write_text("", encoding="utf-8")
            (directory / "class-loads").mkdir()
            (directory / "class-loads/classes-123-2026-10-12_00-00-00.log").write_text("[123][info][class,load] org.openjdk.jmh.runner.ForkedMain source: file:/frozen.jar\n", encoding="utf-8")
            log = directory / "vm.log"
            log.write_text(document, encoding="utf-8")
            actual = copy.deepcopy(terminal)
            change(actual)
            if index == 6:
                (directory / "class-loads/classes-123-2026-10-12_00-00-00.log").unlink()
                (directory / "console.log").write_text("[123][info][class,load] org.openjdk.jmh.runner.ForkedMain source: file:/frozen.jar\n", encoding="utf-8")
            if index == 7:
                (directory / "class-loads/classes-456-2026-10-12_00-00-00.log").write_text("[456][info][class,load] unbound.Class source: file:/frozen.jar\n", encoding="utf-8")
            if index == 0:
                PAIRED.verify_vms(directory, [log], actual, allowed, 1)
                self.assertTrue((directory / "vms.json").is_file())
            else:
                with self.assertRaises(ValueError):
                    PAIRED.verify_vms(directory, [log], actual, allowed, 1)

    def test_jmh_version_probes_require_exact_arguments_caps_and_bound_compiler_hints(self):
        home = self.root / "jdk"
        executable = home / "bin/java.exe"
        executable.parent.mkdir(parents=True)
        executable.write_bytes(b"frozen executable")
        allowed = {os.path.normcase(str(executable)): PAIRED.digest(executable)}
        environment = "-Xmx32m -XX:MaxMetaspaceSize=256m -XX:+UnlockDiagnosticVMOptions -XX:+PrintCommandLineFlags -XX:-DisplayVMOutput -XX:+LogVMOutput -XX:LogFile=owned/vm-%p-%t.log"
        inherited = "-XX:+UnlockExperimentalVMOptions -XX:CompileCommand=blackhole,some/fake/Class.method"
        document = ("<hotspot_log process='123' time_ms='1001'>\n<vm_arguments><args>\n"
                    f"{environment} {{options}} </args>\n<launcher>SUN_STANDARD</launcher>\n<properties>\n"
                    f"java.home={home}\njava.class.path=\n</properties></vm_arguments>\n"
                    "-XX:MaxHeapSize=33554432 -XX:MaxMetaspaceSize=268435456\n<hotspot_log_done stamp='1'/>\n</hotspot_log>\n")
        authority = ("<hotspot_log process='456' time_ms='1000'>\n<vm_arguments><command>fixture.Collector</command>\n"
                     f"java.home={home}\njava.class.path=frozen.jar\n</vm_arguments>\n"
                     "-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n<hotspot_log_done stamp='1'/>\n</hotspot_log>\n")
        origin = {"main": "fixture.Collector", "executable": str(executable), "classpath": "frozen.jar"}
        console = "paired.jvm.origin " + json.dumps(origin) + "\n[456][info][class,load] org.openjdk.jmh.runner.CompilerHints source: file:/frozen.jar\n"
        changes = [lambda value: value, lambda value: value.replace("-XX:MaxHeapSize=33554432", "-XX:MaxHeapSize=4293590016"),
                   lambda value: value.replace("Class.method", "Other.method"), lambda value: value.replace("<launcher>", "<command></command><launcher>"),
                   lambda value: value.replace("java.class.path=\n", "java.class.path=unknown.jar\n")]
        for index, change in enumerate(changes + [lambda value: value, lambda value: value]):
            for quiet in ("", "-XX:CompileCommand=quiet "):
                with self.subTest(change=index, quiet=quiet):
                    directory = self.root / f"probe-{index}-{bool(quiet)}"
                    directory.mkdir()
                    PAIRED.write_new(directory / "request.json", {"environment": {"JAVA_TOOL_OPTIONS": environment}})
                    actual_console = console if index != 5 else console.replace("[456]", "[999]")
                    (directory / "console.log").write_text(actual_console.split("\n", 1)[0] + "\n", encoding="utf-8")
                    (directory / "class-loads").mkdir()
                    (directory / "class-loads/classes-456-2026-10-12_00-00-00.log").write_text(actual_console.split("\n", 1)[1], encoding="utf-8")
                    probe = directory / "probe.log"
                    probe.write_text(change(document.replace("{options}", inherited.replace("-XX:CompileCommand=blackhole", quiet + "-XX:CompileCommand=blackhole"))), encoding="utf-8")
                    collector = directory / "collector.log"
                    collector.write_text(authority if index != 6 else authority.replace("fixture.Collector", "fixture.Unknown"), encoding="utf-8")
                    if index == 0:
                        PAIRED.verify_vms(directory, [probe, collector], {"members": {}}, allowed, 0)
                        evidence = PAIRED.read_json(directory / "vms.json")
                        self.assertEqual(evidence[0]["role"], "probe")
                        self.assertIsNone(evidence[0]["command"])
                        self.assertEqual(evidence[0]["classpath"], "")
                        self.assertEqual(evidence[1]["role"], "java_exec")
                        (directory / "vms.json").unlink()
                        with self.assertRaisesRegex(ValueError, "Incomplete actual JMH fork"):
                            PAIRED.verify_vms(directory, [probe, collector], {"members": {}}, allowed, 1)
                    else:
                        with self.assertRaises(ValueError):
                            PAIRED.verify_vms(directory, [probe, collector], {"members": {}}, allowed, 0)

    def test_fixed_class_load_staging_retains_only_fresh_phase_bytes_and_rejects_previous_mutation(self):
        for mode in ("complete", "partial", "changed-previous", "deleted-previous"):
            with self.subTest(mode=mode):
                owner = self.root / ("staging-" + mode)
                owner.mkdir()
                old = owner / "classes-123-2026-10-12_00-00-00.log"
                old.write_text("[123][info][class,load] previous.Class source: file:/previous.jar\n", encoding="utf-8")
                previous = PAIRED.class_load_inventory(owner)
                fresh = owner / "classes-123-2026-10-12_00-00-01.log"
                line = "[123][info][class,load] fresh.Class source: file:/fresh.jar\n"
                fresh.write_text(line.rstrip() if mode == "partial" else line, encoding="utf-8")
                directory = self.root / ("phase-" + mode)
                (directory / "class-loads").mkdir(parents=True)
                if mode == "changed-previous":
                    old.write_bytes(b"changed previous bytes")
                elif mode == "deleted-previous":
                    old.unlink()
                if mode in ("changed-previous", "deleted-previous"):
                    with self.assertRaisesRegex(ValueError, "previous class-load diagnostic"):
                        PAIRED.preserve_class_loads(owner, directory, previous)
                    self.assertFalse((directory / "class-load-inventory.json").exists())
                else:
                    captured = PAIRED.preserve_class_loads(owner, directory, previous)
                    retained = directory / "class-loads" / fresh.name
                    self.assertEqual(retained.read_bytes(), fresh.read_bytes())
                    self.assertEqual(list((directory / "class-loads").iterdir()), [retained])
                    self.assertEqual(PAIRED.class_load_inventory(owner)[old.name], previous[old.name])
                    capture = PAIRED.read_json(directory / "class-load-inventory.json")
                    self.assertEqual(capture["owner"], str(owner))
                    self.assertEqual(capture["copied"][0]["sha256"], PAIRED.digest(retained))
                    if mode == "partial":
                        with self.assertRaisesRegex(ValueError, "truncated class-load diagnostic"):
                            PAIRED.class_loads(directory)
                    else:
                        observed, identities = PAIRED.class_loads(directory)
                        self.assertEqual(observed, line)
                        self.assertEqual(set(identities), {123})
                        self.assertEqual(PAIRED.class_load_inventory(owner, captured), captured)
                        fresh.write_bytes(b"changed between phases")
                        with self.assertRaisesRegex(ValueError, "captured class-load staging bytes"):
                            PAIRED.class_load_inventory(owner, captured)
                        fresh.unlink()
                        with self.assertRaisesRegex(ValueError, "captured class-load staging bytes"):
                            PAIRED.class_load_inventory(owner, captured)
                        self.assertEqual(retained.read_text(encoding="utf-8"), line)

    def test_whole_class_load_inventory_exceeds_console_budget_without_truncating_each_vm(self):
        directory = self.root / "whole-diagnostics"
        directory.mkdir()
        owner = directory / "class-loads"
        owner.mkdir()
        for pid in range(1, 49):
            record = f"[{pid}][info][class,load] fixture.Complete source: file:/frozen-runtime.jar\n"
            (owner / f"classes-{pid}-2026-10-12_00-00-00.log").write_text(record * 7000, encoding="utf-8")
        text, identities = PAIRED.class_loads(directory)
        self.assertGreater(len(text), 16 * 1024**2)
        self.assertEqual(set(identities), set(range(1, 49)))
        self.assertEqual(text.count("fixture.Complete source:"), 48 * 7000)

    def test_class_load_inventory_rejects_missing_duplicate_wrong_pid_rotation_and_truncation(self):
        line = "[123][info][class,load] fixture.Complete source: file:/frozen-runtime.jar\n"
        changes = [lambda path: path.unlink(), lambda path: path.write_text(line.replace("[123]", "[999]")),
                   lambda path: path.write_text(line.rstrip()), lambda path: path.write_text(line * 20000),
                   lambda path: path.with_name("classes-123-2026-10-12_00-00-01.log").write_text(line),
                   lambda path: path.with_name(path.name + ".0").write_text(line)]
        for index, change in enumerate(changes):
            directory = self.root / f"changed-class-load-{index}"
            owner = directory / "class-loads"
            owner.mkdir(parents=True)
            path = owner / "classes-123-2026-10-12_00-00-00.log"
            path.write_text(line, encoding="utf-8")
            change(path)
            if index == 0:
                text, identities = PAIRED.class_loads(directory)
                self.assertEqual((text, identities), ("", {}))
            else:
                with self.assertRaises(ValueError):
                    PAIRED.class_loads(directory)
        with self.assertRaisesRegex(ValueError, "Missing owned class-load directory"):
            PAIRED.class_loads(self.root / "missing-phase")

    def test_early_setup_failure_preserves_failed_attempt_without_java(self):
        output = self.root / "early-failure"
        manifest = self.root / "manifest.json"
        manifest.write_text("{}", encoding="utf-8")
        with patch.object(PAIRED, "os") as platform, patch.object(PAIRED, "git", return_value="a" * 40):
            platform.name = "nt"
            platform.environ = {}
            with self.assertRaises(KeyError):
                PAIRED.run(self.root, manifest, output)
        self.assertEqual(PAIRED.read_json(output / "attempt.json")["status"], "failed")
        self.assertTrue(output.with_suffix(".zip").is_file())

    def test_workflow_result_accepts_runner_omitted_empty_opt_in_output(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/jvm.yml").read_text(encoding="utf-8")
        script = textwrap.dedent(re.search(r"(?ms)^\s+python3 - <<'PY'\n(.*?)^\s+PY\n", workflow)[1])
        outputs = {key: "false" for key in ("common", "web", "docs", "docs_full", "workflow", "all_minecraft")}
        outputs.update(minecraft="[]", paired_manifest="")
        # The runner omits empty outputs before exposing needs.plan.outputs to the aggregation job.
        exposed = {key: value for key, value in outputs.items() if value}
        jobs = {key: {"result": "skipped"} for key in ("paired-jvm", "preflight-qualification", "checks", "web-demos", "web", "workflow-checks", "minecraft-matrix", "minecraft", "documentation", "docs")}
        jobs["plan"] = {"result": "success", "outputs": exposed}
        with patch.dict(os.environ, {"RESULTS": json.dumps(jobs)}):
            exec(compile(script, "jvm-workflow-result", "exec"), {})
        exposed["paired_manifest"] = ".github/performance/pilot-issue145/manifest.json"
        with patch.dict(os.environ, {"RESULTS": json.dumps(jobs)}), self.assertRaises(AssertionError):
            exec(compile(script, "jvm-workflow-result", "exec"), {})
        jobs["paired-jvm"]["result"] = "success"
        with patch.dict(os.environ, {"RESULTS": json.dumps(jobs)}):
            exec(compile(script, "jvm-workflow-result", "exec"), {})


if __name__ == "__main__":
    unittest.main()
