"""Exercise real paired orchestration with synthetic command output; no Java, OS or timing proof."""
from contextlib import ExitStack
import importlib.util
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("paired_routes", Path(__file__).resolve().parents[1] / "performance/paired-jvm.py")
PAIRED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAIRED)


class CompleteInputRouteTest(unittest.TestCase):
    """Keep every original phase family and both immutable input sides enforced."""
    def test_complete_routes_preserve_both_inputs_and_stop_before_report_on_drift_or_missing_delivery(self):
        for failure in (None, "missing-property", "changed-unselected-input", "changed-unselected-selector",
                        "changed-before-unselected-input", "changed-before-unselected-selector",
                        "missing-unselected-selector", "missing-unselected-input"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temporary:
                self.exercise(Path(temporary), failure)

    def exercise(self, root, failure):
        """Drive the real orchestrator against synthetic execution output without qualifying Java or timing."""
        root.joinpath("gradle").mkdir()
        root.joinpath("gradle/libs.versions.toml").write_text('[versions]\nperformance-toolchain="17.0.1+1"\nperformance-launcher="25.0.1"\n')
        homes = {}
        for role, provider, version in (("toolchain", "Eclipse Adoptium", "17.0.1"), ("launcher", "Oracle Corporation", "25.0.1")):
            home = root / role
            home.joinpath("bin").mkdir(parents=True)
            home.joinpath("bin/java.exe").write_bytes(b"synthetic; never executed")
            home.joinpath("release").write_text(f'IMPLEMENTOR="{provider}"\nJAVA_VERSION="{version}"\n')
            homes[role] = home
        output = root / "attempt"
        files = []
        for side in ("baseline", "candidate"):
            path = root / f"{side}.tsv"
            path.write_bytes(side.encode())
            files.append({"label": side, "pin": {"path": path.name, "sha256": PAIRED.digest(path)}})
        classes = {"api": {"project": ":api", "class": "independent.Api"}, "core": {"project": ":runtime:core", "class": "independent.Core"}, "remote": {"project": ":runtime:remote", "class": "independent.Runtime"}}
        owner = {"name": "owner", "project": ":quality:remote-benchmarks", "template": "verifyRemoteWorkloads", "kind": "java-exec", "main": "independent.Owner", "comparison": "independent.Compare", "workload_id": "whole-owner", "phase_keys": ["workload"], "rows": [{"workload": "all"}, {"workload": "control"}], "warmup": 100, "samples": 200, "runtime_classes": classes}
        source = {"ref": "a" * 40, "tree": "b" * 40, "patches": []}
        suite = {"project": ":quality:remote-benchmarks", "name": "whole", "benchmarks": ["IndependentFixture"], "workloads": [], "parameters": {}, "expected_cases": 2,
                 "preflight": {"main": "independent.Control", "qualification": files[0]["pin"], "runtime_classes": {value["project"]: value["class"] for value in classes.values()}, "controls": [{"id": "all", "arguments": ["complete"]}]},
                 "operation_reports": {"adapters": {"independent.IndependentFixture": "independent.Operation"}, "warmup": 30, "samples": 60, "expected_rows": 2, "expected_controls": 1}}
        data = {"schema": "strata-paired-jvm-ci-v1", "sources": {"fixture": source, "candidate": source, "baseline": {**source, "tree": "c" * 40}}, "runtime_projects": [value["project"] for value in classes.values()], "suites": [suite], "owner_reports": [owner], "inputs": files, "input_properties": {"strata.performance.compilerInventory": {"baseline": "baseline", "candidate": "candidate"}}, "compiled_pins": {side: [] for side in ("fixture", "baseline", "candidate")}, "dependency_pins": [], "java_pins": []}
        manifest = root / "manifest.json"
        manifest.write_text(json.dumps(data))
        calls, applied = [], []

        def materialize(repository, attempt, side, binding, input_root):
            workspace = attempt / "workspaces" / side
            workspace.mkdir(parents=True)
            for directory in ("classes/kotlin/jmh", "jmh-generated-classes", "jmh-generated-resources"):
                path = workspace / "quality/remote-benchmarks/build" / directory
                path.mkdir(parents=True)
                (path / "fixture.class").write_bytes(b"synthetic fixture")
            for project in data["runtime_projects"] + [":performance-testkit"]:
                binary = workspace.joinpath(*project.strip(":").split(":"), "build/libs/module.jar")
                binary.parent.mkdir(parents=True)
                with zipfile.ZipFile(binary, "w") as target:
                    target.writestr("dev/s7a/strata/performance/JvmPerformanceMeter.class", b"synthetic collector")
                    for name in ("independent/Api.class", "independent/Core.class", "independent/Runtime.class"):
                        target.writestr(name, b"synthetic class")
            return workspace, binding

        def preserve_classpath(path, workspace):
            path.mkdir(parents=True)
            collector = workspace / "performance-testkit/build/libs/module.jar"
            copied = path / "collector.jar"
            copied.write_bytes(collector.read_bytes())
            PAIRED.write_new(path / "classpath.json", {"schema": "strata-paired-jvm-classpath-v1", "entries": [{"source": str(collector), "path": str(copied), "sha256": PAIRED.digest(collector)}]})

        def invoke(command, repository, console):
            request = PAIRED.read_json(Path(command[command.index("-Request") + 1]))
            directory = console.parent
            name = directory.name
            arguments = request["arguments"]
            calls.append(name)
            self.assertEqual((request["minimumFreePhysicalBytes"], request["jobCommittedBytes"], request["workSeconds"], request["cleanupSeconds"]), (4 * 1024**3, 3 * 1024**3, 1800, 30))
            options = {item.split("=", 1)[0]: item.split("=", 1)[1] for item in arguments if "=" in item}
            selected = options.get("-Pstrata.paired.inputProperties")
            logs = ["paired.jvm.origin " + json.dumps({"task": arguments[0]})]
            property_lines = []
            if selected:
                self.assertIn("-Pstrata.performance.fixtureInputs", options)
                mapping = Path(options["-Pstrata.performance.fixtureInputs"]).read_text()
                self.assertIn("baseline=", mapping)
                self.assertIn("candidate=", mapping)
                bindings = PAIRED.read_json(Path(selected))["entries"]
                for property_name, pin in bindings.items():
                    record = {"task": arguments[0], "property": property_name, **pin}
                    logs.append("paired.jvm.inputProperty " + json.dumps(record))
                    property_lines.append(property_name + "=" + pin["path"])
                applied.append((name, selected))
            if failure == "missing-property" and name.startswith("admit-"):
                property_lines = []
            diagnostic = output / "vms" / f"{len(calls)}.log"
            diagnostic.write_text("<vm_arguments><properties>\n" + "\n".join(property_lines) + "\n</properties></vm_arguments>\n")
            PAIRED.write_new(directory / "vms.json", [{"role": "java_exec", "diagnostic_sha256": PAIRED.digest(diagnostic)}])
            console.write_text("\n".join(logs) + "\n")
            terminal = {"exitCode": 0, "naturalRelease": True, "handlesClosed": True, "jobName": "synthetic", "inheritedAffinityMask": 1, "systemAffinityMask": 1}
            PAIRED.write_new(directory / "terminal.json", terminal)
            PAIRED.write_new(directory / "child-admission.json", {"inOwnedJob": True, "jobName": "synthetic", "availablePhysicalBytes": 4 * 1024**3})
            if "-Pstrata.paired.preserveClasspath" in options:
                preserve_classpath(Path(options["-Pstrata.paired.preserveClasspath"]), Path(request["workspace"]))
            if failure in ("changed-unselected-input", "changed-unselected-selector") and name.startswith("admit-"):
                unselected = output / "input-properties-candidate.json"
                if failure == "changed-unselected-selector":
                    unselected.write_bytes(b"changed")
                else:
                    pin = next(iter(PAIRED.read_json(unselected)["entries"].values()))
                    Path(pin["path"]).write_bytes(b"changed")
            if name.startswith("collect-") and "-Pstrata.performance.output" in options:
                destination = Path(options["-Pstrata.performance.output"])
                destination.mkdir(parents=True)
                PAIRED.write_new(destination / "receipt.json", {"status": "passed", "fork_verification": "loaded-artifacts-per-iteration-v1", "registered_workloads": ["all", "control"], "run_id": name})
            if "-Pstrata.performance.operationRequest" in options:
                operation = PAIRED.read_json(Path(options["-Pstrata.performance.operationRequest"]))
                destination = Path(operation["output"])
                destination.mkdir(parents=True)
                PAIRED.write_new(destination / "report.json", {"status": "passed", "phases": [{"control": True}, {"control": False}], "run_id": name})
            if "-Pstrata.performance.request" in options:
                comparison = PAIRED.read_json(Path(options["-Pstrata.performance.request"]))
                key = "cases" if comparison["command"] == "jmh-comparison" else "phases"
                PAIRED.write_new(Path(comparison["output"]), {"status": "passed", key: [{}, {}]})
            if "-Pstrata.paired.report" in options:
                report = PAIRED.read_json(Path(options["-Pstrata.paired.report"]))
                if name.startswith("compare-"):
                    PAIRED.write_new(Path(report["arguments"][-1]), {"status": "passed", "phases": [{}, {}]})
                else:
                    PAIRED.write_new(Path(report["arguments"][0]), {"status": "passed", "run_id": name})
            return 0

        def preflight(*args):
            path = output / ("control-" + args[3])
            path.mkdir()
            PAIRED.write_new(path / "request.json", {"synthetic": True})
            return path

        original_input_property_files = PAIRED.input_property_files

        def prepare_selectors(*args):
            selectors = original_input_property_files(*args)
            if failure in ("changed-before-unselected-input", "changed-before-unselected-selector",
                           "missing-unselected-selector", "missing-unselected-input"):
                selector = selectors["candidate"]["path"]
                archive = Path(next(iter(PAIRED.read_json(selector)["entries"].values()))["path"])
                target = selector if failure.endswith("selector") else archive
                if failure.startswith("missing-"):
                    target.unlink()
                else:
                    target.write_bytes(b"changed before admission")
            return selectors

        platform = SimpleNamespace(name="nt", path=os.path, environ=os.environ)
        executable = Path(PAIRED.sys.executable).resolve()
        with ExitStack() as stack:
            stack.enter_context(patch.object(PAIRED, "os", platform))
            stack.enter_context(patch.object(PAIRED, "sys", SimpleNamespace(executable=str(executable))))
            stack.enter_context(patch.dict(os.environ, {"JAVA_HOME": str(homes["launcher"]), "STRATA_PAIRED_TOOLCHAIN": str(homes["toolchain"])}))
            for name, implementation in {"git": lambda *args: "a" * 40, "preserve_driver_sources": lambda *args: {}, "verify_driver_sources": lambda *args: None, "materialize": materialize, "source_guard": lambda *args: None, "archive_file": lambda workspace, project: workspace.joinpath(*project.strip(":").split(":"), "build/libs/module.jar"), "class_load_inventory": lambda *args: {}, "preserve_class_loads": lambda *args: {}, "invoke": invoke, "verify_vms": lambda *args, **kwargs: None, "preflight_request": preflight, "verify_preflight": lambda *args: None, "verify_owner_report": lambda *args: None, "table": lambda *args: None, "input_property_files": prepare_selectors}.items():
                stack.enter_context(patch.object(PAIRED, name, implementation))
            stack.enter_context(patch.object(PAIRED.shutil, "which", return_value=str(homes["launcher"] / "bin/java.exe")))
            stack.enter_context(patch.object(PAIRED.subprocess, "check_output", return_value="{}"))
            if failure is None:
                PAIRED.run(root, manifest, output)
            else:
                expected_message = ("Missing or unsafe pinned file" if failure.startswith("missing-unselected-")
                                    else "Inventory property was missing" if failure == "missing-property"
                                    else "Frozen inventory")
                with self.assertRaisesRegex(ValueError, expected_message):
                    PAIRED.run(root, manifest, output)
        self.assertEqual(PAIRED.read_json(output / "attempt.json")["executables"][0],
                         {"path": str(executable), "sha256": PAIRED.digest(executable)})
        if failure is None:
            self.assertEqual(PAIRED.read_json(output / "attempt.json")["status"], "passed")
            self.assertEqual(len([name for name in calls if name.startswith("collect-whole-avgt-")]), 6)
            self.assertEqual(len([name for name in calls if name.startswith("collect-whole-sample-")]), 6)
            self.assertEqual(len([name for name in calls if name.startswith("collect-whole-operations-")]), 6)
            self.assertEqual(len([name for name in calls if name.startswith("collect-owner-owner-")]), 6)
            self.assertIn("compare-owner-owner", dict(applied))
            self.assertEqual(len([name for name, _ in applied if name.startswith("admit-")]), 2)
            self.assertEqual(len([name for name, _ in applied if name.startswith("preflight-")]), 2)
        else:
            self.assertFalse(any(name.startswith("collect-") or name.startswith("compare-") for name in calls))
            if "before" in failure or failure.startswith("missing-unselected-"):
                self.assertFalse(any(name.startswith("admit-") for name in calls))
            self.assertEqual(PAIRED.read_json(output / "attempt.json")["status"], "failed")
