#!/usr/bin/env python3
"""Recover upload receipts from trusted workflow attempts, never from a cache."""

import argparse
import hashlib
import io
import json
import os
import subprocess
from pathlib import Path
from zipfile import ZipFile


def github(path, *, binary=False):
    """Use the runner token through gh without exposing headers or error bodies."""
    result = subprocess.run(["gh", "api", path], capture_output=True, check=False)
    if result.returncode:
        raise ValueError("GitHub receipt lookup failed; upload history could not be proved.")
    return result.stdout if binary else json.loads(result.stdout)


def pages(path, key):
    """Read a bounded complete GitHub inventory, rejecting changing counts."""
    entries, total = [], None
    for page_number in range(1, 101):
        separator = "&" if "?" in path else "?"
        page = github(f"{path}{separator}per_page=100&page={page_number}")
        count, items = page["total_count"], page[key]
        if total is None:
            total = count
        if type(total) is not int or total < 0 or count != total or not isinstance(items, list):
            raise ValueError("GitHub receipt inventory changed or is malformed.")
        entries.extend(items)
        if len(entries) == total:
            identifiers = [entry.get("id") for entry in entries]
            if any(type(identifier) is not int or identifier <= 0 for identifier in identifiers) or len(set(identifiers)) != len(identifiers):
                raise ValueError("GitHub receipt inventory contains invalid or duplicate IDs.")
            return entries
        if not items or total < len(entries):
            break
    raise ValueError("GitHub receipt inventory is incomplete.")


def restore(repository, tag, commit, run_id, attempt, destination):
    """An unrecorded previous write permits verification, but never another upload."""
    if repository != "sya-ri/strata":
        raise ValueError("Unexpected release repository.")
    prefix = f"repos/{repository}"
    title = f"Publish release {tag} ({commit}) - CurseForge true"
    runs = pages(f"{prefix}/actions/workflows/publish-release.yml/runs?event=workflow_dispatch", "workflow_runs")
    attempts = []
    for run in runs:
        if run.get("display_title") != title:
            continue
        if run.get("head_branch") != "master" or run.get("event") != "workflow_dispatch":
            raise ValueError("Receipt producer is not the protected master workflow.")
        limit = attempt - 1 if run["id"] == run_id else run["run_attempt"]
        attempts.extend((run["id"], number, run["head_sha"]) for number in range(1, limit + 1))
    if not any(item[0] == run_id for item in attempts):
        attempts.extend((run_id, number, os.environ["GITHUB_SHA"]) for number in range(1, attempt))
    receipts, may_upload = [], True
    for previous_id, previous_attempt, controller in sorted(attempts):
        jobs = pages(f"{prefix}/actions/runs/{previous_id}/attempts/{previous_attempt}/jobs", "jobs")
        stages = [step for job in jobs for step in job.get("steps", [])
                  if step.get("name") == "Stage only missing CurseForge files"
                  and step.get("started_at") and step.get("conclusion") != "skipped"]
        if not stages:
            continue
        if len(stages) != 1:
            raise ValueError("Ambiguous prior CurseForge stage.")
        artifacts = pages(f"{prefix}/actions/runs/{previous_id}/artifacts", "artifacts")
        name = f"curseforge-{tag}-{previous_id}-{previous_attempt}"
        matches = [artifact for artifact in artifacts if artifact["name"] == name]
        if not matches or any(artifact.get("expired") for artifact in matches):
            may_upload = False
            continue
        if len(matches) != 1:
            raise ValueError("Duplicate CurseForge receipt artifact.")
        artifact = matches[0]
        binding = artifact.get("workflow_run", {})
        if binding.get("id") != previous_id or binding.get("head_sha") != controller:
            raise ValueError("Receipt producer identity differs.")
        archive = github(f"{prefix}/actions/artifacts/{artifact['id']}/zip", binary=True)
        if len(archive) != artifact["size_in_bytes"] or artifact.get("digest") != "sha256:" + hashlib.sha256(archive).hexdigest():
            raise ValueError("Receipt artifact digest or size differs.")
        with ZipFile(io.BytesIO(archive)) as bundle:
            if bundle.namelist() != ["receipt.json"] or 4 * 1024 * 1024 < bundle.getinfo("receipt.json").file_size:
                raise ValueError("Unexpected receipt archive contents.")
            receipt = json.loads(bundle.read("receipt.json"))
        if receipt.get("tag") != tag or receipt.get("sourceCommit") != commit:
            raise ValueError("Receipt product identity differs.")
        receipts.append(receipt)
    destination.mkdir(parents=True, exist_ok=True)
    if receipts:
        combined = receipts[0]
        for receipt in receipts[1:]:
            identity = {k: v for k, v in receipt.items() if k != "files"}
            if identity != {k: v for k, v in combined.items() if k != "files"}:
                raise ValueError("Receipts disagree about the release manifest or project.")
            for name, record in receipt["files"].items():
                old = combined["files"].get(name)
                if old and old.get("fileId") and record.get("fileId") != old["fileId"]:
                    raise ValueError("Receipts disagree about an uploaded file ID.")
                combined["files"][name] = record
        (destination / "receipt.json").write_text(json.dumps(combined, indent=2) + "\n", encoding="utf-8")
    (destination / "history.json").write_text(json.dumps({"mayUpload": may_upload}) + "\n", encoding="utf-8")


def main():
    """Restore only the exact requested release into a fresh build directory."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--destination", required=True, type=Path)
    args = parser.parse_args()
    restore(os.environ["GITHUB_REPOSITORY"], args.tag, args.source_commit,
            int(os.environ["GITHUB_RUN_ID"]), int(os.environ["GITHUB_RUN_ATTEMPT"]), args.destination)


if __name__ == "__main__":
    main()
