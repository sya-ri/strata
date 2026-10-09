"""Export a completed hosted run read-only and audit final receipts outside its measured lifetime.

This Linux observer uses the installed GitHub CLI and existing owned process accounting.
It never launches Java, allocates jobs, retries a campaign or publishes a qualification.
"""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import sys
import time

from cpu_host import run_process
import cpu_plan
from hosted_campaign import unpack
from hosted_role import Role


def record(text, key):
    """Read a top-level JSON receipt from complete service log lines, rejecting duplicate emissions."""
    result = []
    for line in text.splitlines():
        start = line.find("{")
        if start < 0:
            continue
        try:
            value = json.loads(line[start:], object_pairs_hook=cpu_plan.unique_object,
                               parse_constant=lambda token: cpu_plan.require(False, "Non-finite log receipt"))
        except (ValueError, TypeError):
            continue
        if key in value:
            result.append(value)
    cpu_plan.require(len(result) == 1, "Missing or duplicate durable " + key)
    return result[0]


def audit_job(job, text, role, campaign):
    """Check actual final owner release and later terminal transfer against this job's wider service wall."""
    cpu_plan.require(job["name"] == "CPU campaign " + role.value and job["status"] == "completed"
                     and job["conclusion"] == "success", "Incomplete or foreign service job")
    start = datetime.fromisoformat(job["started_at"].replace("Z", "+00:00"))
    end = datetime.fromisoformat(job["completed_at"].replace("Z", "+00:00"))
    cpu_plan.require(start <= end and job["steps"] and all(step["status"] == "completed" for step in job["steps"]),
                     "Missing complete service job/step bookends")
    node = record(text, "scope")
    cpu_plan.require(node["scope"] == "node-action-owner-only", "Foreign node owner scope")
    owner = record(text, "hosted_owner_terminal")["hosted_owner_terminal"]
    cpu_plan.require(owner["scope"] == "inclusive-campaign-python-sdk-jmh-owned-tree"
                     and owner["complete"] is True and owner["owned_release"] == "released"
                     and owner["cost"]["complete"] is True and owner["cost"]["cleanup_complete"] is True
                     and owner["cost"]["exit_code"] == 0 and owner["cost"]["cpu_seconds"] is not None,
                     "Missing actual complete owned-tree CPU/release")
    raw = owner["log_text"].encode()
    cpu_plan.require(len(raw) == owner["log_bytes"] and hashlib.sha256(raw).hexdigest() == owner["log_sha256"],
                     "Incomplete or changed original owner log")
    envelope = record(owner["log_text"], "terminal_upload_acknowledgement")
    final = envelope["terminal_upload_acknowledgement"]
    cpu_plan.require(envelope["campaign"] == campaign and envelope["role"] == role.value
                     and final["status"] == "passed" and final["operation"] == "upload", "Mixed final upload source")
    for late in envelope["late_terminal_phases"]:
        phase = late["phase"]
        cpu_plan.require(phase["status"] == "passed" and phase["cost"]["complete"] is True
                         and phase["cost"]["cleanup_complete"] is True and phase["cost"]["exit_code"] == 0
                         and hashlib.sha256(late["log_text"].encode()).hexdigest() == phase["log_sha256"],
                         "Missing complete final pack/upload cost/log")
    cpu_plan.require(node["child_code"] == 0 and node["signal"] is None
                     and int(node["elapsed_ns"]) <= int((end - start).total_seconds() * 1e9), "Incomplete node owner interval")
    return {"job": job, "owner": owner, "node": node, "final": envelope,
            "held_service_job_wall_seconds": (end - start).total_seconds(),
            "platform_cpu_seconds": None, "pure_queue_delay_seconds": None,
            "physical_host_release_time": None}


