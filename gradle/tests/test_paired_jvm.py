"""Exercise generic manifest selection and immutable evidence boundaries without Java or timing."""
from contextlib import ExitStack, redirect_stdout
import base64
import copy
import csv
import hashlib
import importlib.util
import io
import json
import lzma
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import zipfile


SPEC = importlib.util.spec_from_file_location("paired_jvm", Path(__file__).resolve().parents[1] / "performance/paired-jvm.py")
PAIRED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAIRED)
QUALIFICATION_SPEC = importlib.util.spec_from_file_location("qualified_preflight", Path(__file__).resolve().parents[1] / "performance/verify-qualified-preflight.py")
QUALIFICATION = importlib.util.module_from_spec(QUALIFICATION_SPEC)
QUALIFICATION_SPEC.loader.exec_module(QUALIFICATION)


class PairedJvmTest(unittest.TestCase):
    def test_owner_continuation_keeps_the_complete_declared_fixture_closure(self):
        data = {"suites": [{"project": ":quality:benchmarks"}],
                "compiled_pins": {"baseline": [], "candidate": [], "fixture": [{"path": "quality/remote-benchmarks/build/classes/kotlin/jmh/example/Complete.class", "sha256": "a" * 64}]}}
        self.assertEqual(PAIRED.fixture_projects(data), [":quality:benchmarks", ":quality:remote-benchmarks"])
        data["suites"] = []
        self.assertEqual(PAIRED.fixture_projects(data), [":quality:remote-benchmarks"])
        data["compiled_pins"]["fixture"] = [{"path": "runtime/velocity/build/classes/kotlin/test/example/Complete.class", "sha256": "a" * 64}]
        self.assertEqual(PAIRED.fixture_projects(data), [])

    def test_runtime_side_builds_declared_fixture_pins_without_changing_the_inventory(self):
        tasks = [":performance-testkit:jvmJar", ":quality:remote-benchmarks:jmhCompileGeneratedClasses", ":runtime:velocity:testClasses"]
        paths = ["performance-testkit/build/libs/performance-testkit-0.2.2.jar", "quality/remote-benchmarks/build/classes/kotlin/jmh/example/Complete.class",
                 "runtime/velocity/build/classes/kotlin/test/example/CompleteProbe.class"]
        data = {"compiled_pins": {"baseline": [{"path": path, "sha256": "a" * 64} for path in paths]}}
        original = copy.deepcopy(data)
        self.assertEqual(PAIRED.pinned_fixture_preparation(data, "baseline", tasks), tasks)
        self.assertEqual(data, original)
        data["compiled_pins"]["baseline"] = [{"path": "runtime/velocity/build/libs/velocity-0.2.2.jar", "sha256": "a" * 64}]
        self.assertEqual(PAIRED.pinned_fixture_preparation(data, "baseline", tasks), [])
        data["compiled_pins"]["baseline"] = [{"path": paths[0], "sha256": "a" * 64}]
        self.assertEqual(PAIRED.pinned_fixture_preparation(data, "baseline", tasks), tasks[:1])

    """Invalid manifests cannot weaken the declared complete three-repetition collection contract."""

    def test_failed_owned_jvm_reports_are_fresh_bounded_sanitized_pins_without_admission(self):
        workspace, home, output = self.root / "workspace", self.root / "gradle-home", self.root / "failure"
        workspace.mkdir()
        output.mkdir()
        wrapper = Path(PAIRED.__file__).resolve().parents[2] / "gradle/wrapper/gradle-wrapper.properties"
        version = re.search(r"(?m)^distributionUrl=.*?/gradle-([^/]+?)-(?:bin|all)\.zip\s*$", wrapper.read_text(encoding="utf-8"))[1]
        daemon = home / "daemon" / version
        daemon.mkdir(parents=True)
        now = 1700000000000000000
        born = 116444736000000000 + (now - 1000000000) // 100
        terminal = {"nativeInvoked": True, "members": {"7240": [{"pid": 7240, "creationFileTime": born,
            "exitFileTime": born + 20000000, "image": str(workspace / "java.exe"), "inOwnedJob": True,
            "completed": True, "handleClosed": True, "exitCode": 1}]}}
        crash = daemon / "hs_err_pid7240.log"
        raw = b"# fatal error, pid=7240\n# Problematic frame: jvm.dll+0x123\nCommand Line: credential=keep-private\nNative frames:\nV jvm.dll\nRegisters:\nprivate memory\nEnvironment Variables:\nTOKEN=keep-private\n"
        crash.write_bytes(raw)
        os.utime(crash, ns=(now, now))
        daemon_log = daemon / "daemon-7240.out.log"
        log = b"x\n" * (1024**2 // 2 + 100) + b"daemon stopped keep-private\nAuthorization: encoded-private\n"
        daemon_log.write_bytes(log)
        os.utime(daemon_log, ns=(now, now))
        before = copy.deepcopy(terminal)
        result = PAIRED.preserve_failed_jvm_diagnostics(output, workspace, terminal,
                                                      {"GRADLE_USER_HOME": str(home), "GITHUB_TOKEN": "keep-private"})
        self.assertFalse(result["qualified"])
        self.assertEqual(result["known_failed_jvm_generations"], 1)
        self.assertEqual(len(result["entries"]), 2)
        self.assertEqual(terminal, before)
        for entry in result["entries"]:
            data = (output / entry["retained_path"]).read_bytes()
            self.assertFalse(entry["qualified"])
            self.assertEqual(entry["retained_sha256"], hashlib.sha256(data).hexdigest())
            self.assertLessEqual(entry["retained_bytes"], 1024**2)
            self.assertNotIn(b"keep-private", data)
            self.assertNotIn(b"encoded-private", data)
            if entry["source_relative"].endswith(crash.name):
                self.assertEqual(entry["source_file_sha256"], hashlib.sha256(raw).hexdigest())
                self.assertIn(b"Problematic frame", data)
                self.assertNotIn(b"private memory", data)
            else:
                self.assertIsNone(entry["source_file_sha256"])
                self.assertEqual(entry["source_range_sha256"], hashlib.sha256(log[-1024**2:]).hexdigest())
        self.assertLessEqual(result["read_bytes"], 4 * 1024**2)
        self.assertLessEqual(result["retained_bytes"], 4 * 1024**2)
        os.utime(crash, ns=(now - 3000000000, now - 3000000000))
        os.utime(daemon_log, ns=(now - 3000000000, now - 3000000000))
        stale = self.root / "stale"
        stale.mkdir()
        self.assertEqual(PAIRED.preserve_failed_jvm_diagnostics(stale, workspace, terminal, {"GRADLE_USER_HOME": str(home)})["entries"], [])
        os.utime(crash, ns=(now, now))
        crash.write_bytes(b"# fatal error, pid=9999\n")
        os.utime(crash, ns=(now, now))
        mismatch = self.root / "mismatch"
        mismatch.mkdir()
        self.assertEqual(PAIRED.preserve_failed_jvm_diagnostics(mismatch, workspace, terminal, {"GRADLE_USER_HOME": str(home)})["entries"], [])
        crash.write_bytes(raw)
        os.utime(crash, ns=(now, now))
        escaped = self.root / "escaped"
        escaped.mkdir()
        outside = self.root / "outside-private.log"
        real_resolve = Path.resolve
        with patch.object(Path, "resolve", lambda path, *a, **kw: outside if path == crash else real_resolve(path, *a, **kw)):
            refused_path = PAIRED.preserve_failed_jvm_diagnostics(escaped, workspace, terminal, {"GRADLE_USER_HOME": str(home)})
        self.assertEqual(refused_path["entries"], [])
        self.assertEqual(refused_path["read_bytes"], 0)
        many = {"nativeInvoked": True, "members": {}}
        for pid in (7241, 7242, 7243):
            member = {**terminal["members"]["7240"][0], "pid": pid}
            many["members"][str(pid)] = [member]
            for target, data in ((workspace / f"hs_err_pid{pid}.log", f"# fatal error, pid={pid}\n".encode() + b"frame\n" * (1024**2 // 6 + 10)),
                                 (daemon / f"daemon-{pid}.out.log", log)):
                target.write_bytes(data)
                os.utime(target, ns=(now, now))
        bounded = self.root / "bounded"
        bounded.mkdir()
        aggregate = PAIRED.preserve_failed_jvm_diagnostics(bounded, workspace, many, {"GRADLE_USER_HOME": str(home), "GITHUB_TOKEN": "keep-private"})
        self.assertLessEqual(aggregate["read_bytes"], 4 * 1024**2)
        self.assertLessEqual(aggregate["retained_bytes"], 4 * 1024**2)
        self.assertTrue(aggregate["truncated"])
        unowned = copy.deepcopy(terminal)
        unowned["members"]["7240"][0]["inOwnedJob"] = False
        refused = self.root / "unowned"
        refused.mkdir()
        self.assertEqual(PAIRED.preserve_failed_jvm_diagnostics(refused, workspace, unowned, {"GRADLE_USER_HOME": str(home)})["known_failed_jvm_generations"], 0)
        with patch.object(PAIRED, "write_new", side_effect=OSError("must not mask original failure")):
            unavailable = PAIRED.preserve_failed_jvm_diagnostics(output, workspace, unowned, {})
        self.assertEqual(unavailable["manifest_error_type"], "OSError")

    def test_complete_gate_classpaths_are_resolved_before_dependency_pins_without_test_filters(self):
        data = {"suites": [
            {"test_gates": [{"task": ":runtime:core:jvmTest", "required_tests": ["dev.example.Core#whole"]}]},
            {"test_gates": [{"task": ":runtime:headless:test", "required_tests": ["dev.example.Headless#whole"]},
                            {"task": ":runtime:core:jvmTest", "required_tests": ["dev.example.Core#whole"]}]},
            {},
        ]}
        self.assertEqual([":strataPairedPrepareTestClasspaths", "-Pstrata.paired.prepareTestClasspaths=:runtime:core:jvmTest,:runtime:headless:test"],
                         PAIRED.test_classpath_preparation(data))
        self.assertEqual([], PAIRED.test_classpath_preparation({"suites": [{}]}))
        self.assertEqual(["dev.example.Core#whole"], data["suites"][0]["test_gates"][0]["required_tests"])

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

    def test_inventory_properties_keep_both_frozen_inputs_and_select_the_declared_side(self):
        other = self.root / "candidate.tsv"
        other.write_bytes(b"candidate inventory\n")
        manifest = copy.deepcopy(self.manifest)
        manifest["inputs"].append({"label": "candidate-inventory", "pin": {"path": other.name, "sha256": PAIRED.digest(other)}})
        manifest["input_properties"] = {"strata.performance.compilerInventory": {"baseline": "external-input", "candidate": "candidate-inventory"}}
        loaded = self.load(manifest)
        archives = {item["label"]: {"path": str(self.root / item["pin"]["path"]), "sha256": item["pin"]["sha256"]} for item in loaded["inputs"]}
        files = PAIRED.input_property_files(self.root, loaded, archives)
        for side, label in (("baseline", "external-input"), ("candidate", "candidate-inventory")):
            record = PAIRED.read_json(files[side]["path"])
            self.assertEqual(record["entries"]["strata.performance.compilerInventory"], archives[label])
            self.assertEqual(PAIRED.digest(files[side]["path"]), files[side]["sha256"])
        self.assertEqual(loaded["inputs"], manifest["inputs"])

    def test_inventory_properties_reject_sampling_overrides_unknown_labels_and_missing_sides(self):
        for binding in (
            {"strata.performance.samples": {"baseline": "external-input", "candidate": "external-input"}},
            {"strata.performance.compilerInventory": {"baseline": "external-input", "candidate": "missing"}},
            {"strata.performance.compilerInventory": {"baseline": "external-input"}},
        ):
            with self.subTest(binding=binding):
                manifest = copy.deepcopy(self.manifest)
                manifest["input_properties"] = binding
                with self.assertRaises(ValueError):
                    self.load(manifest)

    def test_inventory_properties_reject_changed_prepared_bytes_without_repinning(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["input_properties"] = {"strata.performance.compilerInventory": {"baseline": "external-input", "candidate": "external-input"}}
        archives = {"external-input": {"path": str(self.input), "sha256": self.pin["sha256"]}}
        self.input.write_bytes(b"altered inventory\n")
        with self.assertRaisesRegex(ValueError, "bytes changed"):
            PAIRED.input_property_files(self.root, manifest, archives)

    def test_inventory_properties_require_complete_actual_launcher_consumption(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["input_properties"] = {"strata.performance.compilerInventory": {"baseline": "external-input", "candidate": "external-input"}}
        archives = {"external-input": {"path": self.input.as_posix(), "sha256": self.pin["sha256"]}}
        selected = PAIRED.input_property_files(self.root, manifest, archives)["baseline"]
        task = ":quality:benchmarks:verifyHistoricalWorkloads"
        origin = "paired.jvm.origin " + json.dumps({"task": task}) + "\n"
        applied = {"task": task, "property": "strata.performance.compilerInventory", **archives["external-input"]}
        for name, records in (("missing", []), ("unknown", [{**applied, "property": "strata.performance.unknownInventory"}]), ("changed", [{**applied, "sha256": "0" * 64}]), ("duplicate", [applied, applied])):
            directory = self.root / name
            directory.mkdir()
            (directory / "console.log").write_text(origin + "".join("paired.jvm.inputProperty " + json.dumps(item) + "\n" for item in records), encoding="utf-8")
            with self.subTest(name=name), self.assertRaises(ValueError):
                PAIRED.verify_input_properties(directory, selected, [])
        directory = self.root / "complete"
        directory.mkdir()
        (directory / "console.log").write_text(origin + "paired.jvm.inputProperty " + json.dumps(applied) + "\n", encoding="utf-8")
        diagnostic = self.root / "vm.log"
        diagnostic.write_text("<vm_arguments><properties>\nstrata.performance.compilerInventory=" + self.input.as_posix() + "\n</properties></vm_arguments>\n", encoding="utf-8")
        PAIRED.write_new(directory / "vms.json", [{"role": "java_exec", "diagnostic_sha256": PAIRED.digest(diagnostic)}])
        PAIRED.verify_input_properties(directory, selected, [diagnostic])
        self.assertEqual(PAIRED.read_json(directory / "input-properties-verified.json")["applied"], [applied])

    def test_inventory_properties_reject_actual_vm_overwrite_even_with_the_expected_launcher_receipt(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["input_properties"] = {"strata.performance.compilerInventory": {"baseline": "external-input", "candidate": "external-input"}}
        archives = {"external-input": {"path": self.input.as_posix(), "sha256": self.pin["sha256"]}}
        selected = PAIRED.input_property_files(self.root, manifest, archives)["baseline"]
        task = ":quality:benchmarks:verifyHistoricalWorkloads"
        applied = {"task": task, "property": "strata.performance.compilerInventory", **archives["external-input"]}
        for label, value in (("missing", ""), ("overwritten", "strata.performance.compilerInventory=other.tsv\n")):
            directory = self.root / label
            directory.mkdir()
            (directory / "console.log").write_text("paired.jvm.origin " + json.dumps({"task": task}) + "\npaired.jvm.inputProperty " + json.dumps(applied) + "\n", encoding="utf-8")
            diagnostic = directory / "vm.log"
            diagnostic.write_text("<vm_arguments><properties>\n" + value + "</properties></vm_arguments>\n", encoding="utf-8")
            PAIRED.write_new(directory / "vms.json", [{"role": "java_exec", "diagnostic_sha256": PAIRED.digest(diagnostic)}])
            with self.subTest(label=label), self.assertRaisesRegex(ValueError, "actual JVM"):
                PAIRED.verify_input_properties(directory, selected, [diagnostic])

    def test_inventory_properties_bind_actual_vm_parser_output_and_reject_printed_or_changed_values(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["input_properties"] = {"strata.performance.compilerInventory": {"baseline": "external-input", "candidate": "external-input"}}
        archives = {"external-input": {"path": self.input.as_posix(), "sha256": self.pin["sha256"]}}
        selected = PAIRED.input_property_files(self.root, manifest, archives)["baseline"]
        home = self.root / "synthetic-jdk"
        executable = home / "bin/java.exe"
        executable.parent.mkdir(parents=True)
        executable.write_bytes(b"synthetic executable; never executed")
        origin = {"task": ":quality:benchmarks:verifyHistoricalWorkloads", "main": "independent.InventoryOwner", "executable": str(executable), "classpath": "frozen.jar"}
        applied = {"task": origin["task"], "property": "strata.performance.compilerInventory", **archives["external-input"]}
        value = "strata.performance.compilerInventory=" + self.input.as_posix() + "\n"
        for label, properties, printed in (("valid", value, ""), ("missing", "", ""),
                                           ("overwritten", "strata.performance.compilerInventory=other.tsv\n", ""),
                                           ("duplicate", value + value, ""), ("printed-only", "", value)):
            with self.subTest(label=label):
                directory = self.root / label
                (directory / "class-loads").mkdir(parents=True)
                (directory / "console.log").write_text("paired.jvm.origin " + json.dumps(origin) + "\npaired.jvm.inputProperty " + json.dumps(applied) + "\n", encoding="utf-8")
                diagnostic = directory / "vm.log"
                diagnostic.write_text("<hotspot_log process='123' time_ms='1001'><vm_arguments><args>-Xmx768m -XX:MaxMetaspaceSize=384m </args>"
                                      "<command>independent.InventoryOwner input</command><properties>\n"
                                      f"java.home={home}\njava.class.path=frozen.jar\n{properties}</properties></vm_arguments>\n"
                                      f"-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n<tty>\n{printed}</tty>\n"
                                      "<hotspot_log_done/></hotspot_log>\n", encoding="utf-8")
                PAIRED.verify_vms(directory, [diagnostic], {"members": {}}, {os.path.normcase(str(executable)): PAIRED.digest(executable)}, 0)
                actual = PAIRED.read_json(directory / "vms.json")
                self.assertEqual([vm["role"] for vm in actual], ["java_exec"])
                self.assertEqual(actual[0]["diagnostic_sha256"], PAIRED.digest(diagnostic))
                if label == "valid":
                    PAIRED.verify_input_properties(directory, selected, [diagnostic])
                else:
                    with self.assertRaisesRegex(ValueError, "actual JVM"):
                        PAIRED.verify_input_properties(directory, selected, [diagnostic])
                    self.assertFalse((directory / "input-properties-verified.json").exists())

    def compiled_launch_manifest(self):
        """Register two immutable external archive inputs without changing the ordinary runtime projects."""
        value = copy.deepcopy(self.manifest)
        suite = value["suites"][0]
        suite.update(compiled_main="independent.CompleteBenchmark", external_runtime_inputs={"baseline": "external-b", "candidate": "external-c"})
        for side in ("b", "c"):
            path = self.root / ("external-" + side + ".jar")
            with zipfile.ZipFile(path, "x") as archive:
                archive.writestr("independent/External.class", side.encode())
            value["inputs"].append({"label": "external-" + side, "pin": {"path": path.name, "sha256": PAIRED.digest(path)}})
        return value

    def test_compiled_launch_keeps_registered_side_inputs_and_rejects_unbound_main_or_archive(self):
        value = self.compiled_launch_manifest()
        self.assertEqual(self.load(value), value)
        self.assertEqual(value["runtime_projects"], self.manifest["runtime_projects"])
        suite = value["suites"][0]
        inputs = {item["label"]: {"path": str(self.root / item["pin"]["path"]), "sha256": item["pin"]["sha256"]} for item in value["inputs"]}
        for side, label in suite["external_runtime_inputs"].items():
            self.assertEqual(PAIRED.suite_launch_flags(suite, side, inputs), ["-Pstrata.performance.compiledMain=independent.CompleteBenchmark", "-Pstrata.performance.externalRuntime=" + inputs[label]["path"]])
        self.assertEqual(PAIRED.suite_launch_flags(self.manifest["suites"][0], "baseline", inputs), [])
        for main in (None, True, "", "unsafe main", "a;command", "../Main", "Single"):
            invalid = copy.deepcopy(value)
            invalid["suites"][0]["compiled_main"] = main
            with self.subTest(main=main), self.assertRaises(ValueError):
                self.load(invalid)
        for selected in ({"baseline": "external-b"}, {"baseline": "unknown", "candidate": "external-c"}, {"baseline": [], "candidate": "external-c"}):
            invalid = copy.deepcopy(value)
            invalid["suites"][0]["external_runtime_inputs"] = selected
            with self.subTest(selected=selected), self.assertRaises(ValueError):
                self.load(invalid)
        with self.assertRaisesRegex(ValueError, "not registered"):
            PAIRED.suite_launch_flags(suite, "baseline", {})
        archive = Path(inputs["external-b"]["path"])
        archive.write_bytes(b"mutated archive")
        with self.assertRaisesRegex(ValueError, "changed"):
            PAIRED.suite_launch_flags(suite, "baseline", inputs)
        archive.unlink()
        with self.assertRaisesRegex(ValueError, "Missing"):
            PAIRED.suite_launch_flags(suite, "baseline", inputs)

    def test_compiled_launch_full_fake_owned_route_keeps_verifier_preflight_collection_and_operation_classpaths(self):
        """Exercise the actual controller phases with fake helper bytes and real closed diagnostic/pin readers, never Java."""
        for optional, preflight, mutate in ((False, False, False), (True, False, False), (True, True, False), (True, True, True)):
            with self.subTest(optional=optional, preflight=preflight, mutate=mutate), tempfile.TemporaryDirectory() as temporary:
                original_root, original_input = self.root, self.input
                self.root = Path(temporary)
                self.input = self.root / "fixture.txt"
                self.input.write_bytes(original_input.read_bytes())
                try:
                    value = self.compiled_launch_manifest() if optional else copy.deepcopy(self.manifest)
                    value["suites"][0]["modes"] = ["avgt"]
                    suite = value["suites"][0]
                    suite["operation_reports"] = {"adapters": {"IndependentFixtureBenchmark": "independent.OperationAdapter"}, "warmup": 30, "samples": 60, "expected_rows": 2, "expected_controls": 1}
                    runtime_names = {project: "independent.Runtime" + str(index) for index, project in enumerate(value["runtime_projects"])}
                    if preflight:
                        suite["preflight"] = {"main": "independent.Control", "qualification": self.pin, "runtime_classes": runtime_names, "controls": [{"id": "complete", "arguments": []}]}
                    manifest = self.root / "manifest.json"
                    manifest.write_text(json.dumps(value), encoding="utf-8")
                    self.assertEqual(PAIRED.load_manifest(self.root, manifest), value)
                    source_root = self.root / "driver"
                    (source_root / "gradle").mkdir(parents=True)
                    catalog = Path(PAIRED.__file__).resolve().parents[2] / "gradle/libs.versions.toml"
                    shutil.copyfile(catalog, source_root / "gradle/libs.versions.toml")
                    versions = PAIRED.tomllib.loads(catalog.read_text(encoding="utf-8"))["versions"]
                    homes = {}
                    for role, provider, version in (("STRATA_PAIRED_TOOLCHAIN", "Eclipse Adoptium", versions["performance-toolchain"].split("+", 1)[0]), ("JAVA_HOME", "Oracle Corporation", versions["performance-launcher"])):
                        home = self.root / role
                        (home / "bin").mkdir(parents=True)
                        (home / "bin/java.exe").write_bytes(role.encode())
                        (home / "release").write_text(f'IMPLEMENTOR="{provider}"\nJAVA_VERSION="{version}"\n', encoding="utf-8")
                        homes[role] = str(home)
                    workspace = self.root / "fixture-workspace"
                    workspace.mkdir()
                    archives = {}
                    owners = value["runtime_projects"] + [":performance-testkit"]
                    for index, project in enumerate(owners):
                        archive = self.root / ("runtime-" + str(index) + ".jar")
                        with zipfile.ZipFile(archive, "x") as jar:
                            name = runtime_names.get(project, "independent.Control")
                            jar.writestr(name.replace(".", "/") + ".class", project.encode())
                            if project == ":performance-testkit":
                                jar.writestr("dev/s7a/strata/performance/JvmPerformanceMeter.class", b"meter")
                                jar.writestr("org/openjdk/jmh/runner/Runner.class", b"runner")
                        archives[project] = archive
                    for relative in ("classes/kotlin/jmh", "jmh-generated-classes", "jmh-generated-resources"):
                        directory = workspace / "quality/benchmarks/build" / relative
                        directory.mkdir(parents=True)
                        (directory / "fixture.bin").write_bytes(b"common compiled fixture")
                    output = self.root / "output"
                    calls = []

                    def fake_invoke(command, cwd, console):
                        request_path = Path(command[command.index("-Request") + 1])
                        directory = request_path.parent
                        request = PAIRED.read_json(request_path)
                        arguments = request["arguments"]
                        phase = directory.name
                        calls.append((phase, arguments))
                        properties = {argument.split("=", 1)[0]: argument.split("=", 1)[1] for argument in arguments if argument.startswith("-P") and "=" in argument}
                        if "-Pstrata.performance.operationRequest" in properties:
                            operation = PAIRED.read_json(Path(properties["-Pstrata.performance.operationRequest"]))
                            collector = archives[":performance-testkit"]
                            expected = PAIRED.owner_processing_collector(Path(properties["-Pstrata.paired.classpath"]), collector) if preflight else str(collector)
                            self.assertEqual(operation["collector"], expected)
                        external = properties.get("-Pstrata.performance.externalRuntime")
                        entries = [str(path) for path in archives.values()] + ([external] if external else [])
                        if "-Pstrata.paired.preserveClasspath" in properties:
                            retained = Path(properties["-Pstrata.paired.preserveClasspath"])
                            records = [{"source": path, **PAIRED.preserve_file(Path(path), retained / (str(index) + ".jar"))} for index, path in enumerate(entries)]
                            PAIRED.write_new(retained / "classpath.json", {"schema": "strata-paired-jvm-classpath-v1", "entries": records})
                        if "-Pstrata.paired.classpath" in properties:
                            captured = PAIRED.prepared_classpath(Path(properties["-Pstrata.paired.classpath"]))
                            self.assertEqual([item["source"] for item in captured["entries"]], entries)
                        pid = len(calls) + 100
                        main = properties.get("-Pstrata.performance.compiledMain", "independent.DefaultCollector")
                        if "-Pstrata.performance.operationRequest" in properties:
                            main = "dev.s7a.strata.performance.JvmOperationReports"
                        if "-Pstrata.paired.preflight" in properties:
                            control = PAIRED.read_json(Path(properties["-Pstrata.paired.preflight"]))
                            main = control["main"]
                        home = Path(homes["JAVA_HOME"])
                        classpath = ";".join(entries)
                        origins = {"main": main, "executable": str(home / "bin/java.exe"), "classpath": classpath}
                        console.write_text("paired.jvm.origin " + json.dumps(origins) + "\n", encoding="utf-8")
                        logs = Path(request["environment"]["STRATA_PAIRED_LOGS"])
                        (logs / f"vm-{pid}-2026-10-12_00-00-00.log").write_text(f"<hotspot_log process='{pid}' time_ms='1001'>\n<vm_arguments><command>{main}</command>\njava.home={home}\njava.class.path={classpath}\n</vm_arguments>\n-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n<hotspot_log_done stamp='1'/>\n</hotspot_log>\n", encoding="utf-8")
                        member = {"inOwnedJob": True, "completed": True, "exitCode": 0, "affinityMask": 15, "systemAffinityMask": 15, "image": str(home / "bin/java.exe"), "creationFileTime": 116444736010000000}
                        PAIRED.write_new(directory / "terminal.json", {"exitCode": 0, "naturalRelease": True, "handlesClosed": True, "jobName": phase, "inheritedAffinityMask": 15, "systemAffinityMask": 15, "members": {str(pid): member}})
                        PAIRED.write_new(directory / "child-admission.json", {"inOwnedJob": True, "jobName": phase, "availablePhysicalBytes": 4 * 1024**3})
                        if "-Pstrata.paired.preflight" in properties:
                            staging = Path(request["environment"]["STRATA_PAIRED_CLASS_LOGS"])
                            diagnostic = "".join(f"[{pid}][info][class,load] {name} source: {Path(identity['path']).as_uri()}\n" for name, identity in control["required_classes"].items())
                            (staging / f"classes-{pid}-2026-10-12_00-00-00.log").write_text(diagnostic, encoding="utf-8")
                            retained = Path(properties["-Pstrata.paired.preflight"]).parent
                            artifact = retained / "complete.txt"
                            artifact.write_bytes(b"independent complete observation")
                            PAIRED.write_new(retained / "receipt.json", {"schema": "strata-paired-jvm-preflight-receipt-v1", "status": "passed", "request_sha256": PAIRED.digest(retained / "request.json"), "controls": [{"id": "complete", "status": "passed", "observations": {"rows": 2}, "origins": {name: {"pid": pid} for name in control["required_classes"]}, "artifacts": [{"path": artifact.name, "sha256": PAIRED.digest(artifact)}]}]})
                            if mutate:
                                Path(external).write_bytes(b"mutated after complete admission")
                        elif "-Pstrata.performance.operationRequest" in properties:
                            operation = PAIRED.read_json(Path(properties["-Pstrata.performance.operationRequest"]))
                            Path(operation["output"]).mkdir(parents=True)
                            PAIRED.write_new(Path(operation["output"]) / "report.json", {"status": "passed", "run_id": phase, "phases": [{"control": True}, {"control": False}]})
                        elif "-Pstrata.performance.request" in properties:
                            comparison = PAIRED.read_json(Path(properties["-Pstrata.performance.request"]))
                            field = "phases" if comparison["command"] == "operation-comparison" else "cases"
                            PAIRED.write_new(Path(comparison["output"]), {"status": "passed", field: [{}, {}]})
                        elif phase.startswith("collect-"):
                            repetition = int(properties["-Pstrata.performance.repetition"])
                            destination = Path(properties["-Pstrata.performance.historicalOutputRoot"]) / suite["name"] / ("run-" + str(repetition))
                            destination.mkdir(parents=True)
                            PAIRED.write_new(destination / "receipt.json", {"status": "passed", "run_id": phase, "fork_verification": "loaded-artifacts-per-iteration-v1", "registered_workloads": ["first", "second"]})
                            terminal = PAIRED.read_json(directory / "terminal.json")
                            staging = Path(request["environment"]["STRATA_PAIRED_CLASS_LOGS"])
                            for index in range(suite["expected_cases"]):
                                fork_pid = pid * 10 + index
                                (logs / f"vm-{fork_pid}-2026-10-12_00-00-00.log").write_text(f"<hotspot_log process='{fork_pid}' time_ms='1001'>\n<vm_arguments><command>org.openjdk.jmh.runner.ForkedMain 1</command>\njava.home={home}\njava.class.path={classpath}\n</vm_arguments>\n-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n<hotspot_log_done stamp='1'/>\n</hotspot_log>\n", encoding="utf-8")
                                (staging / f"classes-{fork_pid}-2026-10-12_00-00-00.log").write_text(f"[{fork_pid}][info][class,load] org.openjdk.jmh.runner.ForkedMain source: {archives[':performance-testkit'].as_uri()}\n", encoding="utf-8")
                                terminal["members"][str(fork_pid)] = dict(member)
                            (directory / "terminal.json").write_text(json.dumps(terminal), encoding="utf-8")
                        return 0

                    executable = Path(PAIRED.sys.executable).resolve()
                    helper = self.root / "pwsh.exe"
                    helper.write_bytes(b"synthetic helper; never launched")
                    patches = (patch.dict(os.environ, homes), patch.object(PAIRED, "os", SimpleNamespace(name="nt", path=os.path, environ=os.environ)), patch.object(PAIRED, "sys", SimpleNamespace(executable=str(executable))), patch.object(PAIRED.shutil, "which", return_value=str(helper)), patch.object(PAIRED, "git", return_value="a" * 40), patch.object(PAIRED, "verify_driver_source", return_value=None), patch.object(PAIRED, "preserve_driver_sources", return_value={}), patch.object(PAIRED, "verify_driver_sources"), patch.object(PAIRED, "source_guard"), patch.object(PAIRED, "materialize", side_effect=lambda root, output, side, source, inputs: (workspace, source)), patch.object(PAIRED, "archive_file", side_effect=lambda workspace, project: archives[project]), patch.object(PAIRED.subprocess, "check_output", return_value='{"source_only_fake":true}'), patch.object(PAIRED, "invoke", side_effect=fake_invoke), patch.object(PAIRED, "table"))
                    with ExitStack() as stack:
                        for context in patches:
                            stack.enter_context(context)
                        if mutate:
                            with self.assertRaisesRegex(ValueError, "changed"):
                                PAIRED.run(source_root, manifest, output, input_root=self.root)
                        else:
                            PAIRED.run(source_root, manifest, output, input_root=self.root)
                    attempt = PAIRED.read_json(output / "attempt.json")
                    self.assertEqual(attempt["executables"][0], {"path": str(executable), "sha256": PAIRED.digest(executable)})
                    if mutate:
                        self.assertEqual(attempt["status"], "failed")
                        self.assertFalse(any(name.startswith("collect-") for name, _ in calls))
                    else:
                        self.assertEqual(attempt["score_cells"], 12)
                        self.assertEqual(attempt["operation_cells"], 12)
                        self.assertEqual(attempt["status"], "passed")
                    applicable = [(name, arguments) for name, arguments in calls if name.startswith(("admit-", "preflight-", "collect-"))]
                    for name, arguments in applicable:
                        flags = [argument for argument in arguments if argument.startswith(("-Pstrata.performance.compiledMain=", "-Pstrata.performance.externalRuntime="))]
                        self.assertEqual(len(flags), 2 if optional else 0)
                        if optional:
                            label = "external-b" if "-baseline" in name else "external-c"
                            index = next(index for index, item in enumerate(value["inputs"]) if item["label"] == label)
                            self.assertTrue(flags[1].endswith(f"input-{index}.jar"))
                    self.assertTrue(all((output / "phases" / name / "vms.json").is_file() for name, _ in calls))
                finally:
                    self.root, self.input = original_root, original_input

    def test_fake_route_admission_preserves_real_platform_and_regular_file_guards(self):
        output = self.root / "must-not-start"
        actual_name, actual_path_type = os.name, type(Path())
        with patch.object(PAIRED, "os", SimpleNamespace(name="posix")), self.assertRaisesRegex(ValueError, "Windows owned-process boundary"):
            PAIRED.run(self.root, self.root / "missing.json", output)
        self.assertFalse(output.exists())
        self.assertEqual((os.name, type(Path())), (actual_name, actual_path_type))
        executable = Path(PAIRED.sys.executable).resolve()
        alias = Mock(spec=Path)
        alias.is_file.return_value = True
        alias.is_symlink.return_value = True
        alias.resolve.return_value = executable
        with self.assertRaisesRegex(ValueError, "Missing or unsafe pinned file"):
            PAIRED.digest(alias)
        self.assertEqual(PAIRED.digest(alias.resolve()), hashlib.sha256(executable.read_bytes()).hexdigest())
        alias.open.assert_not_called()

    def test_original_average_time_only_profile_keeps_all_cases_controls_and_repetitions(self):
        original = copy.deepcopy(self.manifest)
        explicit = copy.deepcopy(original)
        explicit["suites"][0]["modes"] = ["avgt"]
        loaded = self.load(explicit)
        self.assertEqual(loaded, explicit)
        retained = copy.deepcopy(loaded)
        retained["suites"][0].pop("modes")
        self.assertEqual(retained, original)

    def test_omitted_or_explicit_two_mode_profile_preserves_the_existing_default(self):
        self.assertEqual(self.load(self.manifest), self.manifest)
        explicit = copy.deepcopy(self.manifest)
        explicit["suites"][0]["modes"] = ["avgt", "sample"]
        self.assertEqual(self.load(explicit), explicit)

    def test_modes_cannot_drop_average_time_duplicate_change_order_or_select_quick(self):
        for modes in (None, True, "avgt", [], ["sample"], ["sample", "avgt"], ["avgt", "avgt"],
                      ["avgt", "quick"], ["avgt", "sample", "avgt"], [1], {}):
            with self.subTest(modes=modes):
                invalid = copy.deepcopy(self.manifest)
                invalid["suites"][0]["modes"] = modes
                with self.assertRaises(ValueError):
                    self.load(invalid)

    def completed_jmh_fixture(self, modes):
        """Create independent synthetic audit events without invoking a collector or producing scores."""
        completed = [(name, mode, side, repetition, f"receipt-{name}-{mode}-{side}-{repetition}")
                     for name, mode in modes for repetition in range(3) for side in ("baseline", "candidate")]
        return {item[4] for item in completed}, [(name, mode, {}) for name, mode in modes], completed

    def test_final_jmh_audit_accepts_original_42_average_time_default_and_mixed_complete_suites(self):
        original = {**self.manifest["suites"][0], "name": "original-42", "modes": ["avgt"], "expected_cases": 42}
        default = {**self.manifest["suites"][0], "name": "default"}
        for suites, modes, expected_invocations, expected_comparisons in (
            ([original], [("original-42", "avgt")], 6, 1),
            ([default], [("default", "avgt"), ("default", "sample")], 12, 2),
            ([original, default], [("original-42", "avgt"), ("default", "avgt"), ("default", "sample")], 18, 3),
        ):
            with self.subTest(suites=suites):
                ids, comparisons, completed = self.completed_jmh_fixture(modes)
                self.assertEqual(len(ids), expected_invocations)
                self.assertEqual(len(comparisons), expected_comparisons)
                PAIRED.verify_jmh_completion({"suites": suites}, ids, comparisons, completed)
        PAIRED.verify_jmh_completion({"suites": []}, set(), [], [])

    def test_final_jmh_audit_rejects_old_two_mode_counts_for_the_original_average_time_profile(self):
        data = {"suites": [{"name": "original-42", "modes": ["avgt"], "expected_cases": 42}]}
        ids, comparisons, completed = self.completed_jmh_fixture([("original-42", "avgt"), ("original-42", "sample")])
        with self.assertRaisesRegex(ValueError, "invocation count"):
            PAIRED.verify_jmh_completion(data, ids, comparisons, completed)
        ids, comparisons, completed = self.completed_jmh_fixture([("original-42", "avgt")])
        with self.assertRaisesRegex(ValueError, "comparison count"):
            PAIRED.verify_jmh_completion(data, ids, comparisons + [("original-42", "sample", {})], completed)

    def test_final_mixed_mode_audit_rejects_missing_repeated_reordered_or_orphan_invocations(self):
        data = {"suites": [{"name": "original-42", "modes": ["avgt"], "expected_cases": 42}, {"name": "default", "expected_cases": 2}]}
        ids, comparisons, completed = self.completed_jmh_fixture([("original-42", "avgt"), ("default", "avgt"), ("default", "sample")])
        altered = [
            (ids - {completed[-1][4]}, completed[:-1]),
            (ids | {"extra-undeclared-receipt"}, completed + [("default", "sample", "candidate", 3, "extra-undeclared-receipt")]),
            (ids, [completed[1], completed[0], *completed[2:]]),
            (ids, [completed[0], completed[0], *completed[2:]]),
            (ids, [(*completed[0][:4], "different-physical-receipt"), *completed[1:]]),
            ((ids - {completed[0][4]}) | {"orphan-receipt"}, completed),
        ]
        for actual_ids, sequence in altered:
            with self.subTest(ids=actual_ids, sequence=sequence), self.assertRaises(ValueError):
                PAIRED.verify_jmh_completion(data, actual_ids, comparisons, sequence)

    def test_final_mixed_mode_audit_rejects_missing_extra_repeated_and_reordered_comparisons(self):
        data = {"suites": [{"name": "original-42", "modes": ["avgt"], "expected_cases": 42}, {"name": "default", "expected_cases": 2}]}
        ids, comparisons, completed = self.completed_jmh_fixture([("original-42", "avgt"), ("default", "avgt"), ("default", "sample")])
        for actual in (comparisons[:-1], comparisons + [("extra", "avgt", {})],
                       [comparisons[0], comparisons[0], comparisons[2]],
                       [comparisons[1], comparisons[0], comparisons[2]],
                       [comparisons[0], comparisons[1], ("default", "avgt", {})]):
            with self.subTest(comparisons=actual), self.assertRaises(ValueError):
                PAIRED.verify_jmh_completion(data, ids, actual, completed)

    def selector_git(self, *arguments):
        """Build real isolated fixture histories without invoking commit signing."""
        return subprocess.check_output(["git", "-c", "core.bare=false", "-c", "commit.gpgsign=false",
                                        "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                                        "-C", str(self.root), *arguments], text=True, encoding="utf-8").strip()

    def selector_manifest(self, path, value=None):
        """Keep the complete fixture and existing input pin in every selector control."""
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(self.manifest if value is None else value), encoding="utf-8")
        return path

    def selector_commit(self, message="Preserve complete selector fixture"):
        self.selector_git("add", "--all")
        self.selector_git("commit", "--quiet", "--allow-empty", "-m", message)
        return self.selector_git("rev-parse", "HEAD")

    def selector_repository(self):
        self.selector_git("init", "--quiet")
        self.selector_git("config", "core.autocrlf", "false")
        self.selector_git("config", "core.longpaths", "true")
        (self.root / "source.txt").write_text("base source\n", encoding="utf-8")
        return self.selector_commit()

    def dispatch_fixture(self):
        """Prepare complete metadata outside a real clean execution repository."""
        head = self.selector_repository()
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        source = {"ref": head, "tree": self.selector_git("rev-parse", "HEAD^{tree}"), "patches": []}
        manifest = copy.deepcopy(self.manifest)
        manifest["sources"] = {side: copy.deepcopy(source) for side in ("baseline", "candidate", "fixture")}
        path = ".github/performance/manual/manifest.json"
        target = directory / path
        target.parent.mkdir(parents=True)
        target.write_text(json.dumps(manifest), encoding="utf-8")
        inputs = PAIRED.pack_dispatch_inputs(directory, path)
        event = directory / "event.json"
        event.write_text(json.dumps({"inputs": inputs}), encoding="utf-8")
        return head, manifest, inputs, event

    def test_dispatch_keeps_complete_pins_without_any_producer_or_consumer_commit(self):
        head, manifest, inputs, event = self.dispatch_fixture()
        refs = self.selector_git("show-ref", "--heads")
        raw = lzma.decompress(base64.b64decode(inputs["paired_inputs"]))
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_EVENT_PATH": str(event)}):
            with PAIRED.selected_inputs(self.root, inputs["paired_manifest"], expected_head=head) as selection:
                self.assertEqual(PAIRED.load_manifest(selection["root"], selection["path"]), manifest)
                self.assertEqual(selection["producer"], hashlib.sha256(raw).hexdigest())
                self.assertEqual(selection["bundle"].read_bytes(), raw)
                self.assertNotEqual(selection["root"], self.root)
            with self.assertRaisesRegex(ValueError, "planned bytes"):
                with PAIRED.selected_inputs(self.root, inputs["paired_manifest"], data_ref="0" * 64):
                    self.fail("Changed ZIP admitted")
            with self.assertRaisesRegex(ValueError, "planned bytes"):
                with PAIRED.selected_inputs(self.root, inputs["paired_manifest"], manifest_sha256="0" * 64):
                    self.fail("Changed manifest admitted")
            with self.assertRaisesRegex(ValueError, "explicit manual"):
                with PAIRED.selected_inputs(self.root, inputs["paired_manifest"], base=head, head=head):
                    self.fail("Dispatch combined with PR selection")
        self.assertEqual(self.selector_git("rev-parse", "HEAD"), head)
        self.assertEqual(self.selector_git("rev-list", "--count", "HEAD"), "1")
        self.assertEqual(self.selector_git("show-ref", "--heads"), refs)
        self.assertEqual(self.selector_git("status", "--porcelain=v1"), "")

    def test_dispatch_rejects_a_changed_candidate_source_before_collection(self):
        _, _, inputs, event = self.dispatch_fixture()
        (self.root / "source.txt").write_text("another candidate\n", encoding="utf-8")
        changed = self.selector_commit()
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_EVENT_PATH": str(event)}):
            with self.assertRaisesRegex(ValueError, "Candidate source differs"):
                with PAIRED.selected_inputs(self.root, inputs["paired_manifest"], expected_head=changed):
                    self.fail("Different execution source admitted")

    def test_dispatch_separate_driver_retains_subject_patch_and_rejects_changed_bundle_or_manifest(self):
        head, manifest, inputs, event = self.dispatch_fixture()
        directory = event.parent
        path = inputs["paired_manifest"]
        manifest["driver_source"] = {"ref": head, "tree": self.selector_git("rev-parse", head + "^{tree}")}
        (self.root / "source.txt").write_text("separate candidate source\n", encoding="utf-8")
        self.selector_git("add", "source.txt")
        candidate_tree = self.selector_git("write-tree")
        patch_bytes = subprocess.check_output(["git", "-C", str(self.root), "diff", "--cached", "--binary", head])
        patch_path = ".github/performance/manual/candidate.patch"
        (directory / patch_path).write_bytes(patch_bytes)
        manifest["sources"]["candidate"].update(tree=candidate_tree, patches=[{"path": patch_path, "sha256": hashlib.sha256(patch_bytes).hexdigest()}])
        self.selector_git("restore", "--source=" + head, "--staged", "--worktree", "source.txt")
        (directory / path).write_text(json.dumps(manifest), encoding="utf-8")
        event.unlink()
        inputs = PAIRED.pack_dispatch_inputs(directory, path)
        event.write_text(json.dumps({"inputs": inputs}), encoding="utf-8")
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_EVENT_PATH": str(event)}):
            with PAIRED.selected_inputs(self.root, path, expected_head=head) as selection:
                self.assertTrue(selection["explicit_manual"])
                self.assertEqual(PAIRED.load_manifest(selection["root"], selection["path"]), manifest)
                arguments = (self.root, head, selection["root"], selection["path"], selection["producer"], selection["sha256"], selection["bundle"])
                PAIRED.verify_input_producer(*arguments)
                original = selection["bundle"].read_bytes()
                selection["bundle"].write_bytes(original + b"changed")
                with self.assertRaisesRegex(ValueError, "dispatch input bytes changed"):
                    PAIRED.verify_input_producer(*arguments)
                selection["bundle"].write_bytes(original)
                selection["path"].write_bytes(selection["path"].read_bytes() + b"\n")
                with self.assertRaisesRegex(ValueError, "manifest differs"):
                    PAIRED.verify_input_producer(*arguments)
            self.assertEqual(self.selector_git("rev-list", "--count", "HEAD"), "1")
            self.assertEqual(self.selector_git("status", "--porcelain=v1"), "")

    def test_dispatch_rejects_dirty_tracked_driver_and_invalid_event_payload(self):
        head, _, inputs, event = self.dispatch_fixture()
        environment = {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_EVENT_PATH": str(event)}
        with patch.dict(os.environ, environment):
            (self.root / "source.txt").write_text("dirty working bytes\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "tracked bytes"):
                with PAIRED.selected_inputs(self.root, inputs["paired_manifest"], expected_head=head):
                    self.fail("Dirty driver admitted")
            for invalid in ("x" * 60001, ["not a string"], "not base64!"):
                event.write_text(json.dumps({"inputs": {"paired_inputs": invalid}}), encoding="utf-8")
                with self.subTest(invalid_type=type(invalid).__name__), self.assertRaises(ValueError):
                    PAIRED.dispatch_input_bytes()

    def test_dispatch_zip_rejects_aliases_links_and_existing_file_overwrite(self):
        def bundle(entries):
            output = io.BytesIO()
            with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                for path, mode in entries:
                    entry = zipfile.ZipInfo(path)
                    entry.external_attr = mode << 16
                    archive.writestr(entry, b"fixed")
            return output.getvalue()
        for entries in ([('../escape', 0o100644)], [('source.txt', 0o100644)],
                        [('.github/performance/../outside', 0o100644)], [('.github/performance/NUL.json', 0o100644)],
                        [('.github/performance/test.json.', 0o100644)], [('.github/performance/test.json', 0o120777)],
                        [('.github/performance/test.json', 0o100644), ('.github/performance/TEST.json', 0o100644)]):
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                PAIRED.unpack_dispatch_inputs(bundle(entries), self.root)
        target = self.root / ".github/performance/existing.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"original")
        with self.assertRaisesRegex(ValueError, "overwrites"):
            PAIRED.unpack_dispatch_inputs(bundle([(target.relative_to(self.root).as_posix(), 0o100644)]), self.root)
        self.assertEqual(target.read_bytes(), b"original")

    def test_dispatch_zip_bounds_compressed_bytes_files_and_expansion(self):
        with self.assertRaisesRegex(ValueError, "exceeds 6 MiB"):
            PAIRED.unpack_dispatch_inputs(b"x" * (6 * 1024**2 + 1), self.root)
        for count, size in ((257, 1), (1, 4 * 1024**2 + 1)):
            output = io.BytesIO()
            with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                for index in range(count):
                    archive.writestr(f".github/performance/{index}.json", b"x" * size)
            with self.subTest(count=count, size=size), self.assertRaisesRegex(ValueError, "256 files or 4 MiB"):
                PAIRED.unpack_dispatch_inputs(output.getvalue(), self.root)

    def test_dispatch_selection_retains_exact_input_artifact_and_hash_outputs(self):
        head, _, inputs, event = self.dispatch_fixture()
        output = event.parent / "outputs.txt"
        file = self.root / "gradle/performance/paired-jvm.py"
        argv = [str(file), "--select", "--manifest", inputs["paired_manifest"]]
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_EVENT_PATH": str(event), "GITHUB_OUTPUT": str(output)}), \
                patch.object(PAIRED, "__file__", str(file)), patch.object(PAIRED.sys, "argv", argv), redirect_stdout(io.StringIO()):
            PAIRED.main()
        raw = lzma.decompress(base64.b64decode(inputs["paired_inputs"]))
        self.assertEqual((self.root / "build/performance-ci/dispatch-inputs.zip").read_bytes(), raw)
        self.assertIn("paired_data_ref=" + hashlib.sha256(raw).hexdigest(), output.read_text())
        self.assertEqual(self.selector_git("rev-parse", "HEAD"), head)
        self.assertEqual(self.selector_git("diff", "HEAD", "--name-only"), "")

    def test_dispatch_rejects_trailing_truncated_and_oversized_compressed_data(self):
        _, _, _, event = self.dispatch_fixture()
        valid = lzma.compress(b"bounded input")
        for invalid in (valid + b"trailing", valid[:-1], lzma.compress(b"x" * (6 * 1024**2 + 1))):
            event.write_text(json.dumps({"inputs": {"paired_inputs": base64.b64encode(invalid).decode("ascii")}}), encoding="utf-8")
            with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_EVENT_PATH": str(event)}), self.assertRaises(ValueError):
                PAIRED.dispatch_input_bytes()

    def artifact_fixture(self):
        """Create an independent nested provider ZIP and a byte-pinned large input declaration."""
        directory = self.root / "metadata"
        directory.mkdir()
        manifest = ".github/performance/manual/manifest.json"
        target = ".github/performance/manual/app.js"
        expected = b"exact frozen application bytes"
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as archive:
            archive.writestr("outputs/app.js", expected)
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, "w") as archive:
            archive.writestr("prepared.zip", nested.getvalue())
        raw = outer.getvalue()
        provider = {"id": 123, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(), "archive": "prepared.zip",
                    "files": [{"member": "outputs/app.js", "target": target}]}
        path = directory / manifest
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps({"inputs": [{"label": "app", "pin": {"path": target, "sha256": hashlib.sha256(expected).hexdigest()}}]}), encoding="utf-8")
        (directory / ".github/performance/dispatch-artifacts.json").write_text(json.dumps([provider]), encoding="utf-8")
        metadata = {"id": 123, "size_in_bytes": len(raw), "digest": "sha256:" + provider["sha256"], "expired": False}
        return directory, manifest, target, expected, provider, metadata, raw

    def test_dispatch_hydrates_only_exact_pinned_artifact_bytes_without_Git_inputs(self):
        directory, manifest, target, expected, provider, metadata, raw = self.artifact_fixture()
        inputs = PAIRED.pack_dispatch_inputs(directory, manifest)
        with zipfile.ZipFile(io.BytesIO(lzma.decompress(base64.b64decode(inputs["paired_inputs"])))) as archive:
            self.assertNotIn(target, archive.namelist())
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repository", "RUNNER_TEMP": str(self.root / "job")}), \
                patch.object(PAIRED.subprocess, "check_output", side_effect=[json.dumps(metadata).encode(), raw]) as download:
            targets, receipts = PAIRED.dispatch_artifact_inputs(directory, manifest, download=True)
            self.assertEqual(download.call_count, 2)
        self.assertEqual(targets, {target.casefold()})
        self.assertEqual(receipts, [provider])
        self.assertEqual((directory / target).read_bytes(), expected)
        (directory / target).unlink()
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repository", "RUNNER_TEMP": str(self.root / "job")}), \
                patch.object(PAIRED.subprocess, "check_output", return_value=json.dumps(metadata).encode()) as download:
            PAIRED.dispatch_artifact_inputs(directory, manifest, download=True)
            self.assertEqual(download.call_count, 1)
        self.assertEqual((directory / target).read_bytes(), expected)

    def test_dispatch_rejects_changed_provider_bytes_expiry_and_undeclared_targets(self):
        directory, manifest, target, _, provider, metadata, raw = self.artifact_fixture()
        environment = {"GITHUB_REPOSITORY": "owner/repository", "RUNNER_TEMP": str(self.root / "job")}
        for responses in ([json.dumps({**metadata, "expired": True}).encode()],
                          [json.dumps(metadata).encode(), raw + b"changed"]):
            with patch.dict(os.environ, environment), patch.object(PAIRED.subprocess, "check_output", side_effect=responses), self.assertRaises(ValueError):
                PAIRED.dispatch_artifact_inputs(directory, manifest, download=True)
            self.assertFalse((directory / target).exists())
        changed = {**provider, "files": [{"member": "outputs/app.js", "target": ".github/performance/undeclared.js"}]}
        (directory / ".github/performance/dispatch-artifacts.json").write_text(json.dumps([changed]), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "declared metadata"):
            PAIRED.pack_dispatch_inputs(directory, manifest)

    def test_dispatch_admits_standard_Source_providers_with_the_original_total_transfer_bound(self):
        directory, manifest, target, expected, provider, _, _ = self.artifact_fixture()
        descriptor = directory / ".github/performance/dispatch-artifacts.json"
        for size in (200_257_512, 256 * 1024**2):
            descriptor.write_text(json.dumps([{**provider, "bytes": size}]), encoding="utf-8")
            targets, receipts = PAIRED.dispatch_artifact_inputs(directory, manifest)
            self.assertEqual(targets, {target.casefold()})
            self.assertEqual(receipts, [])
        descriptor.write_text(json.dumps([{**provider, "bytes": 256 * 1024**2 + 1}]), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "artifact identity"):
            PAIRED.dispatch_artifact_inputs(directory, manifest)
        inputs, providers = [], []
        for index, size in enumerate((256 * 1024**2, 256 * 1024**2, 1)):
            path = target + str(index)
            inputs.append({"label": "app" + str(index), "pin": {"path": path, "sha256": hashlib.sha256(expected).hexdigest()}})
            providers.append({**provider, "id": index + 1, "bytes": size, "files": [{"member": "outputs/app.js", "target": path}]})
        (directory / manifest).write_text(json.dumps({"inputs": inputs}), encoding="utf-8")
        descriptor.write_text(json.dumps(providers[:2]), encoding="utf-8")
        targets, _ = PAIRED.dispatch_artifact_inputs(directory, manifest)
        self.assertEqual(targets, {item["pin"]["path"].casefold() for item in inputs[:2]})
        descriptor.write_text(json.dumps(providers), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "512 MiB in total"):
            PAIRED.dispatch_artifact_inputs(directory, manifest)

    def input_consumer(self, malformed=None, candidate_patch=False, mismatch=None, value=None, payload=None, separate_driver=False):
        """Construct real producer/consumer commits and an independently reconstructed candidate tree."""
        base = self.selector_repository()
        if separate_driver:
            for name in ("paired-jvm.py", "invoke-windows.ps1", "bounded-jvm.init.gradle.kts"):
                driver_file = self.root / "gradle/performance" / name
                driver_file.parent.mkdir(parents=True, exist_ok=True)
                driver_file.write_bytes(b"frozen generic driver\n")
            base = self.selector_commit("Freeze shared driver fixture")
        source = {"ref": base, "tree": self.selector_git("rev-parse", base + "^{tree}"), "patches": []}
        for relative, contents in (payload or {}).items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(contents)
        if candidate_patch:
            (self.root / "source.txt").write_text("candidate source\n", encoding="utf-8")
            self.selector_git("add", "source.txt")
            source["tree"] = self.selector_git("write-tree")
            raw = subprocess.check_output(["git", "-C", str(self.root), "diff", "--cached", "--binary", "--full-index", base, "--", "source.txt"])
            path = self.root / ".github/performance/owned/candidate.patch"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
            source["patches"] = [{"path": path.relative_to(self.root).as_posix(), "sha256": PAIRED.digest(path)}]
        manifest = copy.deepcopy(self.manifest if value is None else value)
        manifest["sources"] = {side: copy.deepcopy(source) for side in ("fixture", "baseline", "candidate")}
        if separate_driver:
            driver = {"ref": base, "tree": self.selector_git("rev-parse", base + "^{tree}")}
            manifest["driver_source"] = driver
            manifest["sources"]["baseline"] = {**driver, "patches": []}
            self.selector_git("restore", "--source=" + base, "--staged", "--worktree", "source.txt")
        path = self.selector_manifest(".github/performance/owned/manifest.json", manifest)
        if mismatch == "blob":
            (self.root / "source.txt").write_text("a different final source\n", encoding="utf-8")
        if mismatch == "path":
            (self.root / "unrepresented.txt").write_text("extra final source\n", encoding="utf-8")
        producer = self.selector_commit("Freeze complete paired inputs")
        if mismatch == "mode":
            self.selector_git("update-index", "--chmod=+x", "source.txt")
            self.selector_git("commit", "--quiet", "-m", "Retain final source mode")
            producer = self.selector_git("rev-parse", "HEAD")
        self.selector_git("rm", "--quiet", "-r", ".github/performance")
        trailer = f"Performance-Inputs: {producer}:{path}" if malformed is None else malformed.format(producer=producer, path=path, base=base)
        self.selector_git("commit", "--quiet", "-m", "Consume immutable paired inputs\n\n" + trailer)
        return base, producer, self.selector_git("rev-parse", "HEAD"), path, manifest

    def test_clean_commit_trailer_selects_the_complete_pinned_recipe_and_distinct_data_root(self):
        base, producer, head, path, manifest = self.input_consumer(candidate_patch=True)
        self.assertEqual(PAIRED.select_manifest(self.root, base=base, head=head), path)
        self.assertFalse((self.root / ".github/performance").exists())
        with PAIRED.selected_inputs(self.root, path, data_ref=producer) as selection:
            self.assertNotEqual(selection["root"], self.root)
            self.assertEqual(PAIRED.load_manifest(selection["root"], selection["path"]), manifest)
            self.assertEqual(selection["producer"], producer)
            self.assertEqual(selection["path"].read_bytes(), subprocess.check_output(["git", "-C", str(self.root), "show", f"{producer}:{path}"]))
            self.assertEqual(selection["sha256"], PAIRED.digest(selection["path"]))
        self.assertEqual(self.selector_git("rev-parse", "HEAD"), head)
        self.assertEqual(self.selector_git("status", "--porcelain=v1"), "")

    @patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch"})
    def test_explicit_manual_driver_keeps_full_subject_sources_without_copying_them_into_consumer(self):
        base, producer, head, path, manifest = self.input_consumer(candidate_patch=True, separate_driver=True)
        self.assertEqual((self.root / "source.txt").read_text(), "base source\n")
        self.assertEqual(PAIRED.select_manifest(self.root, path), path)
        with PAIRED.selected_inputs(self.root, path, data_ref=producer, expected_head=head) as selection:
            self.assertTrue(selection["explicit_manual"])
            actual = PAIRED.verify_driver_source(self.root, head, manifest, True)
            self.assertEqual(actual["ref"], base)
            self.assertEqual(actual["tree"], self.selector_git("rev-parse", base + "^{tree}"))
            self.assertEqual(actual["execution_head"], head)
            self.assertEqual(actual["execution_tree"], self.selector_git("rev-parse", head + "^{tree}"))
            output = self.root / "subject-verification"
            output.mkdir()
            for side, text in (("baseline", "base source\n"), ("candidate", "candidate source\n")):
                workspace, recorded = PAIRED.materialize(self.root, output, side, manifest["sources"][side], selection["root"])
                self.assertEqual((workspace / "source.txt").read_text(), text)
                self.assertEqual(recorded["tree"], manifest["sources"][side]["tree"])
                PAIRED.source_guard(workspace, recorded["tree"])
                (workspace / "source.txt").write_text("changed subject source\n")
                with self.assertRaises(subprocess.CalledProcessError):
                    PAIRED.source_guard(workspace, recorded["tree"])
        self.assertEqual((self.root / "source.txt").read_text(), "base source\n")
        self.assertEqual(self.selector_git("rev-parse", "HEAD"), head)

    def test_driver_binding_cannot_replace_automatic_pr_candidate_identity(self):
        base, _, head, path, _ = self.input_consumer(candidate_patch=True, separate_driver=True)
        for arguments in ({}, {"base": base, "head": head}, {"explicit": path, "base": base, "head": head}):
            with self.subTest(arguments=arguments), self.assertRaisesRegex(ValueError, "explicit manual"):
                PAIRED.select_manifest(self.root, **arguments)
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "pull_request"}), self.assertRaisesRegex(ValueError, "explicit manual"):
            PAIRED.select_manifest(self.root, path)

    def test_driver_binding_schema_refuses_patches_extra_fields_and_nonexact_identities(self):
        valid = copy.deepcopy(self.manifest)
        valid["driver_source"] = {"ref": "a" * 40, "tree": "b" * 40}
        self.assertEqual(self.load(valid), valid)
        for driver in (None, [], {}, {"ref": "a" * 40}, {"ref": "a" * 40, "tree": "b" * 40, "patches": []},
                       {"ref": "master", "tree": "b" * 40}, {"ref": "a" * 40, "tree": "B" * 40},
                       {"ref": "a" * 40, "tree": None}):
            value = copy.deepcopy(valid)
            value["driver_source"] = driver
            with self.subTest(driver=driver), self.assertRaisesRegex(ValueError, "driver commit and tree"):
                self.load(value)

    @patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch"})
    def test_separate_driver_rejects_wrong_tree_nonancestor_and_actual_working_script_changes(self):
        _, _, head, path, manifest = self.input_consumer(candidate_patch=True, separate_driver=True)
        wrong_tree = copy.deepcopy(manifest)
        wrong_tree["driver_source"]["tree"] = manifest["sources"]["candidate"]["tree"]
        with self.assertRaisesRegex(ValueError, "driver commit or tree changed"):
            PAIRED.verify_driver_source(self.root, head, wrong_tree, True)
        unrelated = self.selector_git("commit-tree", manifest["driver_source"]["tree"], "-m", "Independent same-tree driver")
        unrelated_data = copy.deepcopy(manifest)
        unrelated_data["driver_source"]["ref"] = unrelated
        with self.assertRaisesRegex(ValueError, "not an execution ancestor"):
            PAIRED.verify_driver_source(self.root, head, unrelated_data, True)
        unknown = copy.deepcopy(manifest)
        unknown["driver_source"]["ref"] = "0" * 40
        with self.assertRaises(subprocess.CalledProcessError):
            PAIRED.verify_driver_source(self.root, head, unknown, True)
        self.selector_git("update-index", "--chmod=+x", "gradle/performance/paired-jvm.py")
        with self.assertRaisesRegex(ValueError, "source index or working bytes changed"):
            PAIRED.verify_driver_source(self.root, head, manifest, True)
        self.selector_git("restore", "--staged", "gradle/performance/paired-jvm.py")
        (self.root / "gradle/performance/paired-jvm.py").write_bytes(b"altered live driver source\n")
        with self.assertRaisesRegex(ValueError, "working bytes changed"):
            PAIRED.select_manifest(self.root, path)

    @patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch"})
    def test_separate_driver_retains_existing_subject_tree_pin_and_producer_hash_rejections(self):
        _, producer, _, path, manifest = self.input_consumer(candidate_patch=True, separate_driver=True)
        for arguments in ({"manifest_sha256": "0" * 64}, {"data_ref": "0" * 40}):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                with PAIRED.selected_inputs(self.root, path, **arguments):
                    self.fail("Changed immutable input binding accepted")
        with PAIRED.selected_inputs(self.root, path, data_ref=producer) as selection:
            wrong = copy.deepcopy(manifest["sources"]["candidate"])
            wrong["tree"] = manifest["sources"]["baseline"]["tree"]
            output = self.root / "changed-subject-tree"
            output.mkdir()
            with self.assertRaisesRegex(ValueError, "Frozen patched index changed"):
                PAIRED.materialize(self.root, output, "candidate", wrong, selection["root"])
            patch_pin = manifest["sources"]["candidate"]["patches"][0]
            PAIRED.pin(selection["root"], patch_pin).write_bytes(b"altered subject patch\n")
            with self.assertRaisesRegex(ValueError, "Pinned bytes changed"):
                PAIRED.load_manifest(selection["root"], selection["path"])

    def test_complete_owner_reports_read_only_the_deleted_producer_input_bytes(self):
        relative = ".github/performance/owned/owner-input.json"
        contents = b'{"complete-owner-input":"immutable"}\n'
        value = copy.deepcopy(self.manifest)
        value["owner_reports"] = [self.owner_specification()]
        value["inputs"] = [{"label": "owner-input", "pin": {"path": relative, "sha256": hashlib.sha256(contents).hexdigest()}}]
        _, producer, head, path, manifest = self.input_consumer(value=value, payload={relative: contents})
        self.assertFalse((self.root / relative).exists())
        with PAIRED.selected_inputs(self.root, path, data_ref=producer, expected_head=head) as selection:
            selected = PAIRED.load_manifest(selection["root"], selection["path"])
            self.assertEqual(selected, manifest)
            whole, scope = PAIRED.continuation_scope(selected, None)
            self.assertEqual(whole["owner_reports"], value["owner_reports"])
            self.assertEqual(scope["kind"], "whole-manifest")
            pinned = PAIRED.pin(selection["root"], selected["inputs"][0]["pin"])
            self.assertEqual(pinned.read_bytes(), contents)
            with self.assertRaises(ValueError):
                PAIRED.load_manifest(self.root, selection["path"])
            pinned.write_bytes(b"substituted producer input\n")
            with self.assertRaisesRegex(ValueError, "Pinned bytes changed"):
                PAIRED.load_manifest(selection["root"], selection["path"])
        self.assertEqual(self.selector_git("rev-parse", "HEAD"), head)
        self.assertEqual(self.selector_git("status", "--porcelain=v1"), "")

    def test_no_declaration_skips_cleanup_but_explicit_input_never_uses_a_historical_default(self):
        base = self.selector_repository()
        (self.root / "ordinary.txt").write_text("cleanup source", encoding="utf-8")
        head = self.selector_commit()
        self.assertEqual(PAIRED.select_manifest(self.root, base=base, head=head), "")
        self.assertEqual(PAIRED.select_manifest(self.root), "")
        self.selector_manifest("gradle/performance/paired-jvm.example.json")
        for explicit in ("", "gradle/performance/paired-jvm.example.json", ".github/performance/paired-jvm.json"):
            with self.subTest(explicit=explicit), self.assertRaises(ValueError):
                PAIRED.select_manifest(self.root, explicit)

    def test_tracked_payload_rejects_even_with_no_explicit_intent_or_trailer(self):
        self.selector_repository()
        self.selector_manifest(".github/performance/old/manifest.json")
        self.selector_commit()
        with self.assertRaisesRegex(ValueError, "Execution head contains"):
            PAIRED.select_manifest(self.root)

    def test_missing_duplicate_nontrailer_or_malformed_declarations_do_not_select(self):
        bad = ["Performance-Inputs", "Performance-Inputs: not-a-sha:{path}",
               "performance-inputs: {producer}:{path}", "Performance-Inputs: {producer}:../manifest.json",
               "Performance-Inputs: {producer}:.github/performance-other/manifest.json",
               "Performance-Inputs: {producer}:{path}\nPerformance-Inputs: {producer}:{path}",
               "Performance-Inputs: {producer}:{path}\n\nThis is ordinary final prose."]
        for index, declaration in enumerate(bad):
            with self.subTest(declaration=declaration), tempfile.TemporaryDirectory() as temporary:
                original = self.root
                self.root = Path(temporary)
                (self.root / "fixture.txt").write_text("fixed input\n", encoding="utf-8")
                try:
                    self.input_consumer(malformed=declaration)
                    with self.assertRaises(ValueError):
                        PAIRED.select_manifest(self.root)
                finally:
                    self.root = original

    def test_wrong_producer_parent_and_multiple_head_parents_reject(self):
        base, producer, head, path, _ = self.input_consumer(malformed="Performance-Inputs: {base}:{path}")
        with self.assertRaisesRegex(ValueError, "sole execution-head parent"):
            PAIRED.select_manifest(self.root)
        tree = self.selector_git("rev-parse", head + "^{tree}")
        message = f"Consume inputs\n\nPerformance-Inputs: {producer}:{path}\n"
        merged = subprocess.check_output(["git", "-C", str(self.root), "-c", "commit.gpgsign=false", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit-tree", tree, "-p", producer, "-p", base], input=message.encode()).decode().strip()
        self.selector_git("checkout", "--quiet", "--detach", merged)
        with self.assertRaisesRegex(ValueError, "sole execution-head parent"):
            PAIRED.select_manifest(self.root)

    def test_consumer_nondata_byte_path_and_mode_changes_reject(self):
        _, _, _, _, _ = self.input_consumer()
        (self.root / "source.txt").write_text("changed consumer source\n", encoding="utf-8")
        self.selector_git("add", "source.txt")
        self.selector_git("commit", "--quiet", "--amend", "--no-edit")
        with self.assertRaisesRegex(ValueError, "nondata source or modes"):
            PAIRED.select_manifest(self.root)

    def test_patched_candidate_blob_path_and_mode_mismatch_rejects_current_head_credit(self):
        for mismatch in ("blob", "path", "mode"):
            with self.subTest(mismatch=mismatch), tempfile.TemporaryDirectory() as temporary:
                original = self.root
                self.root = Path(temporary)
                (self.root / "fixture.txt").write_text("fixed input\n", encoding="utf-8")
                try:
                    self.input_consumer(candidate_patch=True, mismatch=mismatch)
                    with self.assertRaisesRegex(ValueError, "Candidate source differs"):
                        PAIRED.select_manifest(self.root)
                finally:
                    self.root = original

    def test_changed_planned_manifest_producer_or_explicit_path_reject_before_run(self):
        _, producer, _, path, _ = self.input_consumer()
        for arguments in ({"explicit":".github/performance/other/manifest.json"}, {"data_ref":"0" * 40},
                          {"data_ref":""}, {"manifest_sha256":"0" * 64}, {"manifest_sha256":""}):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                with PAIRED.selected_inputs(self.root, **arguments):
                    self.fail("Invalid planning identity accepted")

    def test_wrong_checkout_or_partial_pr_binding_rejects(self):
        base, _, head, _, _ = self.input_consumer()
        with self.assertRaisesRegex(ValueError, "checkout differs"):
            PAIRED.select_manifest(self.root, base=base, head=base)
        with self.assertRaisesRegex(ValueError, "both PR"):
            PAIRED.select_manifest(self.root, base=base)

    def test_same_source_empty_commit_cannot_move_head_during_input_materialization(self):
        _, _, head, _, _ = self.input_consumer(candidate_patch=True)
        materialize = PAIRED.materialize
        def move_head(*arguments, **keywords):
            result = materialize(*arguments, **keywords)
            if arguments[2] == "input-producer":
                self.selector_commit("Move head without changing source")
            return result
        with patch.object(PAIRED, "materialize", side_effect=move_head), self.assertRaisesRegex(ValueError, "head changed during materialization"):
            PAIRED.select_manifest(self.root)
        self.assertNotEqual(self.selector_git("rev-parse", "HEAD"), head)

    def test_invalid_producer_schema_and_patched_input_pins_reject(self):
        bad = copy.deepcopy(self.manifest)
        bad["schema"] = "strata-paired-jvm-ci-v2"
        self.input_consumer(value=bad)
        with self.assertRaisesRegex(ValueError, "Unsupported"):
            PAIRED.select_manifest(self.root)

    def test_cli_emits_exact_head_producer_and_manifest_hash_without_a_mutable_body(self):
        base, producer, head, path, _ = self.input_consumer(candidate_patch=True)
        expected_sha = hashlib.sha256(subprocess.check_output(["git", "-C", str(self.root), "show", f"{producer}:{path}"])).hexdigest()
        output = self.root / "github-output"
        console = io.StringIO()
        with patch.object(PAIRED, "__file__", str(self.root / "gradle/performance/paired-jvm.py")), \
                patch.object(PAIRED.sys, "argv", ["paired-jvm.py", "--select", "--base", base, "--head", head]), \
                patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}), redirect_stdout(console):
            PAIRED.main()
        self.assertEqual(console.getvalue(), path + "\n")
        self.assertEqual(output.read_text(), f"paired_manifest={path}\npaired_data_ref={producer}\npaired_manifest_sha256={expected_sha}\n")

    def test_execution_head_and_explicit_empty_flags_refuse_before_collector(self):
        self.selector_repository()
        for flags in (["--manifest", ""], ["--expected-head", "0" * 40], ["--data-ref", ""], ["--manifest-sha256", ""]):
            with self.subTest(flags=flags), patch.object(PAIRED, "__file__", str(self.root / "gradle/performance/paired-jvm.py")), \
                    patch.object(PAIRED.sys, "argv", ["paired-jvm.py", "--output", str(self.root / "fresh-attempt"), *flags]), \
                    patch.object(PAIRED, "run") as collector:
                with self.assertRaises(ValueError):
                    PAIRED.main()
                collector.assert_not_called()
                self.assertFalse((self.root / "fresh-attempt").exists())

    def test_actual_driver_copy_binds_git_blobs_and_raw_crlf_bytes_then_rejects_mutation(self):
        self.selector_repository()
        (self.root / ".gitattributes").write_text("*.py text eol=lf\n", encoding="utf-8")
        for name in ("paired-jvm.py", "invoke-windows.ps1", "bounded-jvm.init.gradle.kts"):
            path = self.root / "gradle/performance" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"original driver source\r\n")
        self.selector_commit()
        records = PAIRED.preserve_driver_sources(self.root, self.root / "retained")
        PAIRED.verify_driver_sources(records)
        self.assertEqual(set(records), {"paired-jvm.py", "invoke-windows.ps1", "bounded-jvm.init.gradle.kts"})
        for item in records.values():
            self.assertEqual(Path(item["path"]).read_bytes(), Path(item["source"]).read_bytes())
            self.assertEqual(item["execution_head"], self.selector_git("rev-parse", "HEAD"))
        Path(records["paired-jvm.py"]["source"]).write_bytes(b"different driver source\r\n")
        with self.assertRaisesRegex(ValueError, "Driver bytes differ"):
            PAIRED.verify_driver_sources(records)

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

    def test_deleted_producer_inputs_keep_logical_paths_and_original_pins_in_fresh_trees(self):
        payload = {".github/performance/owned/object.bin": b"immutable object",
                   ".github/performance/owned/font.json": b"font document"}
        value = copy.deepcopy(self.manifest)
        object_hash = hashlib.sha1(payload[".github/performance/owned/object.bin"]).hexdigest()
        value["inputs"] = [
            {"label": "object", "pin": {"path": ".github/performance/owned/object.bin",
                                       "sha256": hashlib.sha256(payload[".github/performance/owned/object.bin"]).hexdigest()},
             "layout": {"root": "objects", "path": f"{object_hash[:2]}/{object_hash}"}},
            {"label": "custom", "pin": {"path": ".github/performance/owned/font.json",
                                       "sha256": hashlib.sha256(payload[".github/performance/owned/font.json"]).hexdigest()},
             "layout": {"root": "custom", "path": "pack/assets/example/font/default.json"}},
            {"label": "legacy", "pin": self.pin},
        ]
        _, producer, head, path, _ = self.input_consumer(value=value, payload=payload)
        self.assertFalse((self.root / ".github/performance").exists())
        with PAIRED.selected_inputs(self.root, path, data_ref=producer, expected_head=head) as selection:
            frozen = PAIRED.load_manifest(selection["root"], selection["path"])
            with self.assertRaises(ValueError):
                PAIRED.preserve_inputs(self.root, self.root / "wrong-origin", frozen["inputs"])
            for index in range(2):
                owner = self.root / f"fresh-{index}"
                result = PAIRED.preserve_inputs(selection["root"], owner, frozen["inputs"])
                object_path = Path(result["object"]["path"])
                self.assertEqual(object_path.parent.parent, owner / "objects")
                self.assertEqual(object_path.read_bytes(), payload[".github/performance/owned/object.bin"])
                font_path = Path(result["custom"]["path"])
                self.assertEqual(font_path.parents[3], owner / "custom/pack")
                self.assertEqual(font_path.read_bytes(), payload[".github/performance/owned/font.json"])
                self.assertEqual(Path(result["legacy"]["path"]), owner / "input-2.bin")
                for item in frozen["inputs"]:
                    self.assertEqual(result[item["label"]]["sha256"], item["pin"]["sha256"])
            (selection["root"] / frozen["inputs"][0]["pin"]["path"]).write_bytes(b"changed producer object")
            with self.assertRaisesRegex(ValueError, "Pinned bytes changed"):
                PAIRED.preserve_inputs(selection["root"], self.root / "changed-producer", frozen["inputs"])
        self.assertEqual(self.selector_git("rev-parse", "HEAD"), head)
        self.assertEqual(self.selector_git("status", "--porcelain=v1", "--untracked-files=no"), "")

    def test_logical_input_layout_rejects_traversal_nonportable_names_and_destination_collisions(self):
        def item(root, path, label="input"):
            return {"label": label, "pin": self.pin, "layout": {"root": root, "path": path}}
        invalid = [[item(root, "file")] for root in ("", "../root", "root/child", "C:", "a" * 65, "NUL")]
        invalid += [[item("group", path)] for path in ("", ".", "../file", "nested/../file", "/absolute", "C:/file", "folder\\file",
                                                        "nested//file", "./file", ".git/file", ".GIT/file", "NUL.txt", "nested/COM1.dat",
                                                        "CONIN$", "nested/conout$.txt", "file.", "a" * 4097)]
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

    def test_logical_inputs_preserve_existing_external_archive_suffixes_and_explicit_names(self):
        inputs = [{"label": "archive", "pin": self.pin}, {"label": "flat", "pin": self.pin}]
        result = PAIRED.preserve_inputs(self.root, self.root / "external", inputs, {"archive"})
        self.assertEqual(Path(result["archive"]["path"]).name, "input-0.jar")
        self.assertEqual(Path(result["flat"]["path"]).name, "input-1.bin")
        inputs[0]["layout"] = {"root": "group", "path": "assets/original.jar"}
        result = PAIRED.preserve_inputs(self.root, self.root / "explicit", inputs, {"archive"})
        self.assertEqual(Path(result["archive"]["path"]), self.root / "explicit/group/assets/original.jar")
        self.assertEqual(result["archive"]["sha256"], self.pin["sha256"])

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

    def owner_specification(self):
        return {"name": "independent-owner", "project": ":runtime:headless", "template": "test", "kind": "test",
                "main": "independent.OwnerEvidence", "comparison": "independent.OwnerComparison", "workload_id": "independent-owner-v1",
                "phase_keys": ["workload"], "rows": [{"workload": "all"}, {"workload": "control"}], "warmup": 100, "samples": 200,
                "runtime_classes": {"headless": {"project": ":runtime:headless", "class": "independent.Runtime"}}}

    def test_owner_reports_are_independent_of_jmh_rows_and_reject_incomplete_bindings(self):
        value = copy.deepcopy(self.manifest)
        value["runtime_projects"] += [":velocity-api", ":runtime:velocity"]
        value["owner_reports"] = [self.owner_specification()]
        self.assertEqual(self.load(value), value)
        changes = [lambda report: report.update(kind="shell"), lambda report: report.update(template="test --tests subset"),
                   lambda report: report.update(main="bad;Main"), lambda report: report.update(rows=[]),
                   lambda report: report["rows"].append(report["rows"][0]), lambda report: report.update(samples=True),
                   lambda report: report.update(phase_keys=[]), lambda report: report.update(project=":runtime:unknown"),
                   lambda report: report["runtime_classes"]["headless"].update(project=":unknown"),
                   lambda report: report.update(command="arbitrary")]
        for change in changes:
            changed = copy.deepcopy(value)
            change(changed["owner_reports"][0])
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.load(changed)

    def test_owner_raw_rejects_substitution_reused_uuid_partial_rows_and_changed_profile(self):
        archive = self.root / "actual.jar"
        binary = b"actual compiled representative"
        with zipfile.ZipFile(archive, "w") as target:
            target.writestr("independent/Runtime.class", binary)
        runtime = {":runtime:headless": {"path": str(archive), "sha256": PAIRED.digest(archive)}}
        specification = self.owner_specification()
        identity = "12345678-1234-1234-1234-123456789abc"
        report = {"schema_version": 1, "status": "passed", "workload_id": "independent-owner-v1", "repetition": 0, "run_id": identity,
                  "phases": [{"workload": row["workload"], "warmup": 100, "samples": 200} for row in specification["rows"]],
                  "runtime_metadata": {"modules": [{"module": "headless", "status": "resolved", "representativeClass": "independent.Runtime",
                                                    "codeSource": {"status": "available", "sha256": PAIRED.digest(archive)},
                                                    "classResource": {"status": "available", "sha256": hashlib.sha256(binary).hexdigest()},
                                                    "classTree": {"status": "available"}}]}}
        seen = set()
        PAIRED.verify_owner_report(report, specification, 0, runtime, seen)
        self.assertEqual(seen, {identity})
        with self.assertRaises(ValueError):
            PAIRED.verify_owner_report(report, specification, 0, runtime, seen)
        changes = [lambda value: value["phases"].pop(), lambda value: value["phases"][0].update(samples=199),
                   lambda value: value["phases"][0].update(workload="substituted"), lambda value: value.update(repetition=1),
                   lambda value: value["runtime_metadata"]["modules"][0]["codeSource"].update(sha256="0" * 64),
                   lambda value: value["runtime_metadata"]["modules"][0]["classResource"].update(sha256="0" * 64)]
        for change in changes:
            changed = copy.deepcopy(report)
            change(changed)
            with self.subTest(change=change), self.assertRaises(ValueError):
                PAIRED.verify_owner_report(changed, specification, 0, runtime, set())

    def test_owner_processing_uses_selected_copy_and_rejects_changed_or_shadowed_collectors(self):
        original = self.root / "original-collector.jar"
        retained = self.root / "prepared/classpath-4.jar"
        retained.parent.mkdir()
        resource = "dev/s7a/strata/performance/JvmPerformanceMeter.class"
        with zipfile.ZipFile(original, "w") as archive:
            archive.writestr(resource, b"original collector")
        retained.write_bytes(original.read_bytes())
        inventory = retained.parent / "classpath.json"
        entry = {"source": str(original), "path": str(retained), "sha256": PAIRED.digest(original)}
        inventory.write_text(json.dumps({"schema": "strata-paired-jvm-classpath-v1", "entries": [entry]}), encoding="utf-8")
        self.assertEqual(PAIRED.owner_processing_collector(inventory, original), str(retained))
        self.assertNotEqual(str(original), str(retained))
        other = self.root / "substituted.jar"
        with zipfile.ZipFile(other, "w") as archive:
            archive.writestr(resource, b"substituted collector")
        with self.assertRaises(ValueError):
            PAIRED.owner_processing_collector(inventory, other)
        shadow = retained.parent / "classpath-5.jar"
        shadow.write_bytes(other.read_bytes())
        entries = [entry, {"source": str(other), "path": str(shadow), "sha256": PAIRED.digest(other)}]
        inventory.write_text(json.dumps({"schema": "strata-paired-jvm-classpath-v1", "entries": entries}), encoding="utf-8")
        with self.assertRaises(ValueError):
            PAIRED.owner_processing_collector(inventory, original)
        inventory.write_text(json.dumps({"schema": "strata-paired-jvm-classpath-v1", "entries": [entry]}), encoding="utf-8")
        retained.write_bytes(other.read_bytes())
        with self.assertRaises(ValueError):
            PAIRED.owner_processing_collector(inventory, original)

    def test_automatic_complete_continuation_requires_exact_original_runtime_and_collector(self):
        value = copy.deepcopy(self.manifest)
        value["owner_reports"] = [self.owner_specification()]
        jar = self.root / "original.jar"
        jar.write_bytes(b"original compiled archive")
        sha = PAIRED.digest(jar)
        value["owner_continuation"] = {"name": "independent-owner", "runtime_sha256": {
            side: {project: sha for project in value["runtime_projects"]} for side in ("baseline", "candidate")}, "collector_sha256": sha}
        self.assertEqual(self.load(value), value)
        selected, scope = PAIRED.continuation_scope(value, None)
        self.assertEqual(selected["owner_reports"], value["owner_reports"])
        self.assertEqual(scope["complete_rows"], 2)
        self.assertFalse(scope["whole_issue_acceptance"])
        self.assertFalse(scope["prior_rows_adopted"])
        runtime = {side: {project: {"path": str(jar)} for project in value["runtime_projects"]} for side in ("baseline", "candidate")}
        PAIRED.verify_continuation_archives(selected, runtime, jar)
        with self.assertRaises(ValueError):
            PAIRED.continuation_scope(value, "another-owner")
        changed = self.root / "substituted.jar"
        changed.write_bytes(b"substituted compiled archive")
        for side in ("baseline", "candidate"):
            redirected = copy.deepcopy(runtime)
            redirected[side][value["runtime_projects"][0]]["path"] = str(changed)
            with self.subTest(side=side), self.assertRaises(ValueError):
                PAIRED.verify_continuation_archives(selected, redirected, jar)
        with self.assertRaises(ValueError):
            PAIRED.verify_continuation_archives(selected, runtime, changed)
        changes = [lambda option: option.update(name="independent-owner:row-1"),
                   lambda option: option.update(rows=["control"]),
                   lambda option: option["runtime_sha256"].pop("baseline"),
                   lambda option: option["runtime_sha256"]["candidate"].pop(value["runtime_projects"][0]),
                   lambda option: option.update(collector_sha256="missing")]
        for change in changes:
            negative = copy.deepcopy(value)
            change(negative["owner_continuation"])
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.load(negative)

    def test_complete_owner_continuation_keeps_all_rows_source_pins_and_outer_inventory(self):
        value = copy.deepcopy(self.manifest)
        report = self.owner_specification()
        report["name"] = "complete-owner"
        report["rows"] = [{"workload": str(index)} for index in range(8)]
        value["owner_reports"] = [report]
        full, full_scope = PAIRED.continuation_scope(value, None)
        self.assertIs(full, value)
        self.assertEqual(full_scope["kind"], "whole-manifest")
        selected, scope = PAIRED.continuation_scope(value, "complete-owner")
        self.assertEqual(selected["owner_reports"], [report])
        self.assertEqual(selected["suites"], [])
        for key in ("sources", "runtime_projects", "inputs", "compiled_pins", "dependency_pins", "java_pins"):
            self.assertEqual(selected[key], value[key])
        self.assertEqual(scope["complete_rows"], 8)
        self.assertFalse(scope["prior_rows_adopted"])
        self.assertFalse(scope["whole_issue_acceptance"])
        self.assertEqual(scope["original_jmh_suites"], [{"name": value["suites"][0]["name"], "complete_rows": 2}])
        self.assertEqual(len(value["suites"]), 1)
        for name in ("missing", "complete-owner:row-1", "complete"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                PAIRED.continuation_scope(value, name)
        duplicate = copy.deepcopy(value)
        duplicate["owner_reports"].append(report)
        with self.assertRaises(ValueError):
            PAIRED.continuation_scope(duplicate, "complete-owner")
        paired = copy.deepcopy(value)
        paired["owner_reports"][0]["paired_jmh"] = {"suite": value["suites"][0]["name"], "fixture_class": "example.Fixture"}
        with self.assertRaises(ValueError):
            PAIRED.continuation_scope(paired, "complete-owner")

    def test_owner_table_keeps_cycle_units_nulls_and_adverse_deltas(self):
        specification = self.owner_specification()
        comparison = {"phases": [{"workload": "all", "before": {"owner_cpu_ns_per_cycle": None, "owner_allocated_bytes_per_cycle": 4.0},
                                  "after": {"owner_cpu_ns_per_cycle": None, "owner_allocated_bytes_per_cycle": 7.0},
                                  "delta": {"owner_cpu_ns_per_cycle": None, "owner_allocated_bytes_per_cycle": 3.0}}]}
        PAIRED.table(self.root, [], [], owners=[(specification, comparison)])
        with (self.root / "table.csv").open(encoding="utf-8", newline="") as source:
            rows = list(csv.DictReader(source))
        self.assertEqual(rows[0]["unit"], "ns/cycle")
        self.assertEqual(rows[0]["baseline"], "")
        self.assertEqual(rows[1]["unit"], "B/cycle")
        self.assertEqual(rows[1]["delta"], "3.0")

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
        for task in (":runtime:headless:validateRuntime", ":runtime:headless:check"):
            generic = copy.deepcopy(value)
            generic["suites"][0]["test_gates"][0]["task"] = task
            self.assertEqual(self.load(generic), generic)
        changes = [lambda gate: gate.update(task=":quality:benchmarks:test"),
                   lambda gate: gate.update(task="runtime:headless:test"),
                   lambda gate: gate.update(task=":runtime:headless:"),
                   lambda gate: gate.update(task=":runtime:headless::test"),
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

    def test_whole_preparation_resolves_all_test_classpaths_before_pins_and_aborts_on_changed_dependencies(self):
        """Use synthetic physical-call receipts, as in the browser control; launch no Java or native helper."""
        self.root.joinpath("gradle").mkdir()
        self.root.joinpath("gradle/libs.versions.toml").write_text(
            '[versions]\nperformance-toolchain="17.0.1+1"\nperformance-launcher="25.0.1"\n', encoding="utf-8")
        homes = {}
        for role, provider, version in (("toolchain", "Eclipse Adoptium", "17.0.1"), ("launcher", "Oracle Corporation", "25.0.1")):
            home = self.root / role
            home.joinpath("bin").mkdir(parents=True)
            home.joinpath("bin/java.exe").write_bytes(b"synthetic Java; never launched")
            home.joinpath("release").write_text(f'IMPLEMENTOR="{provider}"\nJAVA_VERSION="{version}"\n', encoding="utf-8")
            homes[role] = home
        helper = self.root / "pwsh.exe"
        helper.write_bytes(b"synthetic helper; never launched")
        executable = Path(PAIRED.sys.executable).resolve()
        gates = [{"task": task, "required_tests": ["independent.Controls#owner", "independent.Controls#failure"]}
                 for task in (":runtime:core:validateRuntime", ":runtime:headless:completeChecks")]
        library_bytes = b"synthetic resolved Test dependency"
        pins = [{"path": f"modules/dependency-{index}.jar", "sha256": hashlib.sha256(library_bytes).hexdigest()} for index in range(13)]

        for mode in ("complete", "invalid", "missing", "mutated", "no-gates"):
            with self.subTest(mode=mode):
                data = copy.deepcopy(self.manifest)
                data["sources"]["baseline"]["tree"] = "c" * 40
                data["suites"][0]["test_gates"] = copy.deepcopy(gates)
                data["suites"].append({**copy.deepcopy(data["suites"][0]), "name": "additional", "test_gates": [copy.deepcopy(gates[0])]})
                if mode == "no-gates":
                    for suite in data["suites"]:
                        suite.pop("test_gates")
                data["dependency_pins"] = copy.deepcopy(pins)
                if mode == "invalid":
                    data["dependency_pins"][-1]["sha256"] = "invalid"
                manifest = self.root / (mode + "-manifest.json")
                manifest.write_text(json.dumps(data), encoding="utf-8")
                cache = self.root / (mode + "-cache")
                output = self.root / (mode + "-attempt")
                events, validated = [], []
                preparation_arguments = PAIRED.test_classpath_preparation(data)
                property_arguments = [arg for arg in preparation_arguments if arg.startswith("-P")]
                preparation_tasks = [arg for arg in preparation_arguments if arg.startswith(":")]
                expected_tasks = sorted({gate["task"] for suite in data["suites"] for gate in suite.get("test_gates", [])})

                def resolve_dependencies():
                    for item in pins[:-1] if mode == "missing" else pins:
                        library = cache / item["path"]
                        library.parent.mkdir(parents=True, exist_ok=True)
                        library.write_bytes(library_bytes)

                if mode == "no-gates":
                    resolve_dependencies()

                def materialize(root, attempt, side, source, input_root):
                    workspace = attempt / "workspaces" / side
                    workspace.mkdir(parents=True)
                    for project in data["runtime_projects"] + [":performance-testkit"]:
                        archive = workspace.joinpath(*project.strip(":").split(":"), "build/libs/synthetic.jar")
                        archive.parent.mkdir(parents=True, exist_ok=True)
                        archive.write_bytes(b"synthetic compiled bytes")
                    for relative in ("classes/kotlin/jmh", "jmh-generated-classes", "jmh-generated-resources"):
                        workspace.joinpath("quality/benchmarks/build", relative).mkdir(parents=True)
                    return workspace, source

                def checked_pin(root, item):
                    if root == cache:
                        events.append(("pin", item["path"]))
                    result = original_pin(root, item)
                    if root == cache:
                        validated.append(item["path"])
                    return result

                def invoke(arguments, root, console):
                    request = PAIRED.read_json(Path(arguments[arguments.index("-Request") + 1]))
                    terminal_path = Path(arguments[arguments.index("-Report") + 1])
                    name, args = terminal_path.parent.name, request["arguments"]
                    events.append(("invoke", name))
                    if name.startswith("prepare-"):
                        self.assertEqual([arg for arg in args if arg == ":strataPairedPrepareTestClasspaths" or arg.startswith("-Pstrata.paired.prepareTestClasspaths=")], preparation_arguments)
                        tasks = [PAIRED.archive_task(project) for project in data["runtime_projects"]]
                        if name == "prepare-candidate":
                            tasks += [":performance-testkit:jvmJar", ":quality:benchmarks:jmhCompileGeneratedClasses"]
                        self.assertEqual([arg for arg in args if arg.startswith(":")], tasks + preparation_tasks)
                        self.assertFalse(any(task in args for task in expected_tasks))
                        self.assertEqual(validated, [])
                        if property_arguments:
                            self.assertEqual(property_arguments[0].split("=", 1)[1].split(","), expected_tasks)
                            resolve_dependencies()
                            if mode == "mutated" and name == "prepare-candidate":
                                (cache / pins[0]["path"]).write_bytes(b"mutated archive")
                    else:
                        self.assertEqual(validated, [item["path"] for item in pins])
                    if name.startswith("collect-"):
                        raise ValueError("Synthetic timing boundary; no measurement")
                    if name.startswith("tests-"):
                        gate = next(gate for gate in gates if gate["task"] == args[0])
                        reports = Path(request["workspace"]).joinpath(*gate["task"].strip(":").split(":")[:-1], "build/test-results", gate["task"].split(":")[-1])
                        reports.mkdir(parents=True, exist_ok=True)
                        reports.joinpath("TEST-independent.Controls.xml").write_text(
                            '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase classname="independent.Controls" name="owner()"/>'
                            '<testcase classname="independent.Controls" name="failure()"/></testsuite>', encoding="utf-8")
                    console.write_bytes(b"synthetic command transcript\n")
                    PAIRED.write_new(terminal_path, {"exitCode": 0, "naturalRelease": True, "handlesClosed": True,
                                                   "jobName": name, "inheritedAffinityMask": 15, "systemAffinityMask": 15})
                    PAIRED.write_new(terminal_path.parent / "child-admission.json", {"inOwnedJob": True, "jobName": name, "availablePhysicalBytes": 4 * 1024**3})
                    return 0

                original_pin = PAIRED.pin
                with patch.multiple(PAIRED, git=lambda *args: "a" * 40, materialize=materialize, invoke=invoke, pin=checked_pin,
                                    verify_driver_source=lambda *args: None, preserve_driver_sources=lambda *args: {},
                                    verify_driver_sources=lambda *args: None, source_guard=lambda *args: None,
                                    verify_vms=lambda *args: None, class_load_inventory=lambda *args: {}, preserve_class_loads=lambda *args: {}), \
                     patch.object(PAIRED, "os", SimpleNamespace(name="nt", path=os.path, environ=os.environ)), \
                     patch.object(PAIRED, "sys", SimpleNamespace(executable=str(executable))), \
                     patch.dict(os.environ, {"JAVA_HOME": str(homes["launcher"]), "STRATA_PAIRED_TOOLCHAIN": str(homes["toolchain"]), "GRADLE_USER_HOME": str(cache)}), \
                     patch.object(PAIRED.shutil, "which", return_value=str(helper)), patch.object(PAIRED.subprocess, "check_output", return_value="{}"):
                    error = {"invalid": "Invalid dependency pin", "missing": "Missing or unsafe pinned file", "mutated": "Pinned bytes changed"}.get(mode, "Synthetic timing boundary")
                    with self.assertRaisesRegex(ValueError, error), redirect_stdout(io.StringIO()):
                        PAIRED.run(self.root, manifest, output)
                preparations = [("invoke", "prepare-baseline"), ("invoke", "prepare-candidate")]
                self.assertEqual(events[:2], [] if mode == "invalid" else preparations)
                dependency_checks = [name for kind, name in events if kind == "pin"]
                expected_pins = [] if mode == "invalid" else pins[:1] if mode == "mutated" else pins
                self.assertEqual(dependency_checks, [item["path"] for item in expected_pins])
                calls = [name for kind, name in events if kind == "invoke"]
                if mode in ("invalid", "missing", "mutated"):
                    self.assertEqual(calls, [] if mode == "invalid" else [name for _, name in preparations])
                else:
                    executed = [name for name in calls if name.startswith("tests-")]
                    self.assertEqual(len(executed), 0 if mode == "no-gates" else 6)
                    for name in executed:
                        self.assertEqual(PAIRED.read_json(output / "phases" / name / "junit-verified.json")["required_tests"], gates[0]["required_tests"])
                    self.assertTrue(calls[-1].startswith("collect-"))
                self.assertEqual(PAIRED.read_json(output / "attempt.json")["status"], "failed")
                if mode != "invalid":
                    self.assertEqual(PAIRED.read_json(output / "attempt.json")["executables"][0],
                                     {"path": str(executable), "sha256": PAIRED.digest(executable)})
                self.assertFalse((output / "raw").exists())
                self.assertTrue(output.with_suffix(".zip").is_file())

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

    def test_independent_junit_gate_accepts_actual_jvm_platform_names_without_accepting_other_platforms_or_duplicates(self):
        gate = {"task": ":runtime:core:jvmTest", "required_tests": ["independent.Controls#owner", "independent.Controls#failure"]}
        reports = self.root / "runtime/core/build/test-results/jvmTest"
        reports.mkdir(parents=True)
        cases = [
            (("owner()[jvm]", "failure()[jvm]"), True),
            (("owner()[jvm]", "failure()"), True),
            (("owner()[js]", "failure()[jvm]"), False),
            (("owner()[jvm][jvm]", "failure()[jvm]"), False),
            (("owner()[jvm]", "owner()", "failure()[jvm]"), False),
        ]
        for index, (names, accepted) in enumerate(cases):
            with self.subTest(names=names):
                directory = self.root / f"jvm-platform-{index}"
                directory.mkdir()
                report = reports / "TEST-independent.Controls.xml"
                body = ''.join(f'<testcase classname="independent.Controls" name="{name}"/>' for name in names)
                report.write_text(f'<testsuite tests="{len(names)}" failures="0" errors="0" skipped="0">{body}</testsuite>', encoding="utf-8")
                PAIRED.preserve_test_reports(self.root, directory, gate)
                if accepted:
                    PAIRED.verify_test_gate(directory, gate)
                    self.assertEqual(PAIRED.read_json(directory / "junit-verified.json")["counts"]["tests"], len(names))
                else:
                    with self.assertRaises(ValueError):
                        PAIRED.verify_test_gate(directory, gate)
                    self.assertFalse((directory / "junit-verified.json").exists())

    def preflight_fixture(self, name, input_root=None, qualification=None):
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
        suite["preflight"] = {"main": names[0], "qualification": self.pin if qualification is None else qualification,
                              "runtime_classes": {project: names[1] for project in runtime},
                              "controls": [{"id": "independent-a", "arguments": []}, {"id": "independent-b", "arguments": []}]}
        inputs = {"input": PAIRED.preserve_file(self.input, output / "inputs" / "input.bin")}
        directory = PAIRED.preflight_request(self.root if input_root is None else input_root, output, suite, "baseline", retained / "classpath.json", runtime, {}, {}, inputs)
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

    def test_qualified_preflight_reads_exact_distinct_data_root_bytes(self):
        data_root = self.root / "distinct-producer-inputs"
        data_root.mkdir()
        qualification = data_root / "fixture.txt"
        qualification.write_bytes(b"independently qualified producer bytes")
        pin = {"path":"fixture.txt", "sha256":PAIRED.digest(qualification)}
        directory, _, _, _ = self.preflight_fixture("distinct-data-preflight", data_root, pin)
        self.assertEqual((directory / "qualification.json").read_bytes(), qualification.read_bytes())
        self.assertNotEqual(qualification.read_bytes(), self.input.read_bytes())
        with self.assertRaisesRegex(ValueError, "Pinned bytes changed"):
            self.preflight_fixture("wrong-root-preflight", self.root, pin)

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

    def test_reused_pid_preserves_both_completed_fork_generations_and_rejects_ambiguous_or_altered_history(self):
        home = self.root / "reuse-jdk"
        executable = home / "bin/java.exe"
        executable.parent.mkdir(parents=True)
        executable.write_bytes(b"frozen executable")
        allowed = {os.path.normcase(str(executable)): PAIRED.digest(executable)}
        epoch = 116444736000000000
        records = [{"pid": 123, "inOwnedJob": True, "completed": True, "handleClosed": True, "exitCode": 0,
                    "creationFileTime": epoch + start * 10000, "exitFileTime": epoch + (start + 1000) * 10000,
                    "image": str(executable), "affinityMask": 15, "systemAffinityMask": 15} for start in (1000, 5000)]
        changes = [lambda history: None, lambda history: history.pop(0),
                   lambda history: history.append(copy.deepcopy(history[0])),
                   lambda history: history[0].update(pid=999), lambda history: history[0].update(completed=False),
                   lambda history: history[0].update(handleClosed=False), lambda history: history[0].update(exitCode=1),
                   lambda history: history[0].update(affinityMask=3),
                   lambda history: history[0].update(creationFileTime=epoch + 2000 * 10000),
                   lambda history: history[0].update(exitFileTime=epoch + 500 * 10000),
                   lambda history: history.append({**history[0], "creationFileTime": epoch + 999 * 10000})]
        for index, change in enumerate(changes):
            with self.subTest(change=index):
                directory = self.root / f"pid-reuse-{index}"
                (directory / "class-loads").mkdir(parents=True)
                (directory / "console.log").write_text("", encoding="utf-8")
                files = []
                for start, stamp in ((1000, "2026-10-12_00-00-01"), (5000, "2026-10-12_00-00-05")):
                    log = directory / f"vm-pid123-{stamp}.log"
                    log.write_text(f"<hotspot_log process='123' time_ms='{start + 1}'>\n<vm_arguments>"
                                   f"<command>org.openjdk.jmh.runner.ForkedMain 1</command>\njava.home={home}\n"
                                   "java.class.path=frozen.jar\n</vm_arguments>\n"
                                   "-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n"
                                   "<hotspot_log_done stamp='1'/>\n</hotspot_log>\n", encoding="utf-8")
                    (directory / f"class-loads/classes-123-{stamp}.log").write_text(
                        "[123][info][class,load] org.openjdk.jmh.runner.ForkedMain source: file:/frozen.jar\n", encoding="utf-8")
                    files.append(log)
                history = copy.deepcopy(records)
                change(history)
                terminal = {"members": {"123": history}, "inheritedAffinityMask": 15, "systemAffinityMask": 15}
                if index == 0:
                    PAIRED.verify_vms(directory, files, terminal, allowed, 2)
                    vms = PAIRED.read_json(directory / "vms.json")
                    self.assertEqual([vm["os_generation"] for vm in vms], records)
                    self.assertEqual(len({vm["class_load_diagnostic"]["path"] for vm in vms}), 2)
                    self.assertEqual(terminal["members"]["123"], records)
                else:
                    with self.assertRaises(ValueError):
                        PAIRED.verify_vms(directory, files, terminal, allowed, 2)
                    self.assertFalse((directory / "vms.json").exists())

    def test_reused_fork_startup_filename_binds_actual_generation_and_rejects_drift_or_ambiguity(self):
        for mode in ("complete", "before-birth", "after-start", "ambiguous", "changed-pin", "stale", "wrong-birth", "unclosed", "missing-main", "wrong-flag"):
            with self.subTest(mode=mode):
                directory = self.root / ("startup-reuse-" + mode)
                owner = directory / "class-loads"
                owner.mkdir(parents=True)
                (directory / "console.log").write_text("", encoding="utf-8")
                home = directory / "jdk"
                executable = home / "bin/java.exe"
                executable.parent.mkdir(parents=True)
                executable.write_bytes(b"pinned executable; never launched")
                starts = (1792143444000, 1792143587000)
                births = (starts[0] - 500, starts[1] - 53)
                history, files, pins = [], [], []
                for index, (start, birth) in enumerate(zip(starts, births)):
                    stamp = PAIRED.time.strftime("%Y-%m-%d_%H-%M-%S", PAIRED.time.gmtime(start // 1000))
                    class_second = (birth if index else start) // 1000
                    if index and mode in ("before-birth", "after-start"):
                        class_second += -1 if mode == "before-birth" else 2
                    class_stamp = PAIRED.time.strftime("%Y-%m-%d_%H-%M-%S", PAIRED.time.gmtime(class_second))
                    log = directory / f"vm-pid123-{stamp}.log"
                    flag = (owner / "classes-%p-%t.log").as_posix()
                    if index and mode == "wrong-flag":
                        flag = flag.replace("%p", "%p%p")
                    log.write_text(f"<hotspot_log process='123' time_ms='{start}'>\n<vm_arguments><args>"
                                   f"-Xlog:class+load=info:file={flag}:pid,level,tags:filecount=0</args>"
                                   f"<command>org.openjdk.jmh.runner.ForkedMain 1</command>\njava.home={home}\n"
                                   "java.class.path=frozen.jar\n</vm_arguments>\n"
                                   "-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n"
                                   "<hotspot_log_done stamp='1'/>\n</hotspot_log>\n", encoding="utf-8")
                    diagnostic = owner / f"classes-123-{class_stamp}.log"
                    main = "independent.WrongMain" if index and mode == "missing-main" else "org.openjdk.jmh.runner.ForkedMain"
                    diagnostic.write_text(f"[123][info][class,load] {main} source: file:/frozen.jar\n", encoding="utf-8")
                    pins.append({"source": str(diagnostic), "path": str(diagnostic), "sha256": PAIRED.digest(diagnostic)})
                    history.append({"pid": 123, "creationFileTime": 116444736000000000 + birth * 10000,
                                    "exitFileTime": 116444736000000000 + (start + 10000) * 10000,
                                    "image": str(executable), "affinityMask": 15, "systemAffinityMask": 15,
                                    "inOwnedJob": True, "completed": True, "handleClosed": True, "exitCode": 0})
                    files.append(log)
                if mode == "ambiguous":
                    extra = owner / "classes-123-2026-10-16_09-39-47.log"
                    extra.write_bytes(diagnostic.read_bytes())
                    pins.append({"source": str(extra), "path": str(extra), "sha256": PAIRED.digest(extra)})
                elif mode == "changed-pin":
                    pins[-1]["sha256"] = "0" * 64
                elif mode == "wrong-birth":
                    history[-1]["creationFileTime"] = 116444736000000000 + (starts[-1] + 1) * 10000
                elif mode == "unclosed":
                    history[-1]["completed"] = False
                PAIRED.write_new(directory / "class-load-inventory.json", {"owner": str(owner), "copied": pins,
                                 "previous": {diagnostic.name: PAIRED.digest(diagnostic)} if mode == "stale" else {}})
                terminal = {"members": {"123": history}, "inheritedAffinityMask": 15, "systemAffinityMask": 15}
                with patch.object(PAIRED.time, "localtime", side_effect=PAIRED.time.gmtime):
                    if mode == "complete":
                        PAIRED.verify_vms(directory, files, terminal, {os.path.normcase(str(executable)): PAIRED.digest(executable)}, 2)
                        records = PAIRED.read_json(directory / "vms.json")
                        self.assertEqual([record["os_generation"] for record in records], history)
                        self.assertEqual(Path(records[-1]["class_load_diagnostic"]["path"]), diagnostic)
                        self.assertEqual(records[-1]["class_load_diagnostic"]["startup_filename_stamps"], ["2026-10-16_09-39-46", "2026-10-16_09-39-47"])
                    else:
                        with self.assertRaises(ValueError):
                            PAIRED.verify_vms(directory, files, terminal, {os.path.normcase(str(executable)): PAIRED.digest(executable)}, 2)
                        self.assertFalse((directory / "vms.json").exists())

    def test_control_selects_actual_birth_without_borrowing_a_reused_pid_and_rejects_ambiguous_vm_origin(self):
        for mode in ("history", "ambiguous-vm", "wrong-birth", "active", "wrong-affinity"):
            with self.subTest(mode=mode):
                directory, phase, terminal, receipt = self.preflight_fixture("reuse-control-" + mode)
                original = terminal["members"]["123"]
                original.update(pid=123, exitFileTime=116444736020000000, handleClosed=True)
                later = {**original, "creationFileTime": 116444736050000000, "exitFileTime": 116444736060000000}
                terminal["members"]["123"] = [original, later]
                if mode == "ambiguous-vm":
                    vms = PAIRED.read_json(phase / "vms.json")
                    vms.append({**vms[0], "vm_start_ms": 5001})
                    (phase / "vms.json").write_text(json.dumps(vms), encoding="utf-8")
                elif mode == "wrong-birth":
                    original["creationFileTime"] = 116444736030000000
                elif mode == "active":
                    original["completed"] = False
                elif mode == "wrong-affinity":
                    original["affinityMask"] = 3
                PAIRED.write_new(directory / "receipt.json", receipt)
                if mode == "history":
                    PAIRED.verify_preflight(directory, phase, terminal, PAIRED.digest(directory / "request.json"))
                    self.assertTrue((directory / "verified.json").is_file())
                else:
                    with self.assertRaises(ValueError):
                        PAIRED.verify_preflight(directory, phase, terminal, PAIRED.digest(directory / "request.json"))
                    self.assertFalse((directory / "verified.json").exists())

    def test_independent_file_flags_bind_reused_control_pids_without_inventing_a_working_directory(self):
        modes = ("positive", "changed-flag", "changed-vm", "changed-file", "missing-file", "wrong-pid", "truncated",
                 "ambiguous-file", "ambiguous-vm", "wrong-birth", "wrong-origin", "reordered-controls", "console-only", "oversized")
        for mode in modes:
            with self.subTest(mode=mode):
                directory, phase, terminal, receipt = self.preflight_fixture("independent-files-" + mode)
                original = terminal["members"]["123"]
                original.update(pid=123, exitFileTime=116444736020000000, handleClosed=True)
                later = {**original, "creationFileTime": 116444736050000000, "exitFileTime": 116444736060000000}
                terminal["members"]["123"] = [original, later]
                classes = (phase / "console.log").read_text(encoding="utf-8")
                (phase / "console.log").write_text(classes + classes, encoding="utf-8")
                (directory.parents[2] / "vms").mkdir()
                template = PAIRED.read_json(phase / "vms.json")[0]
                vms, paths = [], []
                for index, result in enumerate(receipt["controls"]):
                    path = directory / result["id"] / "class-load.log"
                    path.parent.mkdir()
                    path.write_text(classes, encoding="utf-8")
                    artifact = {"path": path.relative_to(directory).as_posix(), "sha256": PAIRED.digest(path)}
                    result["artifacts"].append(artifact)
                    flag = "../../" + result["id"] + "/class-load.log"
                    start = (1001, 5001)[index]
                    log = directory.parents[2] / "vms" / f"vm-{index}.log"
                    log.write_text(f"<hotspot_log process='123' time_ms='{start}'><vm_arguments>"
                                   f"<args>-Xlog:class+load=info:file={flag}:pid,level,tags:filecount=0 </args>"
                                   "</vm_arguments><hotspot_log_done/></hotspot_log>\n", encoding="utf-8")
                    vms.append({**template, "vm_start_ms": start, "diagnostic_path": str(log),
                                "diagnostic_sha256": PAIRED.digest(log), "class_load_file_flags": [flag]})
                    paths.append(path)
                if mode == "changed-flag":
                    vms[0]["class_load_file_flags"] = vms[1]["class_load_file_flags"]
                elif mode == "changed-vm":
                    Path(vms[0]["diagnostic_path"]).write_text("changed original VM arguments\n", encoding="utf-8")
                elif mode == "changed-file":
                    paths[0].write_text(classes + "changed\n", encoding="utf-8")
                elif mode == "missing-file":
                    paths[0].unlink()
                elif mode in ("wrong-pid", "truncated", "wrong-origin", "oversized"):
                    text = classes.replace("[123]", "[456]") if mode == "wrong-pid" else classes.rstrip("\n") if mode == "truncated" else \
                        re.sub(r"source: [^\n]+", "source: file:/wrong.jar", classes) if mode == "wrong-origin" else classes + "x" * (1024 * 1024) + "\n"
                    paths[0].write_text(text, encoding="utf-8")
                    receipt["controls"][0]["artifacts"][-1]["sha256"] = PAIRED.digest(paths[0])
                elif mode == "ambiguous-file":
                    receipt["controls"][0]["artifacts"].append(copy.deepcopy(receipt["controls"][0]["artifacts"][-1]))
                elif mode == "ambiguous-vm":
                    vms.append(copy.deepcopy(vms[0]))
                elif mode == "wrong-birth":
                    original["creationFileTime"] = 116444736030000000
                elif mode == "reordered-controls":
                    receipt["controls"].reverse()
                elif mode == "console-only":
                    receipt["controls"][0]["artifacts"].pop()
                (phase / "vms.json").write_text(json.dumps(vms), encoding="utf-8")
                PAIRED.write_new(directory / "receipt.json", receipt)
                if mode == "positive":
                    PAIRED.verify_preflight(directory, phase, terminal, PAIRED.digest(directory / "request.json"))
                    files = PAIRED.read_json(directory / "verified.json")["independent_class_load_files"]
                    self.assertEqual(len(files), 2)
                    self.assertEqual([item["vm_start_ms"] for item in files], [1001, 5001])
                    self.assertTrue(all(item["working_directory_observation"] is None for item in files))
                    self.assertTrue(all(item["relative_path_relationship"].startswith("inferred") for item in files))
                else:
                    with self.assertRaises((ValueError, FileNotFoundError)):
                        PAIRED.verify_preflight(directory, phase, terminal, PAIRED.digest(directory / "request.json"))
                    self.assertFalse((directory / "verified.json").exists())

    def test_vm_argument_file_capture_keeps_its_original_diagnostic_pin(self):
        directory = self.root / "file-flag-capture"
        (directory / "class-loads").mkdir(parents=True)
        home = self.root / "file-flag-jdk"
        executable = home / "bin/java.exe"
        executable.parent.mkdir(parents=True)
        executable.write_bytes(b"source-only executable fixture")
        origin = {"main": "independent.Controller", "executable": str(executable), "classpath": "frozen.jar"}
        (directory / "console.log").write_text("paired.jvm.origin " + json.dumps(origin) + "\n", encoding="utf-8")
        log = directory / "vm.log"
        flag = "../../independent-a/class-load.log"
        log.write_text("<hotspot_log process='123' time_ms='1001'><vm_arguments>"
                       f"<args>-Xlog:class+load=info:file={flag}:pid,level,tags:filecount=0 </args>"
                       f"<command>independent.Controller input</command>\njava.home={home}\njava.class.path=frozen.jar\n"
                       "</vm_arguments>\n-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n"
                       "<hotspot_log_done/></hotspot_log>\n", encoding="utf-8")
        PAIRED.verify_vms(directory, [log], {"members": {}}, {os.path.normcase(str(executable)): PAIRED.digest(executable)}, 0)
        actual = PAIRED.read_json(directory / "vms.json")[0]
        self.assertEqual(actual["class_load_file_flags"], [flag])
        self.assertEqual(actual["diagnostic_path"], str(log))
        self.assertEqual(actual["diagnostic_sha256"], PAIRED.digest(log))
        self.assertNotIn("working_directory", actual)


    def test_source_daemon_cap_requires_matching_closed_xml_and_keeps_other_role_caps(self):
        for index, (declared, actual, role, closed, valid) in enumerate(((384, 384, "daemon", True, True),
                (1024, 1024, "daemon", True, True), (384, 1024, "daemon", True, False),
                (1024, 384, "daemon", True, False), (384, 384, "daemon", False, False),
                (384, 256, "worker", True, True), (384, 384, "worker", True, False))):
            with self.subTest(declared=declared, actual=actual, role=role, closed=closed):
                directory = self.root / ("source-daemon-cap-" + str(index))
                directory.joinpath("class-loads").mkdir(parents=True)
                (directory / "console.log").write_text("", encoding="utf-8")
                home = directory / "jdk"
                executable = home / "bin/java.exe"
                executable.parent.mkdir(parents=True)
                executable.write_bytes(b"synthetic pinned Java; never launched")
                main = "org.gradle.launcher.daemon.bootstrap.GradleDaemon" if role == "daemon" else "worker.org.gradle.process.internal.worker.GradleWorkerMain"
                heap = (768 if role == "daemon" else 512) * 1024**2
                log = directory / "vm.log"
                log.write_text(f"<hotspot_log process='123' time_ms='1001'><vm_arguments><command>{main} 1</command>\njava.home={home}\njava.class.path=frozen.jar\n"
                               f"</vm_arguments>\n-XX:MaxHeapSize={heap} -XX:MaxMetaspaceSize={actual * 1024**2}\n"
                               + ("<hotspot_log_done/></hotspot_log>\n" if closed else ""), encoding="utf-8")
                member = {"pid": 123, "creationFileTime": 116444736010000000, "exitFileTime": 116444736020000000, "image": str(executable),
                          "inOwnedJob": True, "completed": True, "handleClosed": True, "exitCode": 0, "affinityMask": 15, "systemAffinityMask": 15}
                terminal = {"members": {"123": [member]}, "naturalRelease": True, "handlesClosed": True, "activeProcessesAfterCleanup": 0,
                            "inheritedAffinityMask": 15, "systemAffinityMask": 15, "nativeInvoked": True, "notificationReaderJoined": True, "totalProcesses": 1,
                            "generationAccounting": {"schema": "strata-owned-generation-accounting-v1", "complete": True,
                                                     "totalProcesses": 1, "capturedGenerations": 1, "closedGenerations": 1}}
                arguments = (directory, [log], terminal, {os.path.normcase(str(executable)): PAIRED.digest(executable)}, 0)
                if valid:
                    PAIRED.verify_vms(*arguments, require_all_java_generations=True, daemon_metaspace_mib=declared)
                    record = PAIRED.read_json(directory / "vms.json")[0]
                    self.assertEqual((record["role"], record["caps"]["MaxMetaspaceSize"], record["os_generation"]), (role, actual * 1024**2, member))
                else:
                    with self.assertRaises(ValueError):
                        PAIRED.verify_vms(*arguments, require_all_java_generations=True, daemon_metaspace_mib=declared)
                    self.assertFalse((directory / "vms.json").exists())
        for invalid in (True, 0, 1025, "384", None):
            with self.subTest(invalid=invalid), self.assertRaisesRegex(ValueError, "Invalid finite daemon metaspace cap"):
                PAIRED.verify_vms(self.root, [], {}, {}, daemon_metaspace_mib=invalid)

    def test_compiled_qualification_preserves_long_paths_before_its_first_checkout(self):
        self.selector_repository()
        relative = Path("runtime/shared") / Path(*(["long-source-directory"] * 12)) / "Original.kt"
        original = self.root / relative
        original.parent.mkdir(parents=True)
        original.write_bytes(b"original long-path runtime source\n")
        controller = self.root / "gradle/performance/fixtures/QualifiedFixtureController.java"
        controller.parent.mkdir(parents=True)
        controller.write_bytes(b"independent source fixture; never compiled\n")
        head = self.selector_commit("Preserve long-path qualification sources")
        output = self.root / "build/qualified"
        output.mkdir(parents=True)
        script = Path(PAIRED.__file__).with_name("verify-qualified-preflight.py")
        specification = importlib.util.spec_from_file_location("qualified_long_path_control", script)
        qualified = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(qualified)
        global_config = self.root / "empty-global.gitconfig"
        global_config.write_bytes(b"")
        with patch.dict(os.environ, {"GIT_CONFIG_GLOBAL": str(global_config), "GIT_CONFIG_NOSYSTEM": "1"}):
            source, copied_controller = qualified.frozen_fixture(self.root, output)
            checkout = output / "workspaces/fixture-source"
            self.assertEqual((checkout / relative).read_bytes(), original.read_bytes())
            self.assertEqual(PAIRED.git(checkout, "config", "--local", "--get", "core.longpaths"), "true")
            self.assertEqual(source["ref"], head)
            self.assertEqual(copied_controller, controller)
            self.assertEqual((checkout / "quality/remote-benchmarks/src/jmh/java/independent/fixture/QualifiedFixtureController.java").read_bytes(), controller.read_bytes())

    def test_source_java_exec_caps_bind_actual_closed_generations_without_changing_forks(self):
        cases = ((512, 256, "java_exec", 512, 256, True), (768, 384, "java_exec", 768, 384, True),
                 (512, 256, "java_exec", 768, 384, False), (768, 384, "java_exec", 512, 256, False),
                 (512, 256, "java_exec", 512, 384, False), (512, 256, "java_exec", 768, 256, False),
                 (512, 256, "worker", 512, 256, True), (512, 256, "worker", 768, 384, False),
                 (512, 256, "fork", 768, 384, True), (512, 256, "fork", 512, 256, False))
        for index, (heap, metaspace, role, actual_heap, actual_metaspace, valid) in enumerate(cases):
            with self.subTest(role=role, declared=(heap, metaspace), actual=(actual_heap, actual_metaspace)):
                directory = self.root / ("source-execution-cap-" + str(index))
                classes = directory / "class-loads"
                classes.mkdir(parents=True)
                home = directory / "jdk"
                executable = home / "bin/java.exe"
                executable.parent.mkdir(parents=True)
                executable.write_bytes(b"synthetic pinned Java; never launched")
                main = {"java_exec": "fixture.Collector", "worker": "worker.org.gradle.process.internal.worker.GradleWorkerMain",
                        "fork": "org.openjdk.jmh.runner.ForkedMain"}[role]
                origin = {"main": main, "executable": str(executable), "classpath": "frozen.jar"}
                (directory / "console.log").write_text("paired.jvm.origin " + json.dumps(origin) + "\n", encoding="utf-8")
                if role == "fork":
                    (classes / "classes-123-1970-01-01_00-00-01.log").write_text("[123][info][class,load] org.openjdk.jmh.runner.ForkedMain source: file:/frozen.jar\n", encoding="utf-8")
                log = directory / "vm.log"
                log.write_text(f"<hotspot_log process='123' time_ms='1001'><vm_arguments><command>{main}</command>\n"
                               f"java.home={home}\njava.class.path=frozen.jar\n</vm_arguments>\n"
                               f"-XX:MaxHeapSize={actual_heap * 1024**2} -XX:MaxMetaspaceSize={actual_metaspace * 1024**2}\n"
                               "<hotspot_log_done/></hotspot_log>\n", encoding="utf-8")
                member = {"pid": 123, "creationFileTime": 116444736010000000, "exitFileTime": 116444736020000000,
                          "image": str(executable), "inOwnedJob": True, "completed": True, "handleClosed": True,
                          "exitCode": 0, "affinityMask": 15, "systemAffinityMask": 15}
                terminal = {"members": {"123": [member]}, "naturalRelease": True, "handlesClosed": True,
                            "activeProcessesAfterCleanup": 0, "inheritedAffinityMask": 15, "systemAffinityMask": 15,
                            "nativeInvoked": True, "notificationReaderJoined": True, "totalProcesses": 1,
                            "generationAccounting": {"schema": "strata-owned-generation-accounting-v1", "complete": True,
                                                     "totalProcesses": 1, "capturedGenerations": 1, "closedGenerations": 1}}
                arguments = (directory, [log], terminal, {os.path.normcase(str(executable)): PAIRED.digest(executable)}, int(role == "fork"))
                options = {"require_all_java_generations": True, "java_exec_heap_mib": heap, "java_exec_metaspace_mib": metaspace}
                if valid:
                    PAIRED.verify_vms(*arguments, **options)
                    record = PAIRED.read_json(directory / "vms.json")[0]
                    self.assertEqual((record["role"], record["os_generation"], record["caps"]),
                                     (role, member, {"MaxHeapSize": actual_heap * 1024**2, "MaxMetaspaceSize": actual_metaspace * 1024**2}))
                else:
                    with self.assertRaisesRegex(ValueError, "actual finite VM cap"):
                        PAIRED.verify_vms(*arguments, **options)
                    self.assertFalse((directory / "vms.json").exists())
        for options in ({"java_exec_heap_mib": item} for item in (True, 63, 769, "512", None)):
            with self.subTest(options=options), self.assertRaisesRegex(ValueError, "Invalid finite JavaExec caps"):
                PAIRED.verify_vms(self.root, [], {}, {}, **options)
        for options in ({"java_exec_metaspace_mib": item} for item in (True, 0, 385, "256", None)):
            with self.subTest(options=options), self.assertRaisesRegex(ValueError, "Invalid finite JavaExec caps"):
                PAIRED.verify_vms(self.root, [], {}, {}, **options)

    def test_process_isolation_worker_diagnostics_survive_empty_environment_and_cover_generations(self):
        source = (Path(__file__).resolve().parents[1] / "performance/bounded-jvm.init.gradle.kts").read_text(encoding="utf-8")
        block = re.search(r"val workerDiagnostics = listOf\(([\s\S]*?)\n    \)", source).group(1)
        diagnostics = re.findall(r'"([^"]+)"', block)
        self.assertEqual(diagnostics[:4], ["-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintCommandLineFlags", "-XX:-DisplayVMOutput", "-XX:+LogVMOutput"])
        self.assertEqual(diagnostics[4:], ["-XX:LogFile=${vmLogs.invariantSeparatorsPath}/vm-%p-%t.log"])
        self.assertIn('.invoke(arguments, listOf("-Xms64m", "-Xmx512m", "-XX:MaxMetaspaceSize=256m") + workerDiagnostics)', source)
        for mode in ("complete-reuse", "legacy-default", "missing-generation", "wrong-reused-birth", "wrong-image", "unclosed",
                     "active", "nonnatural", "open-terminal", "duplicate-diagnostic", "wrong-affinity", "unknown-javaw", "alias-pid"):
            with self.subTest(mode=mode):
                directory = self.root / ("isolated-worker-" + mode)
                (directory / "class-loads").mkdir(parents=True)
                home = directory / "jdk"
                executable = home / "bin/java.exe"
                executable.parent.mkdir(parents=True)
                executable.write_bytes(b"synthetic worker executable")
                (directory / "console.log").write_bytes(b"")
                parent_environment = {"JAVA_TOOL_OPTIONS": "-XX:LogFile=wrong-inherited-path"}
                worker_environment = {}
                arguments = ["-Xms64m", "-Xmx512m", "-XX:MaxMetaspaceSize=256m", *[flag.replace("${vmLogs.invariantSeparatorsPath}", directory.as_posix()) for flag in diagnostics]]
                self.assertNotIn("JAVA_TOOL_OPTIONS", worker_environment)
                self.assertNotIn(parent_environment["JAVA_TOOL_OPTIONS"], arguments)
                members, logs = [], []
                for index, birth in enumerate((1000, 5000)):
                    members.append({"pid": 123, "creationFileTime": 116444736000000000 + birth * 10000,
                                    "exitFileTime": 116444736000000000 + (birth + 500) * 10000, "image": str(executable),
                                    "inOwnedJob": True, "completed": True, "handleClosed": True, "exitCode": 0,
                                    "affinityMask": 15, "systemAffinityMask": 15})
                    if index == 1 and mode in ("missing-generation", "legacy-default"):
                        continue
                    log = directory / ("vm-" + str(index) + ".log")
                    start = 1001 if index == 1 and mode == "wrong-reused-birth" else birth + 1
                    log.write_bytes((f"<hotspot_log process='123' time_ms='{start}'><vm_arguments><args>{' '.join(arguments)}</args>"
                                     f"<command>worker.org.gradle.process.internal.worker.GradleWorkerMain 1</command>\njava.home={home}\njava.class.path=worker.jar\n"
                                     "</vm_arguments>\n-XX:MaxHeapSize=536870912 -XX:MaxMetaspaceSize=268435456\n<hotspot_log_done/></hotspot_log>\n").encode())
                    logs.append(log)
                terminal = {"members": {"123": members}, "naturalRelease": True, "handlesClosed": True,
                            "activeProcessesAfterCleanup": 0, "inheritedAffinityMask": 15, "systemAffinityMask": 15,
                            "nativeInvoked": True, "notificationReaderJoined": True, "totalProcesses": 2,
                            "generationAccounting": {"schema": "strata-owned-generation-accounting-v1", "complete": True,
                                                     "totalProcesses": 2, "capturedGenerations": 2, "closedGenerations": 2}}
                if mode == "wrong-image":
                    members[1]["image"] = str(home / "different/java.exe")
                elif mode == "unclosed":
                    members[1]["handleClosed"] = False
                elif mode == "active":
                    terminal["activeProcessesAfterCleanup"] = 1
                elif mode == "nonnatural":
                    terminal["naturalRelease"] = False
                elif mode == "open-terminal":
                    terminal["handlesClosed"] = False
                elif mode == "duplicate-diagnostic":
                    duplicate = directory / "duplicate.log"
                    duplicate.write_bytes(logs[0].read_bytes())
                    logs.append(duplicate)
                elif mode == "wrong-affinity":
                    members[1]["affinityMask"] = 1
                elif mode == "unknown-javaw":
                    terminal["members"]["124"] = [{**members[1], "pid": 124, "image": str(home / "bin/javaw.exe")}]
                    terminal["totalProcesses"] = 3
                    terminal["generationAccounting"].update(totalProcesses=3, capturedGenerations=3, closedGenerations=3)
                elif mode == "alias-pid":
                    terminal["members"]["0123"] = [members[1]]
                allowed = {os.path.normcase(str(executable)): PAIRED.digest(executable)}
                if mode in ("complete-reuse", "legacy-default"):
                    PAIRED.verify_vms(directory, logs, terminal, allowed, 0, require_all_java_generations=mode != "legacy-default")
                    records = PAIRED.read_json(directory / "vms.json")
                    self.assertTrue(all(row["role"] == "worker" and row["caps"] == {"MaxHeapSize": 536870912, "MaxMetaspaceSize": 268435456} for row in records))
                    if mode == "complete-reuse":
                        self.assertEqual([row["os_generation"] for row in records], members)
                else:
                    with self.assertRaises(ValueError):
                        PAIRED.verify_vms(directory, logs, terminal, allowed, 0, require_all_java_generations=True)
                    self.assertFalse((directory / "vms.json").exists())

    def test_complete_invocation_binds_unique_coarse_negative_pid_without_altering_birth_or_legacy_matching(self):
        for mode in ("negative", "positive", "legacy-negative", "missing-accounting", "count-mismatch", "aliased-count",
                     "reused-negative", "duplicate-xml", "wrong-image", "unclosed", "beyond-startup", "beyond-lifetime"):
            with self.subTest(mode=mode):
                directory = self.root / ("unique-coarse-" + mode)
                (directory / "class-loads").mkdir(parents=True)
                home = directory / "jdk"
                executable = home / "bin/java.exe"
                executable.parent.mkdir(parents=True)
                executable.write_bytes(b"pinned synthetic java")
                (directory / "console.log").write_bytes(b"")
                birth = 116444736000000000 + 1000 * 10000
                member = {"pid": 123, "creationFileTime": birth, "exitFileTime": birth + 500 * 10000,
                          "image": str(executable), "inOwnedJob": True, "completed": True, "handleClosed": True,
                          "exitCode": 0, "affinityMask": 15, "systemAffinityMask": 15}
                terminal = {"members": {"123": [member]}, "naturalRelease": True, "handlesClosed": True,
                            "activeProcessesAfterCleanup": 0, "inheritedAffinityMask": 15, "systemAffinityMask": 15,
                            "nativeInvoked": True, "notificationReaderJoined": True, "totalProcesses": 1,
                            "generationAccounting": {"schema": "strata-owned-generation-accounting-v1", "complete": True,
                                                     "totalProcesses": 1, "capturedGenerations": 1, "closedGenerations": 1},
                            "clockBeforeResume": {"adjustmentDisabled": True, "increment100ns": 156250}}
                start = 1001 if mode == "positive" else 3001 if mode == "beyond-startup" else 1501 if mode == "beyond-lifetime" else 993
                log = directory / "vm.log"
                log.write_text(f"<hotspot_log process='123' time_ms='{start}'><vm_arguments>"
                               f"<command>org.gradle.wrapper.GradleWrapperMain</command>\njava.home={home}\njava.class.path=wrapper.jar\n"
                               "</vm_arguments>\n-XX:MaxHeapSize=67108864 -XX:MaxMetaspaceSize=268435456\n"
                               "<hotspot_log_done/></hotspot_log>\n", encoding="utf-8")
                logs = [log]
                if mode == "missing-accounting":
                    terminal.pop("generationAccounting")
                elif mode == "count-mismatch":
                    terminal["generationAccounting"]["totalProcesses"] = 2
                elif mode == "aliased-count":
                    terminal["members"]["0123"] = [copy.deepcopy(member)]
                    terminal["totalProcesses"] = 2
                    terminal["generationAccounting"].update(totalProcesses=2, capturedGenerations=2, closedGenerations=2)
                elif mode == "reused-negative":
                    terminal["members"]["123"].append({**member, "creationFileTime": birth + 3000 * 10000, "exitFileTime": birth + 3500 * 10000})
                    terminal["totalProcesses"] = 2
                    terminal["generationAccounting"].update(totalProcesses=2, capturedGenerations=2, closedGenerations=2)
                elif mode == "duplicate-xml":
                    duplicate = directory / "duplicate.log"
                    duplicate.write_bytes(log.read_bytes())
                    logs.append(duplicate)
                elif mode == "wrong-image":
                    member["image"] = str(home / "different/java.exe")
                elif mode == "unclosed":
                    member["handleClosed"] = False
                allowed = {os.path.normcase(str(executable)): PAIRED.digest(executable)}
                if mode in ("negative", "positive"):
                    PAIRED.verify_vms(directory, logs, terminal, allowed, 0, require_all_java_generations=True)
                    receipt = PAIRED.read_json(directory / "vms.json")[0]
                    self.assertEqual(receipt["os_generation"]["creationFileTime"], birth)
                    self.assertEqual(receipt["os_generation"]["exitFileTime"], birth + 500 * 10000)
                    self.assertEqual(receipt["vm_start_minus_creation_ms"], -7 if mode == "negative" else 1)
                    self.assertEqual(receipt["binding_basis"], "unique_owned_pid_in_complete_invocation" if mode == "negative" else "timestamp_within_captured_lifetime")
                else:
                    with self.assertRaises(ValueError):
                        if mode == "legacy-negative":
                            PAIRED.owned_generation(terminal, 123, start)
                        else:
                            PAIRED.verify_vms(directory, logs, terminal, allowed, 0, require_all_java_generations=True)
                    self.assertFalse((directory / "vms.json").exists())

    def test_owned_java8_runtime_uses_the_pinned_held_launcher_and_preserves_origin_guards(self):
        for role, main, heap, metaspace in (("probe", "JavaProbe", 32, 256), ("wrapper", "org.gradle.wrapper.GradleWrapperMain", 64, 256),
                                             ("worker", "worker.org.gradle.process.internal.worker.GradleWorkerMain", 512, 256),
                                             ("java_exec", "fixture.Collector", 768, 384), ("fork", "org.openjdk.jmh.runner.ForkedMain", 768, 384)):
            for mode in ("complete", "unknown-image", "changed-bytes", "missing-image", "wrong-home", "wrong-spec", "missing-spec", "duplicate-spec",
                         "wrong-origin", "wrong-classpath", "wrong-birth", "unclosed", "wrong-affinity", "truncated"):
                with self.subTest(role=role, mode=mode):
                    directory = self.root / (role + "-" + mode)
                    home = directory / "jdk"
                    executable = home / "bin/java.exe"
                    executable.parent.mkdir(parents=True)
                    executable.write_bytes(b"pinned root launcher; never executed")
                    runtime_home = home / "jre"
                    (runtime_home / "bin").mkdir(parents=True)
                    (runtime_home / "bin/java.exe").write_bytes(b"different nested launcher; never selected")
                    allowed = {os.path.normcase(str(executable)): PAIRED.digest(executable)}
                    birth = 116444736000000000 + 1000 * 10000
                    member = {"pid": 123, "creationFileTime": birth, "exitFileTime": birth + 500 * 10000,
                              "image": str(executable), "inOwnedJob": True, "completed": True, "handleClosed": True,
                              "exitCode": 0, "affinityMask": 15, "systemAffinityMask": 15}
                    terminal = {"members": {"123": [member]}, "naturalRelease": True, "handlesClosed": True,
                                "activeProcessesAfterCleanup": 0, "inheritedAffinityMask": 15, "systemAffinityMask": 15,
                                "nativeInvoked": True, "notificationReaderJoined": True, "totalProcesses": 1,
                                "generationAccounting": {"schema": "strata-owned-generation-accounting-v1", "complete": True,
                                                         "totalProcesses": 1, "capturedGenerations": 1, "closedGenerations": 1}}
                    origin = {"main": main, "executable": str(executable), "classpath": "frozen.jar"}
                    if mode == "wrong-origin":
                        origin["executable"] = str(runtime_home / "bin/java.exe")
                    elif mode == "wrong-classpath":
                        origin["classpath"] = "other.jar"
                    (directory / "console.log").write_text("paired.jvm.origin " + json.dumps(origin) + "\n", encoding="utf-8")
                    classes = directory / "class-loads"
                    classes.mkdir()
                    if role == "fork":
                        (classes / "classes-123-1970-01-01_00-00-01.log").write_text("[123][info][class,load] org.openjdk.jmh.runner.ForkedMain source: file:/frozen.jar\n", encoding="utf-8")
                    specification = "" if mode == "missing-spec" else "java.vm.specification.version=" + ("17" if mode == "wrong-spec" else "1.8") + "\n"
                    if mode == "duplicate-spec":
                        specification *= 2
                    if mode == "wrong-home":
                        runtime_home = directory / "unrelated/jre"
                    log = directory / "vm.log"
                    document = (f"<hotspot_log process='123' time_ms='1001'><vm_arguments><command>{main}</command>\n"
                                f"java.home={runtime_home}\njava.class.path=frozen.jar\n{specification}</vm_arguments>\n"
                                f"-XX:MaxHeapSize={heap * 1024**2} -XX:MaxMetaspaceSize={metaspace * 1024**2}\n"
                                + ("" if mode == "truncated" else "<hotspot_log_done/></hotspot_log>\n"))
                    log.write_text(document, encoding="utf-8")
                    if mode == "unknown-image":
                        allowed.clear()
                    elif mode == "changed-bytes":
                        executable.write_bytes(b"changed root launcher")
                    elif mode == "missing-image":
                        executable.unlink()
                    elif mode == "wrong-birth":
                        member["creationFileTime"] += 5000 * 10000
                    elif mode == "unclosed":
                        member["handleClosed"] = False
                    elif mode == "wrong-affinity":
                        member["affinityMask"] = 3
                    if mode == "complete" or mode in ("wrong-origin", "wrong-classpath") and role != "java_exec":
                        PAIRED.verify_vms(directory, [log], terminal, allowed, int(role == "fork"), require_all_java_generations=True)
                        record = PAIRED.read_json(directory / "vms.json")[0]
                        self.assertEqual(record["home"], str(runtime_home))
                        self.assertEqual(record["executable"], str(executable))
                        self.assertEqual(record["vm_specification_versions"], ["1.8"])
                        self.assertEqual(record["os_generation"], member)
                    else:
                        with self.assertRaises((ValueError, FileNotFoundError)):
                            PAIRED.verify_vms(directory, [log], terminal, allowed, int(role == "fork"), require_all_java_generations=True)
                        self.assertFalse((directory / "vms.json").exists())

    def test_source_sized_vm_diagnostic_retains_all_bytes_without_weakening_terminal_or_caps(self):
        for mode in ("complete", "default-bound", "explicit-overflow", "truncated", "duplicate-header", "changed-cap"):
            with self.subTest(mode=mode):
                directory = self.root / ("source-sized-vm-" + mode)
                (directory / "class-loads").mkdir(parents=True)
                home = directory / "jdk"
                executable = home / "bin/java.exe"
                executable.parent.mkdir(parents=True)
                executable.write_bytes(b"synthetic executable bytes")
                origin = {"main": "independent.Controller", "executable": str(executable), "classpath": "frozen.jar"}
                (directory / "console.log").write_text("paired.jvm.origin " + json.dumps(origin) + "\n", encoding="utf-8")
                document = ("<hotspot_log process='123' time_ms='1001'><vm_arguments>"
                            f"<command>independent.Controller input</command>\njava.home={home}\njava.class.path=frozen.jar\n"
                            "</vm_arguments>\n-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n"
                            "<!--" + "x" * 1_300_000 + "-->\n<hotspot_log_done/></hotspot_log>\n")
                if mode == "truncated":
                    document = document.replace("<hotspot_log_done/></hotspot_log>", "")
                elif mode == "duplicate-header":
                    document += "<hotspot_log process='124' time_ms='1002'></hotspot_log>\n"
                elif mode == "changed-cap":
                    document = document.replace("MaxHeapSize=805306368", "MaxHeapSize=805306369")
                log = directory / "vm.log"
                log.write_bytes(document.encode())
                allowed = {os.path.normcase(str(executable)): PAIRED.digest(executable)}
                bound = 1024**2 if mode == "default-bound" else len(log.read_bytes()) - 1 if mode == "explicit-overflow" else 16 * 1024**2
                if mode == "complete":
                    PAIRED.verify_vms(directory, [log], {"members": {}}, allowed, 0, maximum_diagnostic_bytes=bound)
                    actual = PAIRED.read_json(directory / "vms.json")[0]
                    self.assertEqual(actual["diagnostic_sha256"], PAIRED.digest(log))
                    self.assertEqual(actual["diagnostic_bytes"], len(document.encode()))
                    self.assertEqual(actual["diagnostic_byte_bound"], bound)
                    self.assertEqual(log.read_bytes(), document.encode())
                else:
                    with self.assertRaises(ValueError):
                        if mode == "default-bound":
                            PAIRED.verify_vms(directory, [log], {"members": {}}, allowed, 0)
                        else:
                            PAIRED.verify_vms(directory, [log], {"members": {}}, allowed, 0, maximum_diagnostic_bytes=bound)
                    self.assertFalse((directory / "vms.json").exists())
        for invalid in (True, 0, -1, 16 * 1024**2 + 1):
            with self.subTest(invalid_bound=invalid), self.assertRaisesRegex(ValueError, "finite VM diagnostic byte bound"):
                PAIRED.verify_vms(self.root, [], {}, {}, maximum_diagnostic_bytes=invalid)

    def test_new_history_requires_explicit_closure_and_lifetime_while_legacy_receipts_remain_readable(self):
        original = {"inOwnedJob": True, "completed": True, "exitCode": 0, "creationFileTime": 116444736010000000}
        legacy = {"members": {"123": copy.deepcopy(original)}}
        self.assertEqual(PAIRED.owned_generation(legacy, 123, 1001), original)
        current = {**original, "pid": 123, "handleClosed": True, "exitFileTime": 116444736020000000}
        for missing in (None, "handleClosed", "exitFileTime", "pid"):
            with self.subTest(missing=missing):
                member = copy.deepcopy(current)
                if missing is not None:
                    member.pop(missing)
                terminal = {"members": {"123": [member]}}
                if missing is None:
                    self.assertEqual(PAIRED.owned_generation(terminal, 123, 1001), current)
                else:
                    with self.assertRaises(ValueError):
                        PAIRED.owned_generation(terminal, 123, 1001)

    def test_control_cannot_borrow_class_attribution_from_another_generation_with_the_same_pid(self):
        directory, phase, terminal, receipt = self.preflight_fixture("cross-generation-class")
        classes = (phase / "console.log").read_text(encoding="utf-8")
        (phase / "console.log").write_text("", encoding="utf-8")
        current = phase / "class-loads/classes-123-2026-10-12_00-00-01.log"
        current.write_text("[123][info][class,load] unrelated.Class source: file:/unrelated.jar\n", encoding="utf-8")
        later = phase / "class-loads/classes-123-2026-10-12_00-00-05.log"
        later.write_text(classes, encoding="utf-8")
        vm = PAIRED.read_json(phase / "vms.json")[0]
        vm["class_load_diagnostic"] = {"path": str(current), "sha256": PAIRED.digest(current), "pid": 123}
        other = {**vm, "role": "worker", "vm_start_ms": 5001,
                 "class_load_diagnostic": {"path": str(later), "sha256": PAIRED.digest(later), "pid": 123}}
        (phase / "vms.json").write_text(json.dumps([vm, other]), encoding="utf-8")
        PAIRED.write_new(directory / "receipt.json", receipt)
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous PID-qualified"):
            PAIRED.verify_preflight(directory, phase, terminal, PAIRED.digest(directory / "request.json"))
        self.assertFalse((directory / "verified.json").exists())

    def test_control_rejects_another_generations_console_origin_even_with_an_unrelated_own_class_file(self):
        directory, phase, terminal, receipt = self.preflight_fixture("cross-generation-console")
        current = phase / "class-loads/classes-123-2026-10-12_00-00-01.log"
        current.write_text("[123][info][class,load] unrelated.Class source: file:/unrelated.jar\n", encoding="utf-8")
        vm = PAIRED.read_json(phase / "vms.json")[0]
        vm["class_load_diagnostic"] = {"path": str(current), "sha256": PAIRED.digest(current), "pid": 123}
        other = {"pid": 123, "role": "worker", "vm_start_ms": 5001, "home": vm["home"]}
        (phase / "vms.json").write_text(json.dumps([vm, other]), encoding="utf-8")
        PAIRED.write_new(directory / "receipt.json", receipt)
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous PID-qualified"):
            PAIRED.verify_preflight(directory, phase, terminal, PAIRED.digest(directory / "request.json"))
        self.assertFalse((directory / "verified.json").exists())

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

    def test_preflight_keeps_unique_generation_metadata_separate_from_complete_class_file_identity(self):
        for mode in ("complete", "changed-core-pin"):
            with self.subTest(mode=mode):
                directory, phase, terminal, receipt = self.preflight_fixture("unique-preflight-" + mode)
                console = phase / "console.log"
                diagnostic = phase / "class-loads/classes-123-2026-10-12_00-00-00.log"
                diagnostic.write_text(console.read_text(encoding="utf-8"), encoding="utf-8")
                console.write_text("", encoding="utf-8")
                member = {**terminal["members"]["123"], "pid": 123, "handleClosed": True, "exitFileTime": 116444736020000000}
                terminal["members"]["123"] = [member]
                vm = phase / "vm-pid123-2026-10-12_00-00-01.log"
                vm.write_text("<hotspot_log process='123' time_ms='1001'><vm_arguments><args>"
                              "-Xlog:class+load=info:file=../../class-loads/classes-%p-%t.log:pid,level,tags:filecount=0"
                              "</args></vm_arguments></hotspot_log>\n", encoding="utf-8")
                pin = {"source": str(phase / "class-loads" / diagnostic.name), "path": str(diagnostic), "sha256": PAIRED.digest(diagnostic)}
                PAIRED.write_new(phase / "class-load-inventory.json", {"owner": str(phase / "class-loads"), "previous": {}, "copied": [pin]})
                _, identities = PAIRED.class_loads(phase)
                bound = PAIRED.class_diagnostic(identities, 123, vm, [vm], terminal)
                self.assertEqual(bound["unique_generation"], member)
                if mode == "changed-core-pin":
                    bound["sha256"] = "0" * 64
                records = PAIRED.read_json(phase / "vms.json")
                records[0]["class_load_diagnostic"] = bound
                (phase / "vms.json").write_text(json.dumps(records), encoding="utf-8")
                PAIRED.write_new(directory / "receipt.json", receipt)
                if mode == "complete":
                    PAIRED.verify_preflight(directory, phase, terminal, PAIRED.digest(directory / "request.json"))
                    self.assertEqual(PAIRED.read_json(directory / "verified.json")["controls"], ["independent-a", "independent-b"])
                else:
                    with self.assertRaisesRegex(ValueError, "Control changed its captured class-load diagnostic"):
                        PAIRED.verify_preflight(directory, phase, terminal, PAIRED.digest(directory / "request.json"))

    def test_independent_log_filename_expansions_require_a_unique_pinned_owned_generation(self):
        for mode in ("complete", "two-vms", "two-generations", "two-files", "changed-pin", "old-file", "wrong-owner", "wrong-flag", "missing-flag", "failed-process"):
            with self.subTest(mode=mode):
                directory = self.root / ("independent-expansion-" + mode)
                (directory / "class-loads").mkdir(parents=True)
                # Keep the legacy unique-file path distinct from an exact header-start match.
                diagnostic = directory / "class-loads/classes-3708-2026-10-12_23-45-43.log"
                diagnostic.write_text("[3708][info][class,load] org.openjdk.jmh.runner.CompilerHints source: file:/frozen.jar\n", encoding="utf-8")
                flag = "../../../../class-loads/classes-%p-%t.log"
                if mode == "wrong-flag":
                    flag = "../../../../other-owner/classes-%p-%t.log"
                args = "" if mode == "missing-flag" else f"-Xlog:class+load=info:file={flag}:pid,level,tags:filecount=0"
                vm = directory / "vm-pid3708-2026-10-12_23-45-45.log"
                vm.write_text(f"<hotspot_log process='3708' time_ms='1791848744997'><vm_arguments><args>{args}</args></vm_arguments></hotspot_log>\n", encoding="utf-8")
                files = [vm]
                if mode == "two-vms":
                    other = directory / "vm-pid3708-2026-10-12_23-46-00.log"
                    other.write_text("<hotspot_log process='3708' time_ms='1791848760000'></hotspot_log>\n", encoding="utf-8")
                    files.append(other)
                member = {"pid": 3708, "creationFileTime": 134363223449669511, "exitFileTime": 134363224864077401,
                          "inOwnedJob": True, "completed": True, "handleClosed": True, "exitCode": 1 if mode == "failed-process" else 0}
                history = [member]
                if mode == "two-generations":
                    history.append({**member, "creationFileTime": 134363224900000000, "exitFileTime": 134363225000000000})
                terminal = {"members": {"3708": history}}
                owner = directory / ("wrong" if mode == "wrong-owner" else "class-loads")
                pin = {"path": str(diagnostic), "source": str(owner / diagnostic.name), "sha256": "0" * 64 if mode == "changed-pin" else PAIRED.digest(diagnostic)}
                previous = {diagnostic.name: pin["sha256"]} if mode == "old-file" else {}
                PAIRED.write_new(directory / "class-load-inventory.json", {"owner": str(owner), "previous": previous, "copied": [pin]})
                candidates = {str(diagnostic): {"path": str(diagnostic), "sha256": PAIRED.digest(diagnostic), "pid": 3708}}
                if mode == "two-files":
                    other = directory / "class-loads/classes-3708-2026-10-12_23-46-00.log"
                    other.write_text(diagnostic.read_text(encoding="utf-8"), encoding="utf-8")
                    candidates[str(other)] = {"path": str(other), "sha256": PAIRED.digest(other), "pid": 3708}
                if mode == "complete":
                    result = PAIRED.class_diagnostic(candidates, 3708, vm, files, terminal)
                    self.assertEqual(result["path"], str(diagnostic))
                    self.assertEqual(result["unique_generation"], member)
                    self.assertEqual(result["actual_file_flag"], flag)
                elif mode in ("two-vms", "two-generations", "two-files"):
                    self.assertIsNone(PAIRED.class_diagnostic(candidates, 3708, vm, files, terminal))
                else:
                    with self.assertRaises(ValueError):
                        PAIRED.class_diagnostic(candidates, 3708, vm, files, terminal)

    def test_unique_java_generation_keeps_closed_helpers_and_rejects_missing_or_altered_proof(self):
        modes = ("complete", "two-java", "two-javaw", "missing-file", "console-only", "unclosed-helper", "failed-helper", "foreign-helper",
                 "wrong-helper-affinity", "overlapping-helper", "missing-helper-image", "wrong-java-image", "changed-pin", "missing-flag", "duplicate-origin")
        for mode in modes:
            with self.subTest(mode=mode):
                directory = self.root / ("closed-helper-" + mode)
                (directory / "class-loads").mkdir(parents=True)
                home = directory / "jdk"
                executable = home / "bin/java.exe"
                executable.parent.mkdir(parents=True)
                executable.write_bytes(b"frozen executable")
                allowed = {os.path.normcase(str(executable)): PAIRED.digest(executable)}
                origin = "[8188][info][class,load] org.openjdk.jmh.runner.ForkedMain source: file:/frozen.jar\n"
                # This control still exercises the sole-Java fallback, not header-start selection.
                diagnostic = directory / "class-loads/classes-8188-2026-10-13_06-15-23.log"
                diagnostic.write_text(origin * (2 if mode == "duplicate-origin" else 1), encoding="utf-8")
                flag = "../../class-loads/classes-%p-%t.log"
                args = "" if mode == "missing-flag" else f"-Xlog:class+load=info:file={flag}:pid,level,tags:filecount=0"
                vm = directory / "vm-pid8188-2026-10-13_06-15-25.log"
                vm.write_text(f"<hotspot_log process='8188' time_ms='1791872124997'>\n<vm_arguments><args>{args}</args>\n"
                              f"<command>org.openjdk.jmh.runner.ForkedMain 1</command>\njava.home={home}\njava.class.path=frozen.jar\n"
                              "</vm_arguments>\n-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n"
                              "<hotspot_log_done stamp='1'/>\n</hotspot_log>\n", encoding="utf-8")
                helper = {"pid": 8188, "creationFileTime": 134363456004687052, "exitFileTime": 134363456096932060,
                          "image": str(directory / "conhost.exe"), "inOwnedJob": True, "completed": True, "handleClosed": True,
                          "exitCode": 0, "affinityMask": 15, "systemAffinityMask": 15}
                member = {**helper, "creationFileTime": 134363457249718371, "exitFileTime": 134363457345207482, "image": str(executable)}
                if mode in ("two-java", "two-javaw"):
                    helper["image"] = str(directory / "earlier-jdk/bin" / ("javaw.exe" if mode == "two-javaw" else "java.exe"))
                elif mode == "unclosed-helper":
                    helper["handleClosed"] = False
                elif mode == "failed-helper":
                    helper["exitCode"] = 1
                elif mode == "foreign-helper":
                    helper["inOwnedJob"] = False
                elif mode == "wrong-helper-affinity":
                    helper["affinityMask"] = 1
                elif mode == "overlapping-helper":
                    helper["exitFileTime"] = member["creationFileTime"] + 1
                elif mode == "missing-helper-image":
                    helper.pop("image")
                elif mode == "wrong-java-image":
                    member["image"] = str(directory / "different-jdk/bin/java.exe")
                terminal = {"members": {"8188": [helper, member]}, "inheritedAffinityMask": 15, "systemAffinityMask": 15}
                pin = {"path": str(diagnostic), "source": str(diagnostic), "sha256": "0" * 64 if mode == "changed-pin" else PAIRED.digest(diagnostic)}
                PAIRED.write_new(directory / "class-load-inventory.json", {"owner": str(diagnostic.parent), "previous": {}, "copied": [pin]})
                (directory / "console.log").write_text(origin if mode == "console-only" else "", encoding="utf-8")
                if mode in ("missing-file", "console-only"):
                    diagnostic.unlink()
                if mode == "complete":
                    PAIRED.verify_vms(directory, [vm], terminal, allowed, 1)
                    result = PAIRED.read_json(directory / "vms.json")[0]
                    self.assertEqual(result["os_generation"], member)
                    self.assertEqual(result["class_load_diagnostic"]["unique_generation"], member)
                    self.assertEqual(result["class_load_diagnostic"]["closed_non_java_helper_generations"], [helper])
                    self.assertEqual(result["actual_loaded_main"], "file:/frozen.jar")
                else:
                    with self.assertRaises(ValueError):
                        PAIRED.verify_vms(directory, [vm], terminal, allowed, 1)
                    self.assertFalse((directory / "vms.json").exists())

    def test_header_start_binds_two_java_generations_without_borrowing_ambiguous_files(self):
        for mode in ("complete", "wrong-generation", "wrong-source", "changed-pin", "old-file", "missing-flag",
                     "ambiguous-stamps", "duplicate-origin", "unclosed-helper"):
            with self.subTest(mode=mode):
                directory = self.root / ("header-generation-" + mode)
                (directory / "class-loads").mkdir(parents=True)
                home = directory / "jdk"
                executable = home / "bin/java.exe"
                executable.parent.mkdir(parents=True)
                executable.write_bytes(b"frozen executable")
                allowed = {os.path.normcase(str(executable)): PAIRED.digest(executable)}
                starts = (1791917369287, 1791918056998)
                stamps = [PAIRED.time.strftime("%Y-%m-%d_%H-%M-%S", PAIRED.time.localtime(start // 1000)) for start in starts]
                late_stamp = PAIRED.time.strftime("%Y-%m-%d_%H-%M-%S", PAIRED.time.localtime(starts[1] // 1000 + 1))
                files = [directory / f"vm-pid5804-{stamp}.log" for stamp in (stamps[0], late_stamp)]
                flag = "../../class-loads/classes-%p-%t.log"
                for index, (vm, start) in enumerate(zip(files, starts)):
                    args = "" if mode == "missing-flag" and index == 1 else f"-Xlog:class+load=info:file={flag}:pid,level,tags:filecount=0"
                    vm.write_text(f"<hotspot_log process='5804' time_ms='{start}'>\n<vm_arguments><args>{args}</args>\n"
                                  f"<command>org.openjdk.jmh.runner.ForkedMain {index}</command>\njava.home={home}\njava.class.path=frozen.jar\n"
                                  "</vm_arguments>\n-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n"
                                  "<hotspot_log_done stamp='1'/>\n</hotspot_log>\n", encoding="utf-8")
                origin = "[5804][info][class,load] org.openjdk.jmh.runner.ForkedMain source: file:/frozen.jar\n"
                diagnostics, pins = [], []
                for index, stamp in enumerate(stamps):
                    diagnostic = directory / f"class-loads/classes-5804-{stamp}.log"
                    diagnostic.write_text(origin * (2 if mode == "duplicate-origin" and index == 1 else 1), encoding="utf-8")
                    diagnostics.append(diagnostic)
                    source = directory / "other-owner" / diagnostic.name if mode == "wrong-source" and index == 1 else diagnostic
                    pins.append({"path": str(diagnostic), "source": str(source), "sha256": PAIRED.digest(diagnostic)})
                if mode == "ambiguous-stamps":
                    diagnostic = directory / f"class-loads/classes-5804-{late_stamp}.log"
                    diagnostic.write_text(origin, encoding="utf-8")
                    pins.append({"path": str(diagnostic), "source": str(diagnostic), "sha256": PAIRED.digest(diagnostic)})
                elif mode == "changed-pin":
                    pins[1]["sha256"] = "0" * 64
                previous = {diagnostics[1].name: pins[1]["sha256"]} if mode == "old-file" else {}
                PAIRED.write_new(directory / "class-load-inventory.json", {"owner": str(diagnostics[0].parent), "previous": previous, "copied": pins})
                shared = {"pid": 5804, "image": str(executable), "inOwnedJob": True, "completed": True,
                          "handleClosed": True, "exitCode": 0, "affinityMask": 15, "systemAffinityMask": 15}
                early = {**shared, "creationFileTime": 134363909692619104, "exitFileTime": 134363909785816017}
                helper = {**shared, "image": str(directory / "conhost.exe"), "creationFileTime": 134363913039074374,
                          "exitFileTime": 134363913132529824}
                late = {**shared, "creationFileTime": 134363916569731556, "exitFileTime": 134363916666050451}
                if mode == "wrong-generation":
                    late["creationFileTime"] += 10000000
                elif mode == "unclosed-helper":
                    helper["handleClosed"] = False
                terminal = {"members": {"5804": [early, helper, late]}, "inheritedAffinityMask": 15, "systemAffinityMask": 15}
                (directory / "console.log").write_text("", encoding="utf-8")
                if mode == "complete":
                    PAIRED.verify_vms(directory, files, terminal, allowed, 2)
                    rows = PAIRED.read_json(directory / "vms.json")
                    self.assertEqual([row["os_generation"] for row in rows], [early, late])
                    self.assertEqual([row["class_load_diagnostic"]["path"] for row in rows], list(map(str, diagnostics)))
                    self.assertEqual(rows[1]["class_load_diagnostic"]["unique_generation"], late)
                    self.assertEqual(rows[1]["class_load_diagnostic"]["closed_non_java_helper_generations"], [helper])
                else:
                    with self.assertRaises(ValueError):
                        PAIRED.verify_vms(directory, files, terminal, allowed, 2)
                    self.assertFalse((directory / "vms.json").exists())

    def test_short_probe_does_not_borrow_later_same_pid_fork_class_log(self):
        home = self.root / "probe-reuse-jdk"
        executable = home / "bin/java.exe"
        executable.parent.mkdir(parents=True)
        executable.write_bytes(b"frozen executable")
        allowed = {os.path.normcase(str(executable)): PAIRED.digest(executable)}
        environment = "-Xmx32m -XX:MaxMetaspaceSize=256m -XX:+UnlockDiagnosticVMOptions -XX:+PrintCommandLineFlags -XX:-DisplayVMOutput -XX:+LogVMOutput -XX:LogFile=owned/vm-%p-%t.log"
        probe = ("<hotspot_log process='5704' time_ms='1791843175150'>\n<vm_arguments><args>\n"
                 f"{environment} -XX:+UnlockExperimentalVMOptions -XX:CompileCommand=blackhole,some/fake/Class.method </args>\n"
                 f"<launcher>SUN_STANDARD</launcher>\n<properties>\njava.home={home}\njava.class.path=\n</properties></vm_arguments>\n"
                 "-XX:MaxHeapSize=33554432 -XX:MaxMetaspaceSize=268435456\n<hotspot_log_done stamp='1'/>\n</hotspot_log>\n")
        def document(pid, start, main):
            return (f"<hotspot_log process='{pid}' time_ms='{start}'>\n<vm_arguments><command>{main}</command>\n"
                    f"java.home={home}\njava.class.path=frozen.jar\n</vm_arguments>\n"
                    "-XX:MaxHeapSize=805306368 -XX:MaxMetaspaceSize=402653184\n<hotspot_log_done stamp='1'/>\n</hotspot_log>\n")
        member = {"pid": 5704, "inOwnedJob": True, "completed": True, "handleClosed": True, "exitCode": 0,
                  "creationFileTime": 134363168979685947, "exitFileTime": 134363169071356647,
                  "image": str(executable), "affinityMask": 15, "systemAffinityMask": 15}
        for mode in ("complete", "wrong-fork-stamp", "unstamped-reused-pid"):
            with self.subTest(mode=mode):
                directory = self.root / ("probe-fork-" + mode)
                (directory / "class-loads").mkdir(parents=True)
                PAIRED.write_new(directory / "request.json", {"environment": {"JAVA_TOOL_OPTIONS": environment}})
                origin = {"main": "fixture.Collector", "executable": str(executable), "classpath": "frozen.jar"}
                (directory / "console.log").write_text("paired.jvm.origin " + json.dumps(origin) + "\n", encoding="utf-8")
                files = [directory / name for name in ("vm-pid5704-2026-10-12_22-12-55.log", "vm-pid5704-2026-10-12_22-14-57.log", "vm-pid456-2026-10-12_22-12-54.log")]
                if mode == "unstamped-reused-pid":
                    files[0] = directory / "probe.log"
                    files[1] = directory / "fork.log"
                for path, value in zip(files, (probe, document(5704, 1791843297993, "org.openjdk.jmh.runner.ForkedMain 1"), document(456, 1791843174001, "fixture.Collector"))):
                    path.write_text(value, encoding="utf-8")
                stamp = "2026-10-12_22-12-55" if mode == "wrong-fork-stamp" else "2026-10-12_22-14-57"
                fork_class = directory / f"class-loads/classes-5704-{stamp}.log"
                fork_class.write_text("[5704][info][class,load] org.openjdk.jmh.runner.ForkedMain source: file:/frozen.jar\n", encoding="utf-8")
                (directory / "class-loads/classes-456-2026-10-12_22-12-54.log").write_text("[456][info][class,load] org.openjdk.jmh.runner.CompilerHints source: file:/frozen.jar\n", encoding="utf-8")
                terminal = {"members": {"5704": [member]}, "inheritedAffinityMask": 15, "systemAffinityMask": 15}
                if mode == "complete":
                    PAIRED.verify_vms(directory, files, terminal, allowed, 1)
                    rows = PAIRED.read_json(directory / "vms.json")
                    self.assertNotIn("class_load_diagnostic", rows[0])
                    self.assertEqual(rows[1]["class_load_diagnostic"]["sha256"], PAIRED.digest(fork_class))
                    self.assertEqual(rows[1]["os_generation"], member)
                else:
                    with self.assertRaises(ValueError):
                        PAIRED.verify_vms(directory, files, terminal, allowed, 1)
                    self.assertFalse((directory / "vms.json").exists())

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
                        self.assertEqual({item["pid"] for item in identities.values()}, {123})
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
        self.assertEqual({item["pid"] for item in identities.values()}, set(range(1, 49)))
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
            elif index == 4:
                text, identities = PAIRED.class_loads(directory)
                self.assertEqual(len(identities), 2)
                self.assertEqual({item["pid"] for item in identities.values()}, {123})
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
        for result in ("failure", "cancelled"):
            jobs["paired-jvm"]["result"] = result
            with self.subTest(result=result), patch.dict(os.environ, {"RESULTS": json.dumps(jobs)}), self.assertRaises(AssertionError):
                exec(compile(script, "jvm-workflow-result", "exec"), {})
        jobs["paired-jvm"]["result"] = "success"
        with patch.dict(os.environ, {"RESULTS": json.dumps(jobs)}):
            exec(compile(script, "jvm-workflow-result", "exec"), {})


if __name__ == "__main__":
    unittest.main()
