#!/usr/bin/env python3
"""Partition the selected product's full graph and retain independently attributable shard evidence."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import verification as v

PLAN = Path("build/verification-plan")
WEB_CHECKS = {":api:check", ":runtime:core:check", ":runtime:web:check", ":examples:web:check", ":integration:web:check"}
JS_TESTS = [f":{owner}:{task}" for owner in ("api", "runtime:core")
            for task in ("jsBrowserTest", "jsNodeTest")]


def graph(product):
    """Read observer inventories using checkout-relative build identities."""
    return [dict(task, root=path.parent.parent.parent.relative_to(product).as_posix())
            for path in sorted(product.glob("**/build/release-verification/tasks.json"))
            for task in json.loads(path.read_text())]


def keys(tasks):
    """Compare task ownership independently of ordering and repeated shared prerequisites."""
    return {(t["root"], t["build"], t["path"]) for t in tasks}


def make_plan(tasks, versions):
    """Derive all check owners from the full graph, never from changed paths or a version allowlist."""
    checks = sorted({t["path"] for t in tasks if t["root"] == "." and t["path"].endswith(":check")})
    native = {f":{owner}:minecraft-fabric-{version}:check" for version in versions for owner in ("runtime", "integration")}
    v.require(native <= set(checks) and WEB_CHECKS <= set(checks), "Full graph lacks expected native or browser owners.")
    common = [p for p in checks if p not in native | WEB_CHECKS]
    common += ["koverHtmlReport", "koverXmlReport", "-Pkover"]
    common += [item for task in JS_TESTS for item in ("-x", task)]
    # KMP's allTests report needs its JVM and JS results in one workspace; coverage still owns fresh JVM tests.
    shards = [dict(id="common", kind="common", arguments=common),
              dict(id="web", kind="web", arguments=sorted(WEB_CHECKS))]
    shards += [dict(id="minecraft-" + version.replace(".", "-"), kind="minecraft",
                    arguments=[":ciMinecraftCheck", "-Pstrata.minecraftVersions=" + version]) for version in versions]
    return dict(tasks=tasks, shards=shards)


def command(shard):
    """Use existing Gradle ownership and admission rules without editing product sources."""
    return ["bash", "./gradlew", *shard["arguments"], "-Pstrata.webDemoInstallSystemDependencies=true",
            "--stacktrace", "--no-configuration-cache", "--init-script", "../release/verification-tasks.gradle"]


def phases(shard):
    """List the real provisioning producers required by each verification owner."""
    return ["check"] + {"common": [], "web": ["npm", "browsers"], "minecraft": ["native"]}[shard["kind"]]


def plan(product):
    """Export the original full graph; dry-run planning is not accepted as verification."""
    code = v.phase("plan", product, v.check_command() + ["--dry-run"], PLAN)
    v.require(code == 0, "Full release graph planning failed.")
    subprocess.run(["bash", "gradle/plan-minecraft-ci.sh"], cwd=product, check=True,
                   env=dict(os.environ, STRATA_MC_VERSIONS="all", STRATA_CI_DOCS="false"))
    matrix = json.loads((product / "build/github-actions/minecraft-matrix.json").read_text())
    value = dict(v.identity(product), **make_plan(graph(product), [row["version"] for row in matrix["include"]]))
    v.write_json(PLAN / "plan.json", value)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write("matrix=" + json.dumps(dict(include=value["shards"]), separators=(",", ":")) + "\n")


def selected(product):
    """Reject stale plans and arbitrary commands before executing a shard."""
    value = json.loads((PLAN / "plan.json").read_text())
    expected = v.identity(product)
    v.require(all(value.get(k) == entry for k, entry in expected.items() if k != "runAttempt")
              and 0 < value["runAttempt"] <= expected["runAttempt"], "Shard plan producer differs.")
    matches = [s for s in value["shards"] if s["id"] == os.environ["SHARD"]]
    v.require(len(matches) == 1, "Unknown or duplicated shard.")
    return value, matches[0]


def restore_plan(product):
    """Check the trusted planner artifact before accepting matrix-supplied task arguments."""
    artifact_id = os.environ["PLAN_ARTIFACT_ID"]
    v.require(re.fullmatch(r"[1-9][0-9]*", artifact_id), "Invalid planner artifact ID.")
    artifact = json.loads(subprocess.check_output(["gh", "api",
                          f"repos/{os.environ['GITHUB_REPOSITORY']}/actions/artifacts/{artifact_id}"]))
    expected = v.identity(product)
    v.require(re.fullmatch(re.escape(expected["scope"] + "-partition-plan-") + r"[1-9][0-9]*", artifact["name"]),
              "Planner artifact name differs.")
    v.download_artifact(artifact, PLAN, expected, os.environ["PLAN_ARTIFACT_DIGEST"])
    selected(product)


def seal(product):
    """Only seal fresh successful execution, including required shard-specific provisioning."""
    value, shard = selected(product)
    expected = v.identity(product)
    for name in phases(shard):
        record = json.loads((v.EVIDENCE / f"{name}.json").read_text())
        v.require(all(record.get(k) == entry for k, entry in expected.items()) and record.get("exitCode") == 0,
                  f"Shard {name} did not succeed for this producer.")
    record = json.loads((v.EVIDENCE / "check.json").read_text())
    v.require(record["command"] == command(shard), "Shard command differs from its plan.")
    v.write_json(v.EVIDENCE / "shard.json", dict(expected, shard=shard, planDigest=v.digest(PLAN / "plan.json"),
                                               tasks=graph(product)))


def validate_shards(root, expected, *, write_report=False):
    """Validate immutable shard evidence; only aggregation writes its diagnostic parity report."""
    value = json.loads((root / "plan/plan.json").read_text())
    v.require(all(value.get(k) == entry for k, entry in expected.items() if k != "runAttempt")
              and 0 < value["runAttempt"] <= expected["runAttempt"], "Aggregate plan identity differs.")
    v.require(len({s["id"] for s in value["shards"]}) == len(value["shards"]), "Duplicate planned shards.")
    tasks, producers = {}, []
    for shard in value["shards"]:
        directory = root / "shards" / shard["id"]
        record = json.loads((directory / "shard.json").read_text())
        v.require(all(record.get(k) == entry for k, entry in expected.items() if k != "runAttempt")
                  and 0 < record["runAttempt"] <= expected["runAttempt"]
                  and record["shard"] == shard and record["planDigest"] == v.digest(root / "plan/plan.json"),
                  "Shard revision, producer, command or plan differs.")
        for name in phases(shard):
            check = json.loads((directory / f"{name}.json").read_text())
            v.require(all(check.get(k) == record[k] for k in expected) and check.get("exitCode") == 0
                      and (name != "check" or check.get("command") == command(shard)), "Failed or altered shard execution.")
        for task in record["tasks"]:
            tasks[(task["root"], task["build"], task["path"])] = task
        producers.append(dict(shard=shard["id"], runAttempt=record["runAttempt"]))
    missing = sorted(keys(value["tasks"]) - set(tasks))
    if write_report:
        v.write_json(root / "parity.json", dict(expectedTaskCount=len(keys(value["tasks"])),
                                               actualTaskCount=len(tasks), missing=missing, producers=producers))
    v.require(not missing, f"Partitioned verification omitted {len(missing)} full-command tasks: {missing[:20]}")
    return list(tasks.values())


def aggregate(product):
    """Download immutable same-run artifacts by digest and produce the existing signing-gate contract."""
    expected = v.identity(product)
    repo, run = os.environ["GITHUB_REPOSITORY"], expected["runId"]
    pages = json.loads(subprocess.check_output(["gh", "api", "--paginate", "--slurp",
                                               f"repos/{repo}/actions/runs/{run}/artifacts?per_page=100"]))
    artifacts = [a for page in pages for a in page["artifacts"]]
    producers = []

    def download(label, destination):
        """Allow carried-forward successful producers only from this immutable run and controller."""
        pattern = re.compile(re.escape(f"{expected['scope']}-partition-{label}-") + r"([1-9][0-9]*)$")
        matches = [(int(m[1]), a) for a in artifacts if (m := pattern.fullmatch(a["name"]))
                   and int(m[1]) <= expected["runAttempt"]]
        v.require(matches, f"Missing partition artifact: {label}")
        attempt, artifact = max(matches, key=lambda entry: entry[0])
        v.download_artifact(artifact, destination, expected, artifact["digest"].removeprefix("sha256:"))
        record = json.loads((destination / ("plan.json" if label == "plan" else "shard.json")).read_text())
        v.require(record["runAttempt"] == attempt, "Artifact name and sealed producer attempt differ.")
        producers.append({k: artifact[k] for k in ("id", "name", "digest")})
        v.write_json(v.EVIDENCE / "artifact-producers.json", producers)

    download("plan", v.EVIDENCE / "plan")
    value = json.loads((v.EVIDENCE / "plan/plan.json").read_text())
    for shard in value["shards"]:
        download(shard["id"], v.EVIDENCE / "shards" / shard["id"])
    tasks = validate_shards(v.EVIDENCE, expected, write_report=True)
    files = {p.relative_to(v.EVIDENCE).as_posix(): v.digest(p) for p in sorted(v.EVIDENCE.rglob("*"))
             if p.is_file() and p != v.EVIDENCE / "receipt.json"}
    v.write_json(v.EVIDENCE / "receipt.json", dict(expected, command=v.FULL_CHECK, topology="partitioned",
                                                tasks=tasks, files=files))


def main():
    """Expose planning, real shard execution and strict aggregation to the reusable workflow."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("plan", "restore-plan", "check", "seal", "aggregate"))
    parser.add_argument("--product", type=Path, default=Path("product"))
    args = parser.parse_args()
    if args.operation == "plan":
        plan(args.product)
    elif args.operation == "restore-plan":
        restore_plan(args.product)
    elif args.operation == "check":
        _, shard = selected(args.product)
        sys.exit(v.phase("check", args.product, command(shard)))
    elif args.operation == "seal":
        seal(args.product)
    else:
        aggregate(args.product)


if __name__ == "__main__":
    main()
