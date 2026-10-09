"""Bounded process-accounting verification; no Java, benchmark, or host qualification."""
import math
import os
from pathlib import Path
import sys
import tempfile
import unittest
import subprocess

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cpu_host import run_process


class CpuHostTest(unittest.TestCase):
    """Verify that the actual OS accounting owns a root and one inherited Python child."""

    def test_process_tree_cost_preserves_child_output_and_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            command = [sys.executable, "-c", "import subprocess,sys;subprocess.run([sys.executable,'-c','print(42)'],check=True)"]
            with (root / "process.log").open("xb") as log:
                cost = run_process(command, root, log)
            self.assertEqual(0, cost["exit_code"])
            self.assertEqual("42", (root / "process.log").read_text().strip())
            self.assertTrue(math.isfinite(cost["cpu_seconds"]) and 0 <= cost["cpu_seconds"])
            self.assertTrue(math.isfinite(cost["elapsed_seconds"]) and 0 <= cost["elapsed_seconds"])
            if os.name == "nt":
                self.assertEqual("QueryInformationJobObject(JobObjectBasicAccountingInformation)", cost["source"])
                self.assertLessEqual(2, cost["total_processes"])

    def test_unjoined_child_is_rejected_and_owned_tree_is_cleaned(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            child = "import time;time.sleep(10)"
            command = [sys.executable, "-c", "import subprocess,sys;subprocess.Popen([sys.executable,'-c',sys.argv[1]])", child]
            with (root / "process.log").open("xb") as log:
                if os.name == "nt":
                    with self.assertRaisesRegex(ValueError, "live children"):
                        run_process(command, root, log)
                else:
                    cost = run_process(command, root, log)
                    self.assertNotEqual(0, cost["exit_code"])
                    self.assertFalse(cost["complete"])
                    self.assertTrue(cost["cleanup_complete"])

    @unittest.skipUnless(sys.platform == "linux", "Linux descendant ownership requires its actual kernel APIs")
    def test_escaped_session_is_cleaned_without_touching_unrelated_child(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            unrelated = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(10)"])
            try:
                ready = root / "escaped-ready.txt"
                child = "import os,pathlib,sys,time;os.setsid();pathlib.Path(sys.argv[1]).write_text(str(os.getsid(0)));time.sleep(10)"
                launcher = "import pathlib,subprocess,sys,time;subprocess.Popen([sys.executable,'-c',sys.argv[1],sys.argv[2]]);limit=time.monotonic()+2;\nwhile not pathlib.Path(sys.argv[2]).exists() and time.monotonic()<limit: time.sleep(0.01)\nassert pathlib.Path(sys.argv[2]).exists()"
                command = [sys.executable, "-c", launcher, child, str(ready)]
                with (root / "process.log").open("xb") as log:
                    cost = run_process(command, root, log)
                self.assertNotEqual(0, cost["exit_code"])
                self.assertTrue(cost["cleanup_complete"])
                self.assertNotEqual(cost["root_process_id"], int(ready.read_text()))
                self.assertIsNone(unrelated.poll())
            finally:
                unrelated.terminate()
                unrelated.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
