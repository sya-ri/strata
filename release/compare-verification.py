#!/usr/bin/env python3
"""Compare real same-product verification artifacts without treating dry-run planning as test evidence."""
import argparse
from collections import Counter
import json
from pathlib import Path
import xml.etree.ElementTree as ET

import partition
import verification as v


def inventory(root):
    """Normalize per-shard diagnostic paths and retain test identities, multiplicities and failures."""
    files, cases, failures = set(), Counter(), []
    for path in root.rglob("*"):
        if not path.is_file() or "diagnostics" not in path.parts:
            continue
        name = "/".join(path.parts[path.parts.index("diagnostics") + 1:])
        files.add(name)
        if "/test-results/" not in name or not path.name.startswith("TEST-") or path.suffix != ".xml":
            continue
        for case in ET.parse(path).getroot().iter("testcase"):
            key = (name, case.get("classname", ""), case.get("name", ""))
            cases[key] += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failures.append(key)
    return files, cases, failures


def profiles(root):
    """Keep actual toolchains and runner inputs alongside the measured producer identities."""
    return [json.loads(path.read_text()) for path in sorted(root.rglob("runtime-profile.json"))]


def compare(baseline, candidate):
    """Require complete baseline task and diagnostic/test coverage on the unchanged product revision."""
    before = json.loads((baseline / "receipt.json").read_text())
    after = json.loads((candidate / "receipt.json").read_text())
    fields = ("schemaVersion", "scope", "sourceCommit", "controllerCommit", "tagObject", "runId", "runAttempt")
    for root, receipt in ((baseline, before), (candidate, after)):
        v.validate_receipt(root, {k: receipt[k] for k in fields})
    v.require(before["sourceCommit"] == after["sourceCommit"], "Comparison product revisions differ.")
    v.require(before["command"] == after["command"] == v.FULL_CHECK, "Comparison full-command contract differs.")
    before_files, before_cases, before_failures = inventory(baseline)
    after_files, after_cases, after_failures = inventory(candidate)
    v.require(before_cases and after_cases, "Comparison test evidence is missing.")
    # Shared prerequisites may run in multiple shards; their report bytes and timings need not match.
    missing_tasks = sorted(partition.keys(before["tasks"]) - partition.keys(after["tasks"]))
    missing_cases = sorted(before_cases - after_cases)
    missing_files = sorted(before_files - after_files)
    before_profiles, after_profiles = profiles(baseline), profiles(candidate)
    profile_fields = ("javaVersions", "logicalProcessors", "buildInputs")
    matched_profiles = bool(before_profiles and after_profiles) and all(
        all(profile[field] == before_profiles[0][field] for field in profile_fields)
        and all(profile["runner"][field] == before_profiles[0]["runner"][field]
                for field in ("RUNNER_OS", "RUNNER_ARCH", "ImageOS"))
        for profile in before_profiles + after_profiles)
    return dict(sourceCommit=before["sourceCommit"],
                baselineProducer={k: before[k] for k in ("controllerCommit", "runId", "runAttempt")},
                candidateProducer={k: after[k] for k in ("controllerCommit", "runId", "runAttempt")},
                baselineTaskCount=len(partition.keys(before["tasks"])), candidateTaskCount=len(partition.keys(after["tasks"])),
                baselineTestCount=sum(before_cases.values()), candidateTestCount=sum(after_cases.values()),
                baselineDiagnosticCount=len(before_files), candidateDiagnosticCount=len(after_files),
                matchedRuntimeProfiles=matched_profiles, baselineProfiles=before_profiles, candidateProfiles=after_profiles,
                missingTasks=missing_tasks, missingTests=missing_cases, missingDiagnostics=missing_files,
                failedTests=before_failures + after_failures)


def main():
    """Retain a reviewable comparison and fail on any missing required evidence."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--output", type=Path, default=Path("build/verification-comparison.json"))
    args = parser.parse_args()
    value = compare(args.baseline, args.candidate)
    v.write_json(args.output, value)
    v.require(value["matchedRuntimeProfiles"], "Runtime profiles are absent or unmatched; inspect the comparison.")
    v.require(not any(value[key] for key in ("missingTasks", "missingTests", "missingDiagnostics", "failedTests")),
              "Verification evidence differs; inspect the retained comparison.")


if __name__ == "__main__":
    main()
