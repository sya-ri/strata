"""Exercise generic manifest selection and immutable evidence boundaries without Java or timing."""
from contextlib import redirect_stdout
import copy
import csv
import hashlib
import importlib.util
import io
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

    def selector_commit(self):
        self.selector_git("add", "--all")
        self.selector_git("commit", "--quiet", "--allow-empty", "-m", "Preserve complete selector fixture")
        return self.selector_git("rev-parse", "HEAD")

    def selector_repository(self, legacy=True):
        self.selector_git("init", "--quiet")
        self.selector_git("config", "core.autocrlf", "false")
        if legacy:
            self.selector_manifest(".github/performance/paired-jvm.json")
        return self.selector_commit()

    def test_real_git_inherited_default_does_not_schedule_unrelated_work_or_shadow_owned_nested(self):
        base = self.selector_repository()
        (self.root / "ordinary-source.txt").write_text("ordinary change", encoding="utf-8")
        head = self.selector_commit()
        self.assertEqual(PAIRED.select_manifest(self.root, base=base, head=head), "")
        legacy = ".github/performance/paired-jvm.json"
        self.assertEqual(PAIRED.select_manifest(self.root), legacy)
        self.assertEqual(PAIRED.select_manifest(self.root, legacy, base, head), legacy)
        nested = self.selector_manifest(".github/performance/independent-full/manifest.json")
        head = self.selector_commit()
        self.assertEqual(PAIRED.select_manifest(self.root, base=base, head=head), nested)
        self.assertEqual(PAIRED.load_manifest(self.root, self.root / nested), self.manifest)
        self.assertEqual((self.root / nested).read_bytes(), subprocess.check_output(
            ["git", "-C", str(self.root), "show", f"{head}:{nested}"]))
        with self.assertRaises(ValueError):
            PAIRED.select_manifest(self.root, ".github/performance/missing/manifest.json", base, head)

    def test_real_git_introduced_and_changed_legacy_keep_priority_over_multiple_nested_choices(self):
        base = self.selector_repository(legacy=False)
        legacy = self.selector_manifest(".github/performance/paired-jvm.json")
        first = self.selector_manifest(".github/performance/first/manifest.json")
        self.selector_manifest(".github/performance/second/manifest.json")
        head = self.selector_commit()
        self.assertEqual(PAIRED.select_manifest(self.root, base=base, head=head), legacy)
        self.assertEqual(PAIRED.select_manifest(self.root, first, base, head), first)
        base = head
        value = copy.deepcopy(self.manifest)
        value["suites"][0]["name"] = "changed-complete-corpus"
        self.selector_manifest(legacy, value)
        self.selector_manifest(first, value)
        head = self.selector_commit()
        self.assertEqual(PAIRED.select_manifest(self.root, base=base, head=head), legacy)
        base = head
        (self.root / legacy).unlink()
        self.selector_manifest(first, self.manifest)
        head = self.selector_commit()
        self.assertEqual(PAIRED.select_manifest(self.root, base=base, head=head), first)

    def test_real_git_identical_changed_nested_choices_are_ambiguous_and_manual_selection_resolves(self):
        base = self.selector_repository()
        first = self.selector_manifest(".github/performance/first/manifest.json")
        second = self.selector_manifest(".github/performance/second/manifest.json")
        head = self.selector_commit()
        with self.assertRaisesRegex(ValueError, "Ambiguous.*first/manifest.json.*second/manifest.json"):
            PAIRED.select_manifest(self.root, base=base, head=head)
        self.assertEqual(PAIRED.select_manifest(self.root, second, base, head), second)
        self.assertEqual((self.root / first).read_bytes(), (self.root / second).read_bytes())

    def test_real_git_deleted_and_renamed_nested_manifest_follow_no_rename_diff(self):
        base = self.selector_repository(legacy=False)
        old = self.selector_manifest(".github/performance/old/manifest.json")
        base = self.selector_commit()
        new = ".github/performance/new/manifest.json"
        (self.root / new).parent.mkdir()
        (self.root / old).rename(self.root / new)
        head = self.selector_commit()
        self.assertEqual(PAIRED.select_manifest(self.root, base=base, head=head), new)
        base = head
        (self.root / new).unlink()
        head = self.selector_commit()
        self.assertEqual(PAIRED.select_manifest(self.root, base=base, head=head), "")
        self.assertEqual(PAIRED.select_manifest(self.root), "")

    def test_real_git_other_json_and_unchanged_or_flat_examples_do_not_become_candidates(self):
        self.selector_repository()
        self.selector_manifest(".github/performance/pilot/manifest.json")
        base = self.selector_commit()
        self.selector_manifest(".github/performance/ordinary/receipt.json", {"schema": "independent-receipt-v1"})
        self.selector_manifest(".github/performance/paired-jvm.example.json")
        head = self.selector_commit()
        self.assertEqual(PAIRED.select_manifest(self.root, base=base, head=head), "")

    def test_real_git_selection_rejects_invalid_schema_pins_json_and_unsafe_paths(self):
        base = self.selector_repository()
        path = self.selector_manifest(".github/performance/owned/manifest.json")
        valid = json.dumps(self.manifest)
        unsupported = copy.deepcopy(self.manifest)
        unsupported["schema"] = "strata-paired-jvm-ci-v2"
        malformed_schema = copy.deepcopy(self.manifest)
        malformed_schema["schema"] = 17
        wrong_schema = copy.deepcopy(self.manifest)
        wrong_schema["schema"] = "unrelated-receipt-v1"
        invalid_pin = copy.deepcopy(self.manifest)
        invalid_pin["inputs"][0]["pin"]["sha256"] = "0" * 64
        missing_schema = copy.deepcopy(self.manifest)
        missing_schema.pop("schema")
        incomplete = copy.deepcopy(self.manifest)
        incomplete.pop("sources")
        for content in ("{", '{"schema":"strata-paired-jvm-ci-v1","schema":"strata-paired-jvm-ci-v1"}',
                        json.dumps(unsupported), json.dumps(malformed_schema), json.dumps(wrong_schema),
                        json.dumps(dict(self.manifest, schema=None)), json.dumps(dict(self.manifest, schema=[])),
                        json.dumps(invalid_pin), json.dumps(incomplete), json.dumps(missing_schema)):
            with self.subTest(content=content):
                (self.root / path).write_text(content, encoding="utf-8")
                head = self.selector_commit()
                with self.assertRaises(ValueError):
                    PAIRED.select_manifest(self.root, base=base, head=head)
        (self.root / path).write_text(valid, encoding="utf-8")
        head = self.selector_commit()
        for unsafe in ("../manifest.json", "/manifest.json", ".github/performance/owned/../owned/manifest.json"):
            with self.subTest(unsafe=unsafe), self.assertRaises(ValueError):
                PAIRED.select_manifest(self.root, unsafe, base, head)
        unsafe = self.selector_manifest(".github/performance/unsafe name/manifest.json")
        head = self.selector_commit()
        with self.assertRaisesRegex(ValueError, "Invalid repository path"):
            PAIRED.select_manifest(self.root, base=base, head=head)
        self.assertTrue((self.root / unsafe).is_file())

    def test_real_git_selection_rejects_wrong_checkout_and_uncommitted_manifest(self):
        base = self.selector_repository()
        path = self.selector_manifest(".github/performance/owned/manifest.json")
        head = self.selector_commit()
        with self.assertRaisesRegex(ValueError, "checkout differs"):
            PAIRED.select_manifest(self.root, base=base, head=base)
        with self.assertRaisesRegex(ValueError, "both PR"):
            PAIRED.select_manifest(self.root, base=base)
        value = copy.deepcopy(self.manifest)
        value["suites"][0]["name"] = "uncommitted"
        self.selector_manifest(path, value)
        with self.assertRaisesRegex(ValueError, "manifest differs"):
            PAIRED.select_manifest(self.root, base=base, head=head)
        self.selector_manifest(path)
        self.input.write_text("changed pinned input", encoding="utf-8")
        with self.assertRaises(ValueError):
            PAIRED.select_manifest(self.root, base=base, head=head)

    def test_real_git_base_branch_changes_are_not_current_pull_request_intent(self):
        common = self.selector_repository()
        (self.root / "own-source.txt").write_text("own source change", encoding="utf-8")
        head = self.selector_commit()
        self.selector_git("checkout", "--quiet", "--detach", common)
        value = copy.deepcopy(self.manifest)
        value["suites"][0]["name"] = "base-branch-corpus"
        self.selector_manifest(".github/performance/paired-jvm.json", value)
        base = self.selector_commit()
        self.selector_git("checkout", "--quiet", "--detach", head)
        self.assertEqual(PAIRED.select_manifest(self.root, base=base, head=head), "")

    def test_real_git_cli_emits_exact_owned_path_and_preserves_workflow_head_binding(self):
        base = self.selector_repository()
        path = self.selector_manifest(".github/performance/owned/manifest.json")
        head = self.selector_commit()
        output = self.root / "github-output"
        console = io.StringIO()
        arguments = ["paired-jvm.py", "--select", "--base", base, "--head", head]
        with patch.object(PAIRED, "__file__", str(self.root / "gradle/performance/paired-jvm.py")), \
                patch.object(PAIRED.sys, "argv", arguments), patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}), \
                redirect_stdout(console):
            PAIRED.main()
        self.assertEqual(console.getvalue(), path + "\n")
        self.assertEqual(output.read_text(encoding="utf-8"), "paired_manifest=" + path + "\n")
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/jvm.yml").read_text(encoding="utf-8")
        planning, execution = workflow.split("  paired-jvm:", 1)
        head_ref = "ref: ${{ github.event.pull_request.head.sha || github.sha }}"
        self.assertIn(head_ref, planning)
        self.assertIn(head_ref, execution)
        self.assertIn('args+=(--base "$BASE" --head "$HEAD")', planning)
        self.assertIn('args+=(--manifest "$MANIFEST")', planning)
        self.assertNotIn('MANIFEST:-', planning)

    def test_explicit_empty_cli_path_never_falls_back_or_invokes_collector(self):
        self.selector_repository()
        for selection in (False, True):
            arguments = ["paired-jvm.py", "--manifest", ""]
            arguments += ["--select"] if selection else ["--output", str(self.root / "fresh-attempt")]
            with self.subTest(selection=selection), \
                    patch.object(PAIRED, "__file__", str(self.root / "gradle/performance/paired-jvm.py")), \
                    patch.object(PAIRED.sys, "argv", arguments), patch.object(PAIRED, "run") as collector:
                with self.assertRaises(ValueError):
                    PAIRED.main()
                collector.assert_not_called()
                self.assertFalse((self.root / "fresh-attempt").exists())

    def test_generated_selection_without_fixture_registry(self):
        self.assertEqual(self.load(self.manifest), self.manifest)
        value = copy.deepcopy(self.manifest)
        value["suites"][0].update(project=":quality:component-benchmarks", benchmarks=["AnotherNewBenchmark"], workloads=[], parameters={})
        self.assertEqual(self.load(value), value)
        value["suites"][0].update(project=":quality:remote-benchmarks", benchmarks=[], workloads=["RemoteProtocolBenchmark.diff"])
        self.assertEqual(self.load(value), value)

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
                diagnostic = directory / "class-loads/classes-3708-2026-10-12_23-45-44.log"
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
                diagnostic = directory / "class-loads/classes-8188-2026-10-13_06-15-24.log"
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
