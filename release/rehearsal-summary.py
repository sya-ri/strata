#!/usr/bin/env python3
"""Record non-publishing preparation measurements without claiming live publication savings."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

import verification

spec = importlib.util.spec_from_file_location("recovery", Path(__file__).with_name("recovery-summary.py"))
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


def check_results(inputs, results):
    """Require exactly the chosen topology, both analysis owners and signed preparation."""
    selected = inputs["topology"]
    verification.require(selected in ("serial", "overlap"), "Unknown rehearsal topology.")
    expected = dict(source="success", analysis="success", prepare="success", serial="skipped", overlap="skipped")
    expected[selected] = "success"
    return [f"{key}: expected {value}, got {results.get(key, {}).get('result', 'missing')}"
            for key, value in expected.items() if results.get(key, {}).get("result") != value]


def main():
    """Retain timing evidence after every completed rehearsal, including intentionally failed work."""
    inputs, results = json.loads(os.environ["RELEASE_INPUTS"]), json.loads(os.environ["RESULTS"])
    repository, run_id = os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_RUN_ID"]
    run = recovery.prepared.github(f"repos/{repository}/actions/runs/{run_id}")
    pages = json.loads(subprocess.check_output(["gh", "api", "--paginate", "--slurp",
                                              f"repos/{repository}/actions/runs/{run_id}/jobs?filter=all&per_page=100"]))
    jobs = [job for page in pages for job in page["jobs"]]
    errors = check_results(inputs, results)
    report = dict(schemaVersion=1, scope="rehearsal", sourceCommit=inputs["source_commit"],
                  controllerCommit=os.environ["GITHUB_SHA"], topology=inputs["topology"],
                  runId=int(run_id), runAttempt=int(os.environ["GITHUB_RUN_ATTEMPT"]), errors=errors,
                  timings=recovery.timings(run, jobs))
    verification.write_json(Path("build/rehearsal-timings.json"), report)
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write(f"## Non-publishing {inputs['topology']} rehearsal\n\nProduct `{inputs['source_commit']}`, controller `{os.environ['GITHUB_SHA']}`.\n\n")
        summary.write("Disposable-key signing and complete product verification are required. This run cannot be promoted to production.\n")
        summary.write("\nCompare preparation-to-ready time for matched product, task inventories, toolchains, runner classes and cache conditions. Live publication and Pages are excluded.\n")
        for error in errors:
            summary.write(f"\n{error}\n")
        summary.write("\nJob/step timestamps and aggregate runner-minutes by OS are retained in `rehearsal-timings.json`; unavailable metrics are explicit.\n")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
