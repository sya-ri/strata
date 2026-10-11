"""Exercise generic manifest selection and immutable evidence boundaries without Java or timing."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import tempfile
import textwrap
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location("paired_jvm", Path(__file__).resolve().parents[1] / "performance/paired-jvm.py")
PAIRED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAIRED)


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

    def test_paths_and_json_cannot_escape_or_override_pins(self):
        for value in ("../outside", "C:/outside", "a\\b", "/outside", "a/../b", "./a", ".git/index", "a\nb", "a//b"):
            with self.subTest(path=value), self.assertRaises(ValueError):
                PAIRED.relative_path(self.root, value)
        path = self.root / "bad.json"
        for value in ('{"schema":1,"schema":2}', '{"count":NaN}'):
            path.write_text(value, encoding="utf-8")
            with self.assertRaises(ValueError):
                PAIRED.read_json(path)

    def test_preserve_failed_attempt_and_every_hidden_evidence_byte(self):
        output = self.root / "attempt"
        output.mkdir()
        PAIRED.write_new(output / "attempt.json", {"status": "failed"})
        (output / ".hidden-provenance").write_bytes(b"actual bytes")
        intermediate = output / "workspaces"
        intermediate.mkdir()
        (intermediate / "cache.bin").write_bytes(b"cache")
        archive = PAIRED.pack(output)
        with zipfile.ZipFile(archive) as saved:
            self.assertEqual(set(saved.namelist()), {"attempt.json", ".hidden-provenance"})
            self.assertEqual(saved.read(".hidden-provenance"), b"actual bytes")
        with self.assertRaises(FileExistsError):
            PAIRED.write_new(output / "attempt.json", {"status": "passed"})
        with self.assertRaises(FileExistsError):
            PAIRED.pack(output)

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
                   lambda value: value["members"]["123"].update(creationFileTime=116444736100000000), lambda value: value["members"].clear()]
        for index, change in enumerate(changes):
            directory = self.root / str(index)
            directory.mkdir()
            (directory / "console.log").write_text("[123][info][class,load] org.openjdk.jmh.runner.ForkedMain source: file:/frozen.jar\n", encoding="utf-8")
            log = directory / "vm.log"
            log.write_text(document, encoding="utf-8")
            actual = copy.deepcopy(terminal)
            change(actual)
            if index == 0:
                PAIRED.verify_vms(directory, [log], actual, allowed, 1)
                self.assertTrue((directory / "vms.json").is_file())
            else:
                with self.assertRaises(ValueError):
                    PAIRED.verify_vms(directory, [log], actual, allowed, 1)

    def test_early_setup_failure_preserves_failed_attempt_without_java(self):
        output = self.root / "early-failure"
        manifest = self.root / "manifest.json"
        manifest.write_text("{}", encoding="utf-8")
        with patch.object(PAIRED.os, "name", "nt"), patch.object(PAIRED, "git", return_value="a" * 40), patch.dict(os.environ, {}, clear=True):
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
        jobs = {key: {"result": "skipped"} for key in ("paired-jvm", "checks", "web-demos", "web", "workflow-checks", "minecraft-matrix", "minecraft", "documentation", "docs")}
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
