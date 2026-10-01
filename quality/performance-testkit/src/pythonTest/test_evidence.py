"""Exercise the comparison resource from the actual testkit archive."""

import os
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile, ZipInfo

COLLECTOR = Path(os.environ["STRATA_PERFORMANCE_TESTKIT_JAR"]).resolve()
sys.path.insert(0, str(COLLECTOR))
import strata_performance as kit


class EvidenceTest(unittest.TestCase):
    def test_packaged_collector_and_independent_runs(self):
        self.assertTrue(kit.__file__.startswith(str(COLLECTOR)))
        identity = {"contract": "strata-performance-testkit-v1", "code_source_sha256": kit.digest(COLLECTOR)}
        reports = [{"status": "passed", "run_id": str(index), "collector_identity": identity} for index in range(3)]
        self.assertEqual(identity, kit.verify_collectors(reports, COLLECTOR))
        kit.verify_repetitions(reports)
        with self.assertRaises(kit.EvidenceError):
            kit.verify_repetitions([reports[0]] * 3)
        for bad in ({}, {"contract": identity["contract"], "code_source_sha256": "0" * 64}):
            with self.assertRaises(kit.EvidenceError):
                kit.verify_collectors(reports + [{"collector_identity": bad}], COLLECTOR)
        with self.assertRaises(kit.EvidenceError):
            kit.verify_collectors([], COLLECTOR)
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / "changed.jar"
            changed.write_bytes(COLLECTOR.read_bytes() + b"changed")
            with self.assertRaises(kit.EvidenceError):
                kit.collector_identity(changed)

    def test_missing_conditions_metrics_and_duplicate_phases(self):
        with self.assertRaises(kit.EvidenceError):
            kit.verify_equal([{}, {}], ("java",))
        with self.assertRaises(kit.EvidenceError):
            kit.verify_equal([{"java": "a"}, {"java": "b"}], ("java",))
        kit.verify_equal([{"java": "a"}] * 3, ("java",))
        for values in ([], [None], [float("nan")], [float("inf")], [-1], [True]):
            with self.assertRaises(kit.EvidenceError):
                kit.median(values)
        self.assertEqual(20, kit.median([10, 30, 20]))
        with self.assertRaises(kit.EvidenceError):
            kit.index_phases({"phases": [{"case": "a"}] * 2}, ("case",))
        with self.assertRaises(kit.EvidenceError):
            kit.index_phases({"phases": [{"case": "a"}]}, ("case",), 2)

    def test_archive_inventory_rejects_unsafe_and_duplicate_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime.jar"
            with ZipFile(path, "w") as archive:
                archive.writestr("fixture/Probe.class", b"compiled probe")
                archive.writestr("fixture/Other.class", b"compiled other")
            tree = kit.class_tree(path)
            self.assertEqual(2, tree["entryCount"])
            self.assertEqual(28, tree["bytes"])
            for invalid in ("../Probe.class", "/Probe.class", "fixture//Probe.class", "fixture\\Probe.class"):
                with ZipFile(path, "w") as archive:
                    entry = ZipInfo("fixture/Probe.class")
                    entry.filename = invalid
                    archive.writestr(entry, b"probe")
                with self.assertRaises(kit.EvidenceError):
                    kit.class_tree(path)
            with ZipFile(path, "w") as archive:
                archive.writestr("fixture/Probe.class", b"one")
                archive.writestr("fixture/Probe.class", b"two")
            with self.assertRaises(kit.EvidenceError):
                kit.class_tree(path)

    def test_native_class_tree_mismatch_is_not_accepted(self):
        import hashlib

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime.jar"
            with ZipFile(path, "w") as archive:
                archive.writestr("fixture/Probe.class", b"probe")
            origin = {"url": path.as_uri(), "sha256": kit.digest(path)}
            resource = {"entryName": "fixture/Probe.class", "sha256": hashlib.sha256(b"probe").hexdigest()}
            module = {"module": "runtime", "representativeClass": "fixture.Probe", "status": "resolved",
                      "codeSource": origin, "classResource": resource, "classTree": kit.class_tree(path)}
            native = {"strata": {"modules": [module]}}
            cpu = {"strata_class_sha256": {"fixture.Probe": {"code_source": origin, "class_resource": resource}}}
            self.assertEqual(1, len(kit.verify_native_binary(native, cpu, {"fixture.Probe"}, set())))
            module["classTree"]["sha256"] = "0" * 64
            with self.assertRaises(kit.EvidenceError):
                kit.verify_native_binary(native, cpu, {"fixture.Probe"}, set())