def main():
    """Fetch one completed run attempt and every exact terminal bundle; retain observer costs separately."""
    repository, run, attempt, commit, output = sys.argv[1:]
    cpu_plan.require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
                     and run.isdigit() and attempt.isdigit() and re.fullmatch(r"[a-f0-9]{40}", commit), "Invalid observer boundary")
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    started_ns, started_cpu = time.monotonic_ns(), time.process_time_ns()
    ledger = []

    def fetch(name, arguments):
        """Retain exact CLI output and complete actual observer process-tree cost without hidden retries."""
        path = output / name
        with path.open("xb") as log:
            cost = run_process(["gh", *arguments], output, log)
        record = {"command": ["gh", *arguments], "cost": cost, "source_sha256": cpu_plan.digest(path)}
        cpu_plan.write_new(output / (name + ".cost.json"), record)
        ledger.append(record)
        cpu_plan.require(cost.get("complete") is True and cost.get("cleanup_complete") is True
                         and cost["exit_code"] == 0, "Incomplete owned observer export")
        return path

    try:
        base = "repos/" + repository + "/actions/"
        run_source = fetch("run.json", ["api", base + "runs/" + run + "/attempts/" + attempt])
        actual = cpu_plan.document(run_source)
        cpu_plan.require(actual["id"] == int(run) and actual["run_attempt"] == int(attempt)
                         and actual["head_sha"] == commit and actual["status"] == "completed",
                         "Run is still active or belongs to another attempt/source")
        jobs_source = fetch("jobs.json", ["api", base + "runs/" + run + "/attempts/" + attempt + "/jobs?per_page=100"])
        jobs = cpu_plan.document(jobs_source)
        cpu_plan.require(jobs["total_count"] == 5 and len(jobs["jobs"]) == 5
                         and {job["name"] for job in jobs["jobs"]} == {"CPU campaign " + role.value for role in Role},
                         "Missing, duplicate or extra campaign jobs")
        campaign = "cpu-" + run + "-" + attempt + "-" + commit[:12]
        audits = {}
        for role in Role:
            job = next(job for job in jobs["jobs"] if job["name"] == "CPU campaign " + role.value)
            cpu_plan.require(job["run_id"] == int(run) and job["head_sha"] == commit, "Mixed actual job metadata")
            logs = fetch(role.value + ".log", ["api", base + "jobs/" + str(job["id"]) + "/logs"])
            audits[role.value] = audit_job(job, logs.read_text(), role, campaign)
            sdk_bundle = output / (role.value + "-export")
            name = campaign + "_terminal_" + role.value + "_0"
            fetch(role.value + "-export.log", ["run", "download", run, "--repo", repository, "--name", name, "--dir", str(sdk_bundle)])
            restored = output / role.value
            unpack(sdk_bundle, restored, {"repository": repository, "run_id": run, "run_attempt": attempt,
                                         "commit": commit, "campaign": campaign, "kind": "terminal", "role": role.value, "sequence": 0})
            terminal = cpu_plan.document(restored / "terminal.json")
            cpu_plan.require(terminal["status"] == "passed" and terminal["campaign"] == campaign
                             and terminal["role"] == role.value, "Failed or mixed exported terminal")
            for late in audits[role.value]["final"]["late_terminal_phases"]:
                for path, expected in late["phase"]["artifacts"].items():
                    if Path(path).name in ("bundle.json", "archive.json", "bundle.zip"):
                        exported = sdk_bundle / Path(path).name
                        cpu_plan.require(cpu_plan.digest(exported) == expected["sha256"]
                                         and exported.stat().st_size == expected["bytes"], "Changed final packed/exported bytes")
        artifacts = []
        page = 1
        while True:
            source = fetch("artifacts-" + str(page) + ".json", ["api", base + "runs/" + run + "/artifacts?per_page=100&page=" + str(page)])
            values = cpu_plan.document(source)["artifacts"]
            artifacts.extend(values)
            if len(values) < 100:
                break
            page += 1
        for role in Role:
            acknowledgement = audits[role.value]["final"]["terminal_upload_acknowledgement"]
            name = campaign + "_terminal_" + role.value + "_0"
            matches = [artifact for artifact in artifacts if artifact["name"] == name]
            cpu_plan.require(len(matches) == 1 and matches[0]["expired"] is False
                             and matches[0]["id"] == int(acknowledgement["outputs"]["artifact-id"])
                             and matches[0]["digest"] == "sha256:" + acknowledgement["outputs"]["artifact-digest"],
                             "Missing, duplicate or mixed final actual artifact acknowledgement")
        comparison = cpu_plan.document(output / Role.COORDINATOR.value / "campaign-comparisons.json")
        cpu_plan.require(len(comparison["comparisons"]) == 3 and all(value["status"] == "passed" for value in comparison["comparisons"]),
                         "Incomplete actual three wall pairs")
        cpu_plan.document(output / Role.COORDINATOR.value / "all-cost-audit.json")
        cpu_plan.write_new(output / "final-hosted-audit.json", {"campaign": campaign, "jobs": audits,
                            "owned_hosted_cpu_seconds": sum(value["owner"]["cost"]["cpu_seconds"] for value in audits.values())
                            + sum(sum(value["node"]["cpu_microseconds"].values()) / 1e6 for value in audits.values()),
                            "cpu_scope": "Inclusive Python owned tree plus disjoint node owner; phase/JMH CPU is attribution, never added again",
                            "actual_owned_release_complete": True, "platform_cpu_seconds": None,
                            "all_cost_acceptance": False, "runtime_or_native_acceptance": False,
                            "observer_export_is_outside_hosted_campaign_lifetime": True})
    finally:
        cpu_plan.write_new(output / "observer-cost.json", {"ledger": ledger,
                            "controller_only_cpu_ns": time.process_time_ns() - started_cpu,
                            "elapsed_ns": time.monotonic_ns() - started_ns,
                            "scope": "Later read-only export and validation; excluded from original hosted wall and CPU"})


if __name__ == "__main__":
    main()
