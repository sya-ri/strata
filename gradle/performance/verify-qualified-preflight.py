#!/usr/bin/env python3
"""Qualify the real compiled preflight route and retained negative attempts on the hosted Windows boundary."""
import argparse
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


SPEC = importlib.util.spec_from_file_location("paired_jvm", Path(__file__).with_name("paired-jvm.py"))
PAIRED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAIRED)


def frozen_fixture(root, output):
    """Add only an independent test controller to a private frozen fixture; keep actual runtime sources identical."""
    head = PAIRED.git(root, "rev-parse", "HEAD")
    workspace = output / "workspaces" / "fixture-source"
    subprocess.run(["git", "clone", "--quiet", "--shared", "--no-checkout", str(root), str(workspace)], check=True)
    PAIRED.git(workspace, "config", "core.autocrlf", "false")
    PAIRED.git(workspace, "config", "core.longpaths", "true")
    attributes = workspace / ".git/info/attributes"
    attributes.parent.mkdir(parents=True, exist_ok=True)
    attributes.write_bytes(b"* -text\n")
    PAIRED.git(workspace, "checkout", "--quiet", "--detach", head)
    target = workspace / "quality/remote-benchmarks/src/jmh/java/independent/fixture/QualifiedFixtureController.java"
    target.parent.mkdir(parents=True)
    controller = root / "gradle/performance/fixtures/QualifiedFixtureController.java"
    shutil.copyfile(controller, target)
    PAIRED.git(workspace, "add", "--", target.relative_to(workspace).as_posix())
    tree = PAIRED.git(workspace, "write-tree")
    patch = output / "fixture.patch"
    with patch.open("xb") as stream:
        subprocess.run(["git", "-C", str(workspace), "diff", "--cached", "--binary", "--no-ext-diff", head], check=True, stdout=stream)
    return {"ref": head, "tree": tree, "patches": [{"path": patch.relative_to(root).as_posix(), "sha256": PAIRED.digest(patch)}]}, controller


def pack_qualification(output):
    """Retain each complete attempt ZIP once; exclude its private reusable workspaces from the outer artifact."""
    archive = output.parent / (output.name + ".zip")
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as target:
        for path in sorted(output.iterdir()):
            if path.is_file():
                PAIRED.require(not path.is_symlink(), "Qualification evidence contains a symlink")
                target.write(path, path.name)
    return archive


def main():
    """Run a complete real B0/C0/B1/C1/B2/C2 pair, then actual missing/altered/failure attempts without collection."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    output = parser.parse_args().output.resolve()
    root = Path(__file__).resolve().parents[2]
    PAIRED.require(os.name == "nt" and output.is_relative_to(root), "Use a fresh Windows repository output")
    output.mkdir(parents=True, exist_ok=False)
    result = {"status": "failed", "cases": [], "source_head": PAIRED.git(root, "rev-parse", "HEAD")}
    try:
        source, controller = frozen_fixture(root, output)
        qualification = output / "qualification.json"
        PAIRED.write_new(qualification, {"schema": "strata-compiled-control-fixture-v1", "controller_source_sha256": PAIRED.digest(controller),
                                        "contract": "Independent bounded loop visits and arithmetic oracle; this run qualifies transport, origin, receipt and failure boundaries, not a consuming project's tracer."})
        data = {"schema": "strata-paired-jvm-ci-v1", "sources": {side: source for side in ("fixture", "baseline", "candidate")},
                "runtime_projects": [":api", ":runtime:core", ":runtime:remote"],
                "suites": [{"project": ":quality:remote-benchmarks", "name": "qualified-control-route",
                            "benchmarks": ["RemoteProtocolBenchmark"], "workloads": ["RemoteProtocolBenchmark.diff"],
                            "parameters": {"nodes": ["100"], "change": ["Stable"]}, "expected_cases": 1,
                            "preflight": {"main": "independent.fixture.QualifiedFixtureController",
                                          "qualification": {"path": qualification.relative_to(root).as_posix(), "sha256": PAIRED.digest(qualification)},
                                          "runtime_classes": {":api": "dev.s7a.strata.projection.ProjectionValue", ":runtime:core": "dev.s7a.strata.runtime.spi.RuntimeUiSession", ":runtime:remote": "dev.s7a.strata.runtime.remote.RemoteTree"},
                                          "controls": [{"id": f"independent-{size}", "arguments": ["Complete", str(size)]} for size in (1, 31, 4096)]}}],
                "inputs": [], "compiled_pins": {side: [] for side in ("fixture", "baseline", "candidate")}, "dependency_pins": [], "java_pins": []}
        for case, behavior in (("complete", "Complete"), ("missing-control", "Missing"), ("altered-bytes", "Altered"), ("controller-failure", "Failure")):
            manifest = copy.deepcopy(data)
            manifest["suites"][0]["preflight"]["controls"][-1]["arguments"][0] = behavior
            manifest_path = output / f"{case}-manifest.json"
            PAIRED.write_new(manifest_path, manifest)
            attempt = output / case
            error = None
            try:
                PAIRED.run(root, manifest_path, attempt)
            except (ValueError, subprocess.CalledProcessError) as failure:
                error = str(failure)
            record = PAIRED.read_json(attempt / "attempt.json")
            positive = case == "complete"
            PAIRED.require((record["status"] == "passed") == positive and (error is None) == positive, f"Wrong compiled route result: {case}")
            phases = record["phases"]
            controls = [index for index, phase in enumerate(phases) if phase["name"].startswith("preflight-")]
            collections = [index for index, phase in enumerate(phases) if phase["name"].startswith("collect-")]
            PAIRED.require(controls and (positive or not collections), "A failed independent control reached timed collection")
            if positive:
                PAIRED.require(len(controls) == 2 and len(collections) == 12 and max(controls) < min(collections), "Incomplete real whole pair or missing pre-timing barrier")
                PAIRED.require(record["score_cells"] == 12, "The complete independent test fixture lost a score cell")
                result["collection_costs"] = [{"name": phase["name"], "seconds": phase["elapsed_seconds"],
                                               "job_cpu_seconds": (phase["terminal"]["jobUserCpu100ns"] + phase["terminal"]["jobKernelCpu100ns"]) / 10_000_000}
                                              for phase in phases if phase["name"].startswith("collect-")]
                result["control_costs"] = [{"name": phase["name"], "seconds": phase["elapsed_seconds"]} for phase in phases if phase["name"].startswith("preflight-")]
            else:
                PAIRED.require(not (attempt / "raw").exists(), "Negative preflight retained timed output")
                PAIRED.require((attempt / "prepared/baseline/target-0.jar").is_file()
                               and (attempt / "prepared-classpaths/qualified-control-route/baseline/classpath.json").is_file(), "First-control failure lost prepared runtime/harness bytes")
            result["cases"].append({"case": case, "status": "passed", "attempt_status": record["status"], "error": error,
                                    "attempt_sha256": PAIRED.digest(attempt / "attempt.json"), "archive_sha256": PAIRED.digest(attempt.with_suffix(".zip"))})
        result["status"] = "passed"
    except BaseException as failure:
        result["error"] = f"{type(failure).__name__}: {failure}"
        raise
    finally:
        PAIRED.write_new(output / "result.json", result)
        print(f"Preserved compiled preflight qualification: {pack_qualification(output)}", flush=True)


if __name__ == "__main__":
    main()
