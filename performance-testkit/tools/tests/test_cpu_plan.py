"""Synthetic invariant tests. No invented timings or hosts are performance evidence."""
from contextlib import contextmanager
import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cpu_plan as cpu


class CpuPlanTest(unittest.TestCase):
    """Exercise completeness, immutable bytes, identities and whole-attempt retry boundaries."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.profile = {"contract": "strata-cpu-host-v1", "conditions": {"physical_id": "synthetic-host-0", "cpu_model": ["synthetic"],
                        "affinity": [0], "power": "synthetic-controlled", "os": "synthetic", "boot_id": "synthetic-boot", "java_sha256": "c" * 64, "python_sha256": "d" * 64}, "java": "synthetic-java", "sources": {"synthetic": True}}
        self.config = {"max_attempts": 2, "preparation_receipts": [], "interleaving": [["baseline", 0], ["candidate", 0], ["candidate", 1],
                       ["baseline", 1], ["baseline", 2], ["candidate", 2]], "corpora": [], "executors": [], "shards": []}
        for index in range(2):
            profile = copy.deepcopy(self.profile)
            profile["conditions"]["physical_id"] = "synthetic-host-" + str(index)
            path = self.root / ("profile-" + str(index) + ".json")
            cpu.write_new(path, profile)
            note = self.root / ("qualification-" + str(index) + ".txt")
            note.write_text("Synthetic qualification; not hardware or performance proof.")
            self.config["executors"].append({"id": "executor-" + str(index), "profile": str(path), "qualification": str(note)})
            self.config["shards"].append({"id": "shard-" + str(index), "executor": "executor-" + str(index), "selectors": {}})
        collector = self.root / "collector.jar"
        collector.write_bytes(b"synthetic collector")
        self.config["comparator"] = {"java": "synthetic-java", "classpath": [str(collector)]}
        for corpus_name in ("Image", "Protocol"):
            entry = {"id": corpus_name, "admissions": {}, "commands": {}, "source_archives": {}}
            for variant in cpu.Variant:
                source = self.root / (corpus_name + "-" + variant.value + ".zip")
                source.write_bytes(b"synthetic source " + variant.value.encode())
                entry["source_archives"][variant.value] = str(source)
                entry["commands"][variant.value] = ["synthetic-collector", "{parameters}", "{context}", "{output}", "{mode}", "{repetition}", "{methods}"]
            for mode in cpu.Mode:
                entry["admissions"][mode.value] = {}
                for variant in cpu.Variant:
                    entry["admissions"][mode.value][variant.value] = str(self.make_admission(corpus_name, mode, variant))
                    self.config["preparation_receipts"].append(str(self.synthetic_phase(cpu.Phase.PREPARATION,
                        {"suite": corpus_name, "mode": mode.value, "variant": variant.value},
                        [entry["admissions"][mode.value][variant.value]], self.root / "preparation" / (corpus_name + mode.value + variant.value))))
            self.config["corpora"].append(entry)
            for index, shard in enumerate(self.config["shards"]):
                shard["selectors"][corpus_name] = {"parameters": {"size": [str(index + 1)]}}
        self.config_path = self.root / "config.json"
        cpu.write_new(self.config_path, self.config)
        self.plan_root = self.root / "plan"

    def synthetic_phase(self, phase, binding, artifacts, root):
        """Create explicit fake phase sources for rejection tests, not measured costs."""
        root.mkdir(parents=True)
        spec = {"phase": phase.value, "binding": binding, "command": ["synthetic-test-only"], "workspace": str(self.root), "artifacts": artifacts}
        cpu.write_new(root / "spec.json", spec)
        (root / "command.log").write_text("Synthetic invariant fixture, not performance evidence.")
        cpu.write_new(root / "phase.json", {"contract": "strata-cpu-phase-v1", "phase": phase.value, "phase_id": str(uuid.uuid4()),
            "binding": binding, "spec_sha256": cpu.digest(root / "spec.json"), "log_sha256": cpu.digest(root / "command.log"), "status": "passed",
            "cost": {"exit_code": 0, "cpu_seconds": 0.1}, "elapsed_seconds": 0.2,
            "artifacts": {str(path): {"sha256": cpu.digest(path), "bytes": Path(path).stat().st_size} for path in artifacts}})
        return root / "phase.json"

    def make_admission(self, name, mode, variant):
        """Create preserved fake bytes solely for deterministic validation tests."""
        root = self.root / "admissions" / name / mode.value / variant.value
        root.mkdir(parents=True)
        sources = {}
        for label, data in (("fixture." + name, b"synthetic fixture " + name.encode()), ("generated-registry", b"synthetic generated metadata"),
                            ("collector", b"synthetic collector"), ("harness", b"synthetic harness")):
            archive = root / (label + ".bin")
            archive.write_bytes(data)
            sources[label] = {"kind": "file", "sha256": cpu.digest(archive), "archive": archive.name, "archive_sha256": cpu.digest(archive)}
        target = root / "target.jar"
        target.write_bytes(b"synthetic target " + variant.value.encode())
        control = root / "control.bin"
        control.write_bytes(b"synthetic control")
        target_entry = {"kind": "file", "sha256": cpu.digest(target), "archive": target.name, "archive_sha256": cpu.digest(target)}
        control_entry = {"kind": "file", "sha256": cpu.digest(control), "archive": control.name, "archive_sha256": cpu.digest(control)}
        probe = root / "probe.py"
        shutil.copyfile(Path(cpu.__file__).with_name("cpu_host.py"), probe)
        probe_entry = {"kind": "file", "sha256": cpu.digest(probe), "archive": probe.name, "archive_sha256": cpu.digest(probe)}
        module = {"module": "runtime", "status": "resolved", "representativeClass": "fixture.Runtime", "classTree": {"sha256": "a" * 64},
                  "classResource": {"sha256": "b" * 64}, "codeSource": {"sha256": cpu.digest(target)}}
        collector = {"code_source_sha256": sources["collector"]["sha256"]}
        fixtures = {"fixture." + name: sources["fixture." + name]["sha256"]}
        identity = {"runtime": {"modules": [module]}, "fixtures": fixtures, "collector": collector,
                    "harness": sources["harness"]["sha256"], "inputs": {str(control): control_entry["sha256"], str(probe): probe_entry["sha256"]}}
        inventory = [json.dumps(["fixture." + name + "." + method, mode.value, {"size": str(size)}])
                     for method in ("idle", "update") for size in (1, 2)]
        value = {"contract": "strata-jmh-cpu-admission-v1", "status": "passed", "collector_identity": collector, "sources": sources,
                 "inputs": {"control": control_entry, "cpu-executor-probe": probe_entry}, "targets": {"runtime": target_entry}, "identity": identity,
                 "registered_workloads": inventory, "arguments": ["fixture." + name + ".*", "-bm", mode.value, "-wi", "1", "-i", "2", "-prof", "gc"]}
        path = root / "admission.json"
        cpu.write_new(path, value)
        return path

    def freeze(self):
        self.plan = cpu.freeze(self.config_path, self.plan_root)
        return self.plan

    @contextmanager
    def synthetic_host(self, index=0):
        """Replace observations only in tests; production has no profile override switch."""
        value = copy.deepcopy(self.profile)
        value["conditions"]["physical_id"] = "synthetic-host-" + str(index)
        with patch.object(cpu, "observe", return_value=value), patch.object(cpu, "exclusive_lease", return_value=self.fake_lease()):
            yield

    @contextmanager
    def fake_lease(self):
        yield str(uuid.uuid4())

    def synthetic_process(self, command, workspace, log):
        """Emit fake complete fork provenance instead of running a benchmark in invariant tests."""
        _, parameter, context_path, output, mode, repetition, _ = command
        context = cpu.document(context_path)
        shard = next(s for s in self.plan["shards"] if s["id"] == context["shard_id"])
        source = self.plan["corpora"][context["suite"]]["modes"][mode]["sides"][context["variant"]]
        admission = source["admission"]
        output = Path(output)
        output.mkdir()
        run_id = str(uuid.uuid4())
        identity = copy.deepcopy(admission["identity"])
        raw = []
        selected = shard["selections"][context["suite"]]["modes"][mode]
        for workload in selected:
            benchmark, _, parameters = json.loads(workload)
            row = {"benchmark": benchmark, "mode": mode, "params": parameters, "forks": 1, "warmupIterations": 1, "measurementIterations": 2,
                   "secondaryMetrics": {"strata.all_operations": {"rawData": [[5, 7]]}, "gc.alloc.rate.norm": {"rawData": [[10, 12]]}}}
            raw.append(row)
            fork_id = str(uuid.uuid4())
            for index, kind in enumerate(("WARMUP", "MEASUREMENT", "MEASUREMENT")):
                cpu.write_new(output / "cpu-forks" / (fork_id + "-" + str(index) + ".json"),
                              {"contract": "strata-jmh-cpu-fork-v1", "status": "passed", "run_id": run_id, "context": context,
                               "fork_id": fork_id, "iteration_id": str(uuid.uuid4()), "iteration": index, "kind": kind,
                               "java_sha256": "c" * 64,
                               "process_start_unix_millis": 1000, "verified_ready_unix_millis": 1100,
                               "executor_observation": {"conditions": context["executor_profile"], "sources": {"probe_sha256": context["executor_probe_sha256"]}},
                               "executor_after": {"conditions": context["executor_profile"], "sources": {"probe_sha256": context["executor_probe_sha256"]}},
                               "workload": workload, "all_operations": (1, 5, 7)[index], "loaded_identity": identity})
        cpu.write_new(output / "results.json", raw)
        receipt = {"contract": "strata-jmh-v1", "status": "passed", "repetition": int(repetition), "cpu_context": context,
                   "run_id": run_id, "arguments": admission["arguments"], "fixture_identity": identity["fixtures"],
                   "harness_sha256": identity["harness"], "collector_identity": admission["collector_identity"],
                   "runtime_metadata": identity["runtime"], "target_archives": {"runtime": "target.jar"},
                   "inputs": {key: {"sha256": entry["sha256"], "archive": entry["archive"]} for key, entry in admission["inputs"].items()},
                   "registered_workloads": selected, "results_sha256": cpu.digest(output / "results.json")}
        source_root = self.plan_root / source["path"]
        for key, output_name in (("collector", "collector.jar"), ("harness", "harness.jar")):
            shutil.copyfile(source_root.parent / admission["sources"][key]["archive"], output / output_name)
        shutil.copyfile(source_root.parent / "target.jar", output / "target.jar")
        shutil.copyfile(source_root.parent / "control.bin", output / "control.bin")
        shutil.copyfile(source_root.parent / "probe.py", output / "probe.py")
        cpu.write_new(output / "receipt.json", receipt)
        cpu.write_new(output / "cpu-costs.json", {"contract": "strata-jmh-cpu-costs-v1", "run_id": run_id, "context": context,
                      "parent_preparation_seconds": 0.1, "harness_seconds": 0.1, "parent_processing_seconds": 0.1})
        return {"exit_code": 0, "cpu_seconds": 0.1, "elapsed_seconds": 0.2, "source": "synthetic test only"}

    def collect(self, scheduling="serial"):
        with self.synthetic_host():
            attempt = cpu.begin(self.plan_root, self.root / "collection", scheduling)
        for index, shard in enumerate(self.plan["shards"]):
            with self.synthetic_host():
                cpu.grant(self.plan_root, attempt, shard["id"])
            with self.synthetic_host(index), patch.object(cpu, "run_process", side_effect=self.synthetic_process):
                cpu.run_shard(self.plan_root, attempt, shard["id"], self.root)
            with self.synthetic_host():
                cpu.acknowledge(self.plan_root, attempt, shard["id"])
        return attempt

    def comparator(self, plan_root, plan, target, baseline, candidate):
        """The only fake comparator is injected inside tests, never through the production CLI."""
        self.assertEqual(3, len(baseline))
        self.assertEqual(3, len(candidate))
        self.assertEqual(set(range(3)), {cpu.document(p / "receipt.json")["repetition"] for p in baseline})
        result = {"contract": "strata-jmh-comparison-v1", "status": "passed", "synthetic": True}
        cpu.write_new(target, result)
        return result

    def finish(self, attempt):
        with self.synthetic_host(), patch.object(cpu, "strict_compare", side_effect=self.comparator) as comparator:
            result = cpu.finish(self.plan_root, attempt)
        if result["status"] == "passed":
            self.assertEqual(8, comparator.call_count)
        return result

    def mutate(self, path, callback):
        value = cpu.document(path)
        callback(value)
        Path(path).write_bytes(cpu.encoded(value))

    def test_two_generic_corpora_keep_full_disjoint_inventory_and_distinct_modes(self):
        plan = self.freeze()
        self.assertEqual(16, sum(len(mode["rows"]) for suite in plan["corpora"].values() for mode in suite["modes"].values()))
        self.assertEqual(plan, cpu.load_plan(self.plan_root))
        self.assertEqual({"avgt", "sample"}, set(plan["corpora"]["Image"]["modes"]))

    def test_reject_partition_holes_overlap_foreign_methods_and_parameter_values(self):
        mutations = [lambda c: c["shards"].pop(),
                     lambda c: c["shards"][1]["selectors"]["Image"].update(parameters={"size": ["1"]}),
                     lambda c: c["shards"][1]["selectors"]["Image"].update(methods=["fixture.Image.unknown"]),
                     lambda c: c["shards"][1]["selectors"]["Image"].update(parameters={"size": ["3"]}),
                     lambda c: c["corpora"][0]["admissions"].pop("sample"),
                     lambda c: c["interleaving"].pop(),
                     lambda c: c["shards"][0]["selectors"].pop("Protocol")]
        for index, mutation in enumerate(mutations):
            with self.subTest(index=index):
                value = copy.deepcopy(self.config)
                mutation(value)
                path = self.root / ("invalid-" + str(index) + ".json")
                cpu.write_new(path, value)
                with self.assertRaises((ValueError, KeyError)):
                    cpu.freeze(path, self.root / ("invalid-plan-" + str(index)))

    def test_reject_duplicate_compiled_rows_and_changed_preserved_bytes(self):
        path = Path(self.config["corpora"][0]["admissions"]["avgt"]["baseline"])
        self.mutate(path, lambda value: value["registered_workloads"].append(value["registered_workloads"][0]))
        with self.assertRaises(ValueError):
            self.freeze()

    def test_reject_physical_executor_aliases_and_mutated_selector_after_freeze(self):
        self.freeze()
        self.mutate(self.plan_root / "plan.json", lambda value: value["shards"][0]["selections"]["Image"]["selector"]["parameters"].update(size=["2"]))
        with self.assertRaisesRegex(ValueError, "selector"):
            cpu.load_plan(self.plan_root)
        profile = Path(self.config["executors"][1]["profile"])
        self.mutate(profile, lambda value: value["conditions"].update(physical_id="synthetic-host-0"))
        with self.assertRaisesRegex(ValueError, "independent"):
            cpu.freeze(self.config_path, self.root / "aliased-plan")

    def test_complete_whole_attempt_delegates_every_pair_to_existing_strict_comparator(self):
        self.freeze()
        outcome = self.finish(self.collect())
        self.assertEqual("passed", outcome["status"])
        self.assertEqual(48, outcome["collector_invocations"])
        self.assertEqual(96, outcome["forks"])
        self.assertEqual(288, outcome["iterations"])
        self.assertTrue(outcome["preserved_sources"])

    def test_missing_suite_mode_repetition_and_mixed_attempts_reject_whole_attempt(self):
        self.freeze()
        mutations = [lambda value: value["slots"].pop(),
                     lambda value: value["slots"][0]["context"].update(mode="sample"),
                     lambda value: value["slots"][0]["context"].update(suite="Protocol"),
                     lambda value: value["slots"][0]["context"].update(whole_attempt=str(uuid.uuid4())),
                     lambda value: value["slots"][0]["context"].update(shard_attempt=str(uuid.uuid4())),
                     lambda value: value.update(executor="executor-1"),
                     lambda value: value["slots"].append(copy.deepcopy(value["slots"][0]))]
        for index, mutation in enumerate(mutations):
            with self.subTest(index=index):
                attempt = self.collect()
                path = attempt / "shards/shard-0/shard.json"
                self.mutate(path, mutation)
                # Update the acknowledgement to exercise semantic validation beyond byte checks.
                self.mutate(attempt / "acks/shard-0.json", lambda value: value.update(shard_sha256=cpu.digest(path)))
                result = self.finish(attempt)
                self.assertEqual("failed", result["status"])
                # Each subtest is an independent collection, not another unbounded retry.
                shutil.move(str(self.root / "collection"), str(self.root / ("rejected-" + str(index))))

    def test_changed_raw_archive_settings_conditions_and_reused_invocations_reject(self):
        self.freeze()
        for index, kind in enumerate(("input", "runtime", "harness", "collector", "settings", "fixture", "condition", "run", "fork", "fork-host", "startup", "cost")):
            with self.subTest(kind=kind):
                attempt = self.collect()
                root = attempt / "shards/shard-0"
                first, second = cpu.document(root / "shard.json")["slots"][:2]
                directory = root / first["output"]
                if kind in ("input", "runtime", "harness", "collector"):
                    path = directory / {"input": "control.bin", "runtime": "target.jar", "harness": "harness.jar", "collector": "collector.jar"}[kind]
                    path.write_bytes(b"changed")
                elif kind == "settings":
                    self.mutate(directory / "receipt.json", lambda value: value["arguments"].append("-r"))
                elif kind == "fixture":
                    self.mutate(directory / "receipt.json", lambda value: value["fixture_identity"].update({"fixture.Image": "f" * 64}))
                elif kind == "condition":
                    self.mutate(root / first["after"], lambda value: value["conditions"].update(power="changed"))
                elif kind == "run":
                    run_id = cpu.document(directory / "receipt.json")["run_id"]
                    self.mutate(root / second["output"] / "receipt.json", lambda value: value.update(run_id=run_id))
                elif kind == "fork":
                    path = next((directory / "cpu-forks").glob("*.json"))
                    self.mutate(path, lambda value: value.update(iteration_id="reused"))
                    another = next(p for p in (directory / "cpu-forks").glob("*.json") if p != path)
                    self.mutate(another, lambda value: value.update(iteration_id="reused"))
                elif kind == "fork-host":
                    path = next((directory / "cpu-forks").glob("*.json"))
                    self.mutate(path, lambda value: value["executor_after"]["conditions"].update(power="changed"))
                elif kind == "startup":
                    path = next((directory / "cpu-forks").glob("*.json"))
                    self.mutate(path, lambda value: value.update(verified_ready_unix_millis=0))
                else:
                    (directory / "cpu-costs.json").unlink()
                self.assertEqual("failed", self.finish(attempt)["status"])
                shutil.move(str(self.root / "collection"), str(self.root / ("mutation-" + str(index))))

    def test_failed_attempt_retry_recollects_every_mode_suite_and_paired_slot(self):
        self.freeze()
        first = self.collect()
        shutil.rmtree(first / "shards/shard-1")
        self.assertEqual("failed", self.finish(first)["status"])
        second = self.collect()
        self.assertNotEqual(cpu.document(first / "attempt.json")["whole_attempt"], cpu.document(second / "attempt.json")["whole_attempt"])
        old = {cpu.document(path)["run_id"] for path in first.glob("shards/*/*/receipt.json")}
        new = {cpu.document(path)["run_id"] for path in second.glob("shards/*/*/receipt.json")}
        self.assertFalse(old.intersection(new))
        self.assertEqual(48, len(new))
        self.assertEqual("passed", self.finish(second)["status"])
        with self.synthetic_host(), self.assertRaisesRegex(ValueError, "limit|successful"):
            cpu.begin(self.plan_root, self.root / "collection", "serial")

    def test_serial_permits_cannot_overlap_and_partial_success_cannot_start_parallel(self):
        self.freeze()
        with self.synthetic_host():
            attempt = cpu.begin(self.plan_root, self.root / "collection", "serial")
            with self.assertRaisesRegex(ValueError, "predecessor"):
                cpu.grant(self.plan_root, attempt, "shard-1")
            with self.assertRaises((ValueError, FileNotFoundError)):
                cpu.begin(self.plan_root, self.root / "collection", "parallel")
            with self.assertRaises((ValueError, FileNotFoundError)):
                cpu.begin(self.plan_root, self.root / "collection", "serial")

    def test_schedule_comparison_revalidates_both_complete_fixed_executor_attempts(self):
        self.freeze()
        serial = self.collect()
        self.assertEqual("passed", self.finish(serial)["status"])
        parallel = self.collect("parallel")
        self.assertEqual("passed", self.finish(parallel)["status"])
        for root in (serial, parallel):
            attempt = cpu.document(root / "attempt.json")
            for shard in self.plan["shards"]:
                self.synthetic_phase(cpu.Phase.TRANSFER,
                    {"plan_sha256": attempt["plan_sha256"], "whole_attempt": attempt["whole_attempt"], "scheduling": attempt["scheduling"], "shard_id": shard["id"]},
                    [str(root / "shards" / shard["id"] / "shard.json")],
                    self.root / "collection/transfers" / (root.name + shard["id"]))
        output = self.root / "comparison.json"
        with self.synthetic_host(), patch.object(cpu, "strict_compare", side_effect=self.comparator) as comparator:
            value = cpu.compare_collection(self.plan_root, self.root / "collection", output)
        self.assertEqual(16, comparator.call_count)
        self.assertEqual({"serial", "parallel"}, set(value["scheduling_attempts"]))
        self.assertEqual([], value["failures"])
        self.assertIn("CPU", value["cpu_scope"])

    def test_outcome_cost_tampering_rejects_without_changes_to_raw_or_counts(self):
        self.freeze()
        serial = self.collect()
        self.assertEqual("passed", self.finish(serial)["status"])
        parallel = self.collect("parallel")
        self.assertEqual("passed", self.finish(parallel)["status"])
        path = serial / "outcome.json"
        original = path.read_bytes()
        for index, key in enumerate(("whole_wall_seconds", "aggregate_cpu_seconds", "measured_jmh_allocated_bytes", "aggregate_fork_startup_seconds", "processing_seconds")):
            with self.subTest(metric=key):
                self.mutate(path, lambda value: value.update({key: value[key] + 1}))
                with self.synthetic_host(), patch.object(cpu, "strict_compare", side_effect=self.comparator), self.assertRaisesRegex(ValueError, "Changed outcome clock|Changed derived"):
                    cpu.compare_collection(self.plan_root, self.root / "collection", self.root / ("tampered-" + str(index) + ".json"))
                path.write_bytes(original)

    def test_retry_keeps_successful_and_failed_prior_transfers_in_final_comparison(self):
        self.freeze()
        failed = self.collect()
        first = cpu.document(failed / "attempt.json")
        for index, shard in enumerate(self.plan["shards"]):
            phase = self.synthetic_phase(cpu.Phase.TRANSFER,
                {"plan_sha256": first["plan_sha256"], "whole_attempt": first["whole_attempt"], "scheduling": "serial", "shard_id": shard["id"]},
                [str(failed / "shards" / shard["id"] / "shard.json")], self.root / "collection/transfers" / ("prior-" + shard["id"]))
            if index == 1:
                self.mutate(phase, lambda value: value.update(status="failed", failure="Synthetic interrupted transfer", cost={"exit_code": 1, "cpu_seconds": 0.05}))
        (failed / "acks/shard-1.json").unlink()
        self.assertEqual("failed", self.finish(failed)["status"])
        serial = self.collect()
        self.assertEqual("passed", self.finish(serial)["status"])
        parallel = self.collect("parallel")
        self.assertEqual("passed", self.finish(parallel)["status"])
        for root in (serial, parallel):
            attempt = cpu.document(root / "attempt.json")
            for shard in self.plan["shards"]:
                self.synthetic_phase(cpu.Phase.TRANSFER,
                    {"plan_sha256": attempt["plan_sha256"], "whole_attempt": attempt["whole_attempt"], "scheduling": attempt["scheduling"], "shard_id": shard["id"]},
                    [str(root / "shards" / shard["id"] / "shard.json")], self.root / "collection/transfers" / (root.name + shard["id"]))
        with self.synthetic_host(), patch.object(cpu, "strict_compare", side_effect=self.comparator):
            result = cpu.compare_collection(self.plan_root, self.root / "collection", self.root / "retained-retry.json")
        self.assertEqual(1, len(result["failures"]))
        self.assertEqual(4, len(result["transfer_costs"]))
        self.assertEqual(2, len(result["retained_transfer_diagnostics"]))
        self.assertEqual({"passed", "failed"}, {value["phase"]["status"] for value in result["retained_transfer_diagnostics"]})
        self.synthetic_phase(cpu.Phase.TRANSFER,
            {"plan_sha256": first["plan_sha256"], "whole_attempt": str(uuid.uuid4()), "scheduling": "serial", "shard_id": "shard-0"},
            [str(serial / "shards/shard-0/shard.json")], self.root / "collection/transfers/foreign")
        with self.synthetic_host(), patch.object(cpu, "strict_compare", side_effect=self.comparator), self.assertRaisesRegex(ValueError, "omitted or foreign"):
            cpu.compare_collection(self.plan_root, self.root / "collection", self.root / "foreign-transfer.json")

    def test_duplicate_json_keys_and_unsafe_identifiers_reject(self):
        path = self.root / "duplicate.json"
        path.write_text('{"plan":1,"plan":2}')
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            cpu.document(path)
        for value in ("../escape", "a/b", "a:b", "", "a\\b"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                cpu.identifier(value)

    def test_tree_archive_preserves_full_resources_and_detects_duplicate_entries(self):
        archive = self.root / "tree.zip"
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("META-INF/BenchmarkList", "generated registry")
            output.writestr("fixture.txt", "arbitrary fixture resource")
        first = cpu.tree_hash(archive)
        with zipfile.ZipFile(archive, "a") as output:
            output.writestr("other.txt", "different bytes")
        self.assertNotEqual(first, cpu.tree_hash(archive))


if __name__ == "__main__":
    unittest.main()
