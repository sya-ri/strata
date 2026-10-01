"""Exercise JMH evidence validation using the actual packaged collector and JMH archives."""

import copy
import json
import os
import shutil
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from zipfile import ZipFile

COLLECTOR = Path(os.environ["STRATA_PERFORMANCE_TESTKIT_JAR"]).resolve()
HARNESS = Path(os.environ["STRATA_JMH_CORE_JAR"]).resolve()
sys.path.insert(0, str(COLLECTOR))
import strata_performance as kit


class JmhEvidenceTest(unittest.TestCase):
    def prepare(self, root, sample=False):
        directories = []
        representative = "dev.s7a.strata.performance.JvmPerformanceMeter"
        entry = representative.replace(".", "/") + ".class"
        with ZipFile(COLLECTOR) as archive:
            resource_hash = kit.sha256_bytes(archive.read(entry))
        tree = kit.class_tree(COLLECTOR)
        mode = "sample" if sample else "avgt"
        for repetition in range(3):
            directory = root / f"run-{repetition}"
            directory.mkdir()
            directories.append(directory)
            for name, source in (("collector.jar", COLLECTOR), ("target-0.jar", COLLECTOR), ("harness.jar", HARNESS)):
                shutil.copyfile(source, directory / name)
            metric = {"score": repetition + 1, "scoreUnit": "us/op", "rawData": [[repetition + 1]]}
            if sample:
                metric.pop("rawData")
                metric["rawDataHistogram"] = [[[[repetition + 1, 5]]]]
                metric["scorePercentiles"] = {"50.0": 1, "95.0": 2, "99.0": 3}
            row = {"jmhVersion": "1.37", "benchmark": "fixture.Frame.idle", "mode": mode, "params": {"component": "Row"},
                   "threads": 1, "forks": 1, "jvm": "java", "jvmArgs": [], "jdkVersion": "25", "vmName": "OpenJDK", "vmVersion": "25",
                   "warmupIterations": 3, "warmupTime": "1 s", "warmupBatchSize": 1,
                   "measurementIterations": 1, "measurementTime": "1 s", "measurementBatchSize": 1,
                   "primaryMetric": metric, "secondaryMetrics": {"gc.alloc.rate.norm": {"score": 16, "scoreUnit": "B/op"},
                                                                  "gc.count": {"score": 0, "scoreUnit": "counts"}}}
            content = json.dumps([row]).encode()
            (directory / "results.json").write_bytes(content)
            receipt = {"contract": "strata-jmh-v1", "status": "passed", "run_id": str(uuid.uuid4()), "repetition": repetition,
                       "results_sha256": kit.sha256_bytes(content), "harness_sha256": kit.digest(HARNESS),
                       "arguments": ["fixture.Frame.*", "-bm", mode], "fixture_identity": {"fixture.Frame": "1" * 64},
                       "environment": {"java": "25"}, "collector_identity": kit.collector_identity(COLLECTOR),
                       "registered_workloads": [json.dumps(["fixture.Frame.idle", mode, {"component": "Row"}])],
                       "target_archives": {"fixture": "target-0.jar"}, "runtime_metadata": {"modules": [
                           {"module": "fixture", "representativeClass": representative, "status": "resolved", "classTree": tree,
                            "codeSource": {"status": "available", "sha256": kit.digest(COLLECTOR)},
                            "classResource": {"status": "available", "entryName": entry, "sha256": resource_hash}}]}}
            (directory / "receipt.json").write_text(json.dumps(receipt), encoding="utf-8")
        return directories

    def test_real_archives_and_independent_means_preserve_unavailable_gc_time(self):
        with tempfile.TemporaryDirectory() as temporary:
            directories = self.prepare(Path(temporary))
            report = kit.summarize_jmh(directories, COLLECTOR)
            self.assertEqual(1, report["case_count"])
            self.assertEqual(2, report["cases"][0]["primary_score_median"])
            self.assertIsNone(report["cases"][0]["gc_time_ms_median_available"])
            self.assertNotIn("per_run_percentile_medians", report["cases"][0])

    def test_sample_histograms_keep_jmh_percentiles_and_reject_missing_iteration(self):
        with tempfile.TemporaryDirectory() as temporary:
            directories = self.prepare(Path(temporary), sample=True)
            report = kit.summarize_jmh(directories, COLLECTOR)
            self.assertEqual({"50.0": 1, "95.0": 2, "99.0": 3}, report["cases"][0]["per_run_percentile_medians"])
            path = directories[0] / "results.json"
            raw = json.loads(path.read_bytes())
            raw[0]["primaryMetric"]["rawDataHistogram"] = [[]]
            self.replace_raw(directories[0], raw)
            with self.assertRaises(kit.EvidenceError):
                kit.summarize_jmh(directories, COLLECTOR)

    def replace_raw(self, directory, raw):
        content = json.dumps(raw).encode()
        (directory / "results.json").write_bytes(content)
        path = directory / "receipt.json"
        receipt = json.loads(path.read_bytes())
        receipt["results_sha256"] = kit.sha256_bytes(content)
        path.write_text(json.dumps(receipt), encoding="utf-8")

    def test_incomplete_matrix_metrics_conditions_archives_and_duplicate_runs_fail(self):
        for mutation in ("matrix", "metric", "condition", "archive", "duplicate", "boolean_index", "raw_hash"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                directories = self.prepare(Path(temporary))
                path = directories[1] / "receipt.json"
                receipt = json.loads(path.read_bytes())
                raw = json.loads((directories[1] / "results.json").read_bytes())
                if mutation == "matrix":
                    raw.append(copy.deepcopy(raw[0]))
                    self.replace_raw(directories[1], raw)
                elif mutation == "metric":
                    del raw[0]["secondaryMetrics"]["gc.alloc.rate.norm"]
                    self.replace_raw(directories[1], raw)
                elif mutation == "condition":
                    receipt["environment"] = {"java": "changed"}
                elif mutation == "archive":
                    (directories[1] / "target-0.jar").write_bytes(b"replaced")
                elif mutation == "duplicate":
                    receipt["run_id"] = json.loads((directories[0] / "receipt.json").read_bytes())["run_id"]
                elif mutation == "boolean_index":
                    receipt["repetition"] = True
                else:
                    (directories[1] / "results.json").write_bytes(b"changed")
                if mutation not in ("matrix", "metric"):
                    path.write_text(json.dumps(receipt), encoding="utf-8")
                with self.assertRaises(kit.EvidenceError):
                    kit.summarize_jmh(directories, COLLECTOR)
