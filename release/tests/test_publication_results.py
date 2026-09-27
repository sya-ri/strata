"""Ensure retries retain successful destinations and replace stale failed receipts."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("results", Path(__file__).parents[1] / "collect-publication-results.py")
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)


class PublicationResultsTest(unittest.TestCase):
    def test_partial_retry_selects_latest_attempt_per_destination_regardless_of_api_order(self):
        artifacts = [{"name": name} for name in ("result-central-1", "result-hangar-2", "result-hangar-1", "prepared-v1.2.3-1")]
        for ordered in (artifacts, list(reversed(artifacts))):
            self.assertEqual({"result-central-1", "result-hangar-2"}, set(results.select(ordered)))


if __name__ == "__main__":
    unittest.main()
