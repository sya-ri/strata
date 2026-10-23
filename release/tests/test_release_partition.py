"""Reject incomplete, stale and altered distributed release verification without service writes."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
import partition
import verification as v


class PartitionTest(unittest.TestCase):
    def setUp(self):
        self.identity = dict(schemaVersion=1, scope="rehearsal", sourceCommit="a" * 40, controllerCommit="b" * 40,
                             tagObject="", runId=123, runAttempt=1)
        paths = [":check", ":api:check", ":new-module:check", ":koverHtmlReport", ":koverXmlReport",
                 ":runtime:minecraft-fabric-1.20:check", ":integration:minecraft-fabric-1.20:check",
                 ":integration:minecraft-fabric-1.20:runClientGameTest",
                 ":integration:minecraft-fabric-1.20:runProductionClientGameTest", *partition.WEB_CHECKS]
        self.tasks = [dict(root=".", build="strata", path=path) for path in paths]
        self.plan = dict(self.identity, **partition.make_plan(self.tasks, ["1.20"]))

    def fixture(self, root):
        """Write successful distinct owners; shared prerequisites may appear in several shards."""
        v.write_json(root / "plan/plan.json", self.plan)
        for shard in self.plan["shards"]:
            tasks = [t for t in self.tasks if (t["path"].startswith(":integration:minecraft-fabric-")
                                             or t["path"].startswith(":runtime:minecraft-fabric-")) == (shard["kind"] == "minecraft")
                     and (t["path"] in partition.WEB_CHECKS) == (shard["kind"] == "web")]
            directory = root / "shards" / shard["id"]
            v.write_json(directory / "shard.json", dict(self.identity, shard=shard,
                         planDigest=v.digest(root / "plan/plan.json"), tasks=tasks))
            for phase in partition.phases(shard):
                v.write_json(directory / f"{phase}.json", dict(self.identity, command=partition.command(shard), exitCode=0))

    def test_new_checks_are_owned_without_a_current_module_allowlist(self):
        common, web, native = self.plan["shards"]
        self.assertIn(":new-module:check", common["arguments"])
        self.assertNotIn(":integration:minecraft-fabric-1.20:check", common["arguments"])
        self.assertIn("-Pkover", common["arguments"])
        self.assertIn(":api:jsTest", web["arguments"])
        self.assertIn("-Pstrata.minecraftVersions=1.20", native["arguments"])

    def test_missing_native_owner_is_rejected_during_planning(self):
        with self.assertRaises(ValueError):
            partition.make_plan(self.tasks, ["1.20", "26.3"])

    def test_successful_shards_from_an_earlier_same_run_attempt_remain_attributable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.fixture(root)
            actual = partition.validate_shards(root, dict(self.identity, runAttempt=2))
            self.assertEqual(partition.keys(self.tasks), partition.keys(actual))
            parity = json.loads((root / "parity.json").read_text())
            self.assertEqual([1, 1, 1], [p["runAttempt"] for p in parity["producers"]])

    def test_missing_shard_cannot_produce_an_aggregate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.fixture(root)
            (root / "shards/web/shard.json").unlink()
            with self.assertRaises(FileNotFoundError):
                partition.validate_shards(root, self.identity)

    def test_failed_cancelled_wrong_revision_or_altered_shard_cannot_pass(self):
        for field, value in (("exitCode", 7), ("exitCode", None), ("sourceCommit", "c" * 40),
                             ("controllerCommit", "c" * 40), ("runId", 124), ("runAttempt", 2), ("command", ["help"])):
            with self.subTest(field=field, value=value), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.fixture(root)
                path = root / "shards/web/check.json"
                record = json.loads(path.read_text())
                record[field] = value
                v.write_json(path, record)
                with self.assertRaises(ValueError):
                    partition.validate_shards(root, self.identity)

    def test_transitively_required_production_client_cannot_be_omitted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.fixture(root)
            path = root / "shards/minecraft-1-20/shard.json"
            record = json.loads(path.read_text())
            record["tasks"] = [t for t in record["tasks"] if not t["path"].endswith(":runProductionClientGameTest")]
            v.write_json(path, record)
            with self.assertRaisesRegex(ValueError, "omitted 1"):
                partition.validate_shards(root, self.identity)
            self.assertIn("runProductionClientGameTest", (root / "parity.json").read_text())

    def test_a_shard_cannot_substitute_a_different_plan_or_future_producer(self):
        for change in (dict(planDigest="d" * 64), dict(runAttempt=0), dict(runAttempt=2), dict(scope="production")):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.fixture(root)
                path = root / "shards/web/shard.json"
                record = json.loads(path.read_text())
                record.update(change)
                v.write_json(path, record)
                with self.assertRaises(ValueError):
                    partition.validate_shards(root, self.identity)


if __name__ == "__main__":
    unittest.main()
