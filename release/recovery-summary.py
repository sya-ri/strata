#!/usr/bin/env python3
"""Report strict release outcomes, measured timings and validated destination-only recovery."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from datetime import datetime

import verification

spec = importlib.util.spec_from_file_location("prepared", Path(__file__).with_name("prepared-release.py"))
prepared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepared)

DESTINATIONS = {"maven_central": "Maven Central", "github_release": "Publish github", "modrinth": "Publish modrinth",
                "curseforge": "Publish curseforge", "hangar": "Publish hangar"}


def expected_results(inputs):
    """Permit skips only for deliberately unselected work or validated preparation reuse."""
    first = inputs["operation"] == "release" and not inputs.get("prepared_run_id")
    return dict(source="success", analysis="success" if first else "skipped",
                verification="success" if first else "skipped", prepare="success" if first else "skipped",
                central="success" if inputs["maven_central"] else "skipped",
                publish="success" if any(inputs[k] for k in DESTINATIONS if k != "maven_central") else "skipped")


def seconds(start, end):
    """Compute timestamp intervals without calling rerun start time queue time."""
    return (datetime.fromisoformat(end.replace("Z", "+00:00")) - datetime.fromisoformat(start.replace("Z", "+00:00"))).total_seconds()


def timings(run, jobs):
    """Retain run/attempt identities, all job and step durations and OS runner consumption."""
    minutes = {}
    records = []
    for job in {job["id"]: job for job in jobs}.values():
        labels = job.get("labels", [])
        platform = next((os_name for os_name in ("ubuntu", "windows", "macos") if any(os_name in label.lower() for label in labels)), "unknown")
        duration = seconds(job["started_at"], job["completed_at"]) if job.get("started_at") and job.get("completed_at") else None
        if duration is not None:
            minutes[platform] = minutes.get(platform, 0) + duration / 60
        records.append(dict(id=job["id"], name=job["name"], attempt=job.get("run_attempt"), result=job["conclusion"],
                            startedAt=job.get("started_at"), completedAt=job.get("completed_at"), seconds=duration,
                            steps=[dict(name=s["name"], result=s["conclusion"], startedAt=s.get("started_at"), completedAt=s.get("completed_at"))
                                   for s in job.get("steps", [])]))
    starts = [j["started_at"] for j in jobs if j.get("started_at")]
    ready = [j["completed_at"] for j in jobs if j["name"].endswith(("Prepare release", "Prepare rehearsal"))
             and j.get("completed_at") and j.get("conclusion") == "success"]
    return dict(createdAt=run["created_at"], updatedAt=run["updated_at"],
                dispatchToFirstJobSeconds=seconds(run["created_at"], min(starts)) if starts else None,
                observedElapsedSeconds=seconds(run["created_at"], run["updated_at"]),
                preparationToReadySeconds=seconds(run["created_at"], min(ready)) if ready else None,
                aggregateRunnerMinutesByOS=minutes, jobs=records,
                unavailable=["runner dependency-ready timestamp", "peak RAM/CPU/GPU", "separate Pages/public-visibility milestone"],
                note="In-progress result job and post-summary time are excluded. Timestamp gaps do not establish queue causes.")


def retry_inputs(inputs, manifest, jobs):
    """Offer only failed selected destinations after successful immutable preparation validation."""
    if manifest is None or inputs["operation"] != "release":
        return None
    latest = {}
    for job in jobs:
        previous = latest.get(job["name"])
        if previous is None or previous["id"] < job["id"]:
            latest[job["name"]] = job
    selected = {key: bool(inputs[key]) and latest.get(name, {}).get("conclusion") != "success" for key, name in DESTINATIONS.items()}
    if not any(selected.values()):
        return None
    return dict(operation="release", tag=inputs["tag"], source_commit=inputs["source_commit"],
                prepared_run_id=str(manifest["runId"]), confirmation="release " + inputs["tag"], **selected)


def main():
    """Write recovery evidence even on failure, then fail for any unexpected skip or cancellation."""
    inputs, results = json.loads(os.environ["RELEASE_INPUTS"]), json.loads(os.environ["RESULTS"])
    repo, run_id = os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_RUN_ID"]
    run = prepared.github(f"repos/{repo}/actions/runs/{run_id}")
    pages = json.loads(subprocess.check_output(["gh", "api", "--paginate", "--slurp",
                                              f"repos/{repo}/actions/runs/{run_id}/jobs?filter=all&per_page=100"]))
    jobs = [job for page in pages for job in page["jobs"]]
    manifest, digest, restore_error = None, None, None
    reusable = results["source"]["result"] == "success" and (results["prepare"]["result"] == "success" or inputs.get("prepared_run_id"))
    if reusable:
        try:
            manifest = prepared.restore(int(os.environ["PREPARED_RUN_ID"]), inputs["tag"], inputs["source_commit"],
                                        Path("build/recovery-prepared"), os.environ["TAG_OBJECT"])
            digest = prepared.sha256(Path("build/release-prepared.tar.gz"))
            expected_digest = os.environ.get("ARCHIVE_DIGEST")
            verification.require(not expected_digest or expected_digest == digest, "Prepared archive output digest differs.")
        except (ValueError, OSError, subprocess.SubprocessError) as error:
            restore_error = str(error)
    expected = expected_results(inputs)
    errors = [f"{key}: expected {value}, got {results.get(key, {}).get('result', 'missing')}"
              for key, value in expected.items() if results.get(key, {}).get("result") != value]
    failures = [dict(job=j["name"], step=next((s["name"] for s in j.get("steps", []) if s["conclusion"] == "failure"), None),
                     result=j["conclusion"], url=j.get("html_url")) for j in jobs if j["conclusion"] in ("failure", "cancelled")]
    failure_outputs = results.get("verification", {}).get("outputs", {})
    classification = failure_outputs.get("failure_classification") or "unclassified failure; inspect retained cause chain"
    if classification == "success" and results.get("verification", {}).get("result") != "success":
        classification = "evidence collection or artifact failure after product checks; inspect job steps"
    recovery = retry_inputs(inputs, manifest, jobs)
    report = dict(schemaVersion=1, sourceCommit=inputs["source_commit"], tag=inputs["tag"],
                  tagObject=os.environ.get("TAG_OBJECT"), controllerCommit=os.environ["GITHUB_SHA"],
                  runId=int(run_id), runAttempt=int(os.environ["GITHUB_RUN_ATTEMPT"]),
                  preparation=manifest and {k: manifest[k] for k in ("runId", "runAttempt", "controllerCommit")},
                  archiveDigest=digest, failures=failures, classification=classification,
                  primaryPhase=failure_outputs.get("failure_phase"), retryInputs=recovery, errors=errors,
                  restoreError=restore_error, timings=timings(run, jobs))
    verification.write_json(Path("build/release/recovery.json"), report)
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write(f"## Release verification and recovery\n\nProduct `{inputs['source_commit']}`, controller `{os.environ['GITHUB_SHA']}`.\n\n")
        summary.write("| Job | Result | Required |\n| --- | --- | --- |\n")
        for key, required in expected.items():
            summary.write(f"| {key} | {results.get(key, {}).get('result', 'missing')} | {required} |\n")
        for failure in failures:
            summary.write(f"\n{failure['result']}: {failure['job']} / {failure['step']} ({failure['url']}).\n")
        if errors:
            summary.write(f"\nVerification classification: {classification}.\n")
        summary.write("\nInspect the retained phase classification and cause chain. Retry only identified transient failures; compiler, assertion, analysis and integrity failures require a fix.\n")
        if manifest:
            summary.write(f"\nReusable preparation: run `{manifest['runId']}`, attempt `{manifest['runAttempt']}`, SHA-256 `{digest}`.\n")
        else:
            summary.write("\nNo reusable preparation was validated. Failed preparation must not be selected for destination-only recovery.\n")
        if recovery:
            summary.write("\nController fixes require a new dispatch; failed-job reruns retain the original controller. Exact destination-only retry inputs:\n\n```json\n" + json.dumps(recovery, indent=2) + "\n```\n")
        if restore_error:
            summary.write("\nPreparation recovery validation failed: " + verification.redact(restore_error) + "\n")
        summary.write("\nMeasured timings and unavailable metrics: `recovery.json`. Pages and public visibility are separate milestones.\n")
    sys.exit(1 if errors or restore_error else 0)


if __name__ == "__main__":
    main()
