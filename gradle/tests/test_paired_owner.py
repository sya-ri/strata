"""Verify legacy owner source plans and original argument bindings without Java or timing."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("paired_owner", Path(__file__).resolve().parents[1] / "performance/paired-jvm.py")
PAIRED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAIRED)


class LegacyOwnerPairTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)
        self.specification = {
            "project": ":quality:benchmarks",
            "paired_jmh": {"suite": "complete-two", "fixture_class": "dev.example.FixtureBenchmark"},
            "runtime_classes": {"api": {"project": ":api", "class": "dev.example.Api"}},
        }
        self.classes = self.root / "quality/benchmarks/build/classes/kotlin/jmh"
        self.resource = self.classes / "dev/example/FixtureBenchmark.class"
        self.resource.parent.mkdir(parents=True)
        self.resource.write_bytes(b"compiled fixture")
        self.sources = {side: {"ref": "a" * 40, "tree": tree * 40, "patches": []}
                        for side, tree in [("fixture", "b"), ("baseline", "c"), ("candidate", "b")]}
        self.runtime = {side: {":api": {"sha256": digest * 64}}
                        for side, digest in [("baseline", "1"), ("candidate", "2")]}

    def test_selected_application_hash_matches_existing_entry_identity(self):
        expected = hashlib.sha256()
        name = b"dev/example/FixtureBenchmark.class"
        expected.update(str(len(name)).encode("ascii") + b":" + name + bytes((0,)))
        expected.update(b"compiled fixture")
        expected.update(bytes((255,)))
        self.assertEqual(PAIRED.compiled_application_tree(self.classes), expected.hexdigest())
        (self.classes / "ignored.kotlin_module").write_bytes(b"ignored metadata")
        self.assertEqual(PAIRED.compiled_application_tree(self.classes), expected.hexdigest())
        self.resource.write_bytes(b"substituted fixture")
        self.assertNotEqual(PAIRED.compiled_application_tree(self.classes), expected.hexdigest())

    def test_actual_fixture_and_both_source_archive_bindings(self):
        plan = PAIRED.paired_owner_source_plan(self.root, self.specification, self.sources, self.runtime)
        self.assertEqual(plan["fixture_source_binding"], self.sources["fixture"])
        self.assertEqual(plan["sources"][0]["source_binding"], self.sources["baseline"])
        self.assertEqual(plan["sources"][1]["archives"], {"api": "2" * 64})
        duplicate = copy.deepcopy(self.runtime)
        duplicate["candidate"] = duplicate["baseline"]
        with self.assertRaises(ValueError):
            PAIRED.paired_owner_source_plan(self.root, self.specification, self.sources, duplicate)
        wrong = copy.deepcopy(self.specification)
        wrong["paired_jmh"]["fixture_class"] = "dev.example.Substituted"
        with self.assertRaises(ValueError):
            PAIRED.paired_owner_source_plan(self.root, wrong, self.sources, self.runtime)
        second = self.root / "quality/benchmarks/build/classes/java/jmh/dev/example/FixtureBenchmark.class"
        second.parent.mkdir(parents=True)
        second.write_bytes(b"shadowed fixture")
        with self.assertRaises(ValueError):
            PAIRED.paired_owner_source_plan(self.root, self.specification, self.sources, self.runtime)

    def test_four_argument_binding_preserves_real_receipt_and_plan(self):
        directory = self.root / "raw/run-1"
        directory.mkdir(parents=True)
        libraries = self.root / "quality/benchmarks/build/performance/control-inputs.properties"
        libraries.parent.mkdir(parents=True)
        libraries.write_text("library=fixed.jar\n", encoding="utf-8")
        plan_path = self.root / "immutable-source-plan.json"
        plan_path.write_text('{"prepared": true}\n', encoding="utf-8")
        plan = {"path": str(plan_path), "sha256": PAIRED.digest(plan_path)}
        rows = [{"jvmArgs": ["-Xmx768m", "-Dstrata.performance.inputs=" + str(libraries)]} for _ in range(2)]
        results = directory / "results.json"
        results.write_text(json.dumps(rows), encoding="utf-8")
        receipt = directory / "receipt.json"
        receipt.write_text(json.dumps({"status": "passed", "repetition": 1, "results_sha256": PAIRED.digest(results)}), encoding="utf-8")
        extra, actual_libraries = PAIRED.paired_owner_arguments(directory, 1, plan, self.root, 2)
        self.assertEqual(extra, [str(receipt), str(plan_path)])
        self.assertEqual(actual_libraries, libraries)
        with self.assertRaises(ValueError):
            PAIRED.paired_owner_arguments(directory, 0, plan, self.root, 2)
        with self.assertRaises(ValueError):
            PAIRED.paired_owner_arguments(directory, 1, plan, self.root, 3)
        plan_path.write_text('{"changed": true}\n', encoding="utf-8")
        with self.assertRaises(ValueError):
            PAIRED.paired_owner_arguments(directory, 1, plan, self.root, 2)

    def test_missing_or_mixed_actual_jmh_library_origin_is_rejected(self):
        directory = self.root / "raw"
        directory.mkdir()
        plan_path = self.root / "plan.json"
        plan_path.write_text("{}", encoding="utf-8")
        plan = {"path": str(plan_path), "sha256": PAIRED.digest(plan_path)}
        libraries = self.root / "libraries.properties"
        libraries.write_text("fixed", encoding="utf-8")
        for arguments in [
            [[], []],
            [["-Dstrata.performance.inputs=" + str(libraries)], []],
            [["-Dstrata.performance.inputs=" + str(libraries)]] * 1,
            [["-Dstrata.performance.inputs=" + str(libraries), "-Dstrata.performance.inputs=" + str(libraries)]] * 2,
        ]:
            with self.subTest(arguments=arguments):
                results = directory / "results.json"
                results.write_text(json.dumps([{"jvmArgs": values} for values in arguments]), encoding="utf-8")
                (directory / "receipt.json").write_text(json.dumps({"status": "passed", "repetition": 0,
                    "results_sha256": PAIRED.digest(results)}), encoding="utf-8")
                with self.assertRaises(ValueError):
                    PAIRED.paired_owner_arguments(directory, 0, plan, self.root, 2)


if __name__ == "__main__":
    unittest.main()
