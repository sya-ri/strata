"""Detached evidence validation and aggregation shipped inside the testkit JAR.

Consumers keep workload-specific expectations and presentation. This module uses
only the Python standard library and never measures an operation itself.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import statistics
import uuid
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit
from urllib.request import url2pathname
from zipfile import ZipFile


class EvidenceError(ValueError):
    """Evidence is incomplete, inconsistent, or belongs to another collector."""


def require(condition: bool, message: str) -> None:
    """Validate evidence even when the Python interpreter runs with -O."""
    if not condition:
        raise EvidenceError(message)


def digest(path: Path) -> str:
    """Hash actual bytes without keeping the artifact in memory."""
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


_loaded_archive = getattr(__loader__, "archive", None)
_loaded_archive_digest = digest(Path(_loaded_archive)) if _loaded_archive is not None else None


def collector_identity(collector_jar: Path) -> dict:
    """Bind comparisons to the archive actually imported, refusing later replacement."""
    require(_loaded_archive_digest is not None, "Import the comparison engine from the testkit JAR")
    actual = digest(collector_jar)
    require(actual == _loaded_archive_digest, "The comparison engine archive changed after import or differs from the selected JAR")
    return {"contract": "strata-performance-testkit-v1", "code_source_sha256": actual}


def sha256_bytes(content: bytes) -> str:
    """Hash an already detached source snapshot."""
    return hashlib.sha256(content).hexdigest()


def parse_json_document(content: bytes, path: Path) -> dict:
    """Decode an object report while preserving its source bytes for receipts."""
    try:
        value = json.loads(content.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise EvidenceError(f"Cannot read JSON report {path}: {error}") from error
    require(isinstance(value, dict), f"Report root must be an object: {path}")
    return value


def read_json_document(path: Path) -> tuple[dict, bytes]:
    """Read and validate one immutable snapshot, without a second receipt read."""
    require(path.is_file(), f"Missing report: {path}")
    try:
        content = path.read_bytes()
    except OSError as error:
        raise EvidenceError(f"Cannot read JSON report {path}: {error}") from error
    return parse_json_document(content, path), content


def validate_hash(value, label: str) -> None:
    """Require a canonical lowercase SHA-256 receipt."""
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None,
            f"Invalid SHA-256 at {label}")


def validate_run_id(value, label: str) -> str:
    """Normalize the existing UUID invocation contract without inventing an ID."""
    require(isinstance(value, str), f"Missing run ID at {label}")
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError) as error:
        raise EvidenceError(f"Invalid run ID at {label}") from error
    require(str(parsed) == value.lower(), f"Run ID is not canonical at {label}")
    return str(parsed)


def stable(values: list, label: str):
    """Preserve the fixed-workload three-run equality contract."""
    require(bool(values), f"No values supplied for {label}")
    first = values[0]
    require(all(value == first for value in values[1:]), f"Three-run identity differs: {label}")
    return first


def project_contract(value, excluded_fields: set[str], excluded_prefixes: tuple[str, ...]):
    """Project a detached contract using consumer-supplied descriptive exclusions."""
    if isinstance(value, dict):
        return {key: project_contract(child, excluded_fields, excluded_prefixes)
                for key, child in sorted(value.items())
                if key not in excluded_fields and not key.startswith(excluded_prefixes)}
    if isinstance(value, list):
        return [project_contract(child, excluded_fields, excluded_prefixes) for child in value]
    return value


def local_source(url: str) -> Path:
    """Decode a local file origin, refusing remote authorities and URL options."""
    parsed = urlsplit(url)
    require(parsed.scheme == "file" and not parsed.netloc and not parsed.query and not parsed.fragment,
            f"Expected a local artifact origin: {url}")
    return Path(url2pathname(parsed.path))


def verify_collectors(reports: list[dict], collector_jar: Path) -> dict:
    """Require the exact loaded comparison engine and collector on every run.

    Legacy evidence without a collector receipt is intentionally not admitted;
    its frozen validator remains responsible for its original contract.
    """
    require(bool(reports), "No performance reports")
    expected = collector_identity(collector_jar)
    for report in reports:
        require(report.get("collector_identity") == expected,
                "Collector differs or is missing; recollect both sides with this testkit JAR")
    return expected


def verify_equal(reports: list[dict], keys: tuple[str, ...]) -> None:
    """Check controlled inputs without silently accepting missing fields."""
    require(bool(reports), "No performance reports")
    for key in keys:
        require(all(key in report for report in reports), f"Missing comparison condition: {key}")
        require(all(report[key] == reports[0][key] for report in reports), f"Changed comparison condition: {key}")


def verify_repetitions(reports: list[dict], count: int = 3) -> None:
    """Require independent successful invocations, rather than copied receipts."""
    require(count > 0 and len(reports) == count, "Missing or extra performance repetitions")
    require(all(report.get("status") == "passed" for report in reports), "An invocation did not pass")
    identities = [report.get("run_id") for report in reports]
    require(all(isinstance(value, str) and value.strip() for value in identities)
            and len(set(identities)) == count, "Missing or duplicate invocation identity")


def index_phases(report: dict, keys: tuple[str, ...], expected_count: int | None = None) -> dict:
    """Index complete phase receipts, rejecting duplicate keys before comparison."""
    phases = report.get("phases")
    require(isinstance(phases, list) and bool(phases), "Missing performance phases")
    result = {}
    for phase in phases:
        require(all(key in phase for key in keys), "Missing performance phase identity")
        identity = tuple(phase[key] for key in keys)
        require(identity not in result, f"Duplicate performance phase: {identity}")
        result[identity] = phase
    require(expected_count is None or len(result) == expected_count, "Incomplete performance phase inventory")
    return result


def median(values) -> int | float:
    """Aggregate repetition measurements with the standard-library median."""
    detached = list(values)
    require(bool(detached) and all(type(value) in (int, float) and value >= 0
                                  and (type(value) is int or math.isfinite(value)) for value in detached),
            "Missing, negative, or non-finite performance metric")
    return statistics.median(detached)


def class_tree(path: Path) -> dict:
    """Recompute the canonical JVM class tree from the actual loaded archive."""
    with ZipFile(path) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename.endswith(".class")]
        require(0 < len(entries) <= 16384, "Missing or oversized class inventory")
        names = [entry.orig_filename for entry in entries]
        require(all(entry.orig_filename == entry.filename for entry in entries), "Noncanonical class archive entry")
        require(len(names) == len(set(names)), "Duplicate class archive entry")
        require(all(not name.startswith("/") and "\\" not in name
                    and all(part not in ("", ".", "..") for part in name.split("/"))
                    and str(PurePosixPath(name)) == name for name in names), "Unsafe class archive entry")
        total = 0
        tree = hashlib.sha256()
        for entry in sorted(entries, key=lambda value: value.filename.encode("utf-8")):
            require(0 <= entry.file_size <= 8 * 1024 * 1024, "Oversized class resource")
            total += entry.file_size
            require(total <= 64 * 1024 * 1024, "Oversized class tree")
            with archive.open(entry) as source:
                resource_hash = hashlib.file_digest(source, "sha256").hexdigest()
            tree.update(f"{entry.filename}={resource_hash}\n".encode("utf-8"))
    return {"status": "available", "algorithm": "sha256(path=sha256(resource-bytes)\\n)-utf8-v1",
            "entryCount": len(entries), "bytes": total, "sha256": tree.hexdigest()}


def verify_native_binary(native_report: dict, cpu_report: dict,
                         representatives: set[str], native_only_modules: set[str]) -> list[dict]:
    """Verify real host/class origins independently of JAR names.

    The consumer supplies its expected representative inventory; the kit owns
    hashing and agreement between CPU and native loaded bytes.
    """
    modules = native_report["strata"]["modules"]
    require(len({module["module"] for module in modules}) == len(modules), "Duplicate native module")
    require({module["representativeClass"] for module in modules if "classTree" in module} == representatives,
            "Incomplete native class-tree inventory")
    require({module["module"] for module in modules if "classTree" not in module} == native_only_modules,
            "Incomplete native-only inventory")
    receipts = []
    for module in modules:
        require(module["status"] == "resolved", "Unresolved native artifact")
        origin = module["codeSource"]
        path = local_source(origin["url"])
        require(digest(path) == origin["sha256"], "Native loaded JAR changed")
        entry = module["representativeClass"].replace(".", "/") + ".class"
        resource = module["classResource"]
        require(resource["entryName"] == entry, "Unexpected representative resource")
        with ZipFile(path) as archive:
            require(hashlib.sha256(archive.read(entry)).hexdigest() == resource["sha256"], "Native class bytes changed")
        if "classTree" not in module:
            receipts.append({"module": module["module"], "standalone_jar_sha256": origin["sha256"]})
            continue
        identity = cpu_report["strata_class_sha256"][module["representativeClass"]]
        cpu_path = local_source(identity["code_source"]["url"])
        require(digest(cpu_path) == identity["code_source"]["sha256"], "CPU loaded JAR changed")
        require(identity["class_resource"]["sha256"] == resource["sha256"], "CPU/native representative mismatch")
        tree = class_tree(cpu_path)
        require(tree == module["classTree"] == class_tree(path), "CPU/native class tree mismatch")
        receipts.append({"module": module["module"], "cpu_jar_sha256": digest(cpu_path), "native_class_tree": tree})
    return receipts


def _jmh_case(benchmark, mode, parameters) -> tuple:
    require(isinstance(benchmark, str) and bool(benchmark.strip()) and isinstance(mode, str) and bool(mode.strip()),
            "Missing JMH benchmark or mode")
    require(isinstance(parameters, dict) and all(isinstance(key, str) and key.strip() and isinstance(value, str)
                                               for key, value in parameters.items()), "Invalid JMH parameters")
    return benchmark, mode, tuple(sorted(parameters.items()))


def _jmh_targets(directory: Path, receipt: dict) -> dict:
    modules = receipt.get("runtime_metadata", {}).get("modules")
    archives = receipt.get("target_archives")
    require(isinstance(modules, list) and bool(modules) and isinstance(archives, dict), "Missing JMH target archive inventory")
    names = [module.get("module") for module in modules]
    require(all(isinstance(name, str) and name for name in names) and len(names) == len(set(names))
            and set(names) == set(archives), "Duplicate or unregistered JMH target")
    result = {}
    for module in modules:
        name = module["module"]
        filename = archives[name]
        require(isinstance(filename, str) and Path(filename).name == filename and filename not in (".", "..")
                and not any(character in filename for character in "/\\:"),
                "Unsafe JMH target archive path")
        path = directory / filename
        origin = module.get("codeSource", {})
        resource = module.get("classResource", {})
        representative = module.get("representativeClass")
        require(module.get("status") == "resolved" and origin.get("status") == resource.get("status") == "available"
                and isinstance(representative, str) and representative, "Unresolved JMH target")
        validate_hash(origin.get("sha256"), "JMH target archive")
        validate_hash(resource.get("sha256"), "JMH representative")
        require(path.is_file() and digest(path) == origin["sha256"], "Preserved JMH target differs from loaded bytes")
        require(class_tree(path) == module.get("classTree"), "Preserved JMH class tree differs from loaded resources")
        entry = representative.replace(".", "/") + ".class"
        require(resource.get("entryName") == entry, "Unexpected JMH representative resource")
        with ZipFile(path) as archive:
            require(sha256_bytes(archive.read(entry)) == resource["sha256"], "Preserved JMH representative differs")
        result[name] = (representative, origin["sha256"], resource["sha256"], module["classTree"])
    return result


def summarize_jmh(directories: list[Path], collector_jar: Path, repetitions: int = 3) -> dict:
    """Validate exact JMH matrices and archive-bound receipts, then aggregate independent runs.

    JMH owns all timings, percentiles and profiling. AverageTime scores are means,
    not per-operation p95/p99. Missing GC time remains unavailable instead of zero.
    """
    receipts, runs, sources, targets = [], [], [], []
    control_keys = ("jmhVersion", "mode", "threads", "forks", "jvm", "jvmArgs", "jdkVersion", "vmName", "vmVersion",
                    "warmupIterations", "warmupTime", "warmupBatchSize", "measurementIterations", "measurementTime", "measurementBatchSize")
    for directory in directories:
        receipt, receipt_bytes = read_json_document(directory / "receipt.json")
        require(receipt.get("contract") == "strata-jmh-v1", "Unsupported JMH receipt")
        validate_run_id(receipt.get("run_id"), "JMH invocation")
        validate_hash(receipt.get("harness_sha256"), "JMH harness")
        require(isinstance(receipt.get("arguments"), list) and bool(receipt["arguments"])
                and all(isinstance(value, str) for value in receipt["arguments"]), "Missing JMH options")
        fixtures = receipt.get("fixture_identity")
        require(isinstance(fixtures, dict) and bool(fixtures), "Missing JMH fixture identity")
        for identity in fixtures.values():
            validate_hash(identity, "JMH fixture")
        environment = receipt.get("environment")
        require(isinstance(environment, dict) and bool(environment) and all(isinstance(value, str) and value for value in environment.values()),
                "Missing JMH environment")
        verify_collectors([receipt], collector_jar)
        require(digest(directory / "collector.jar") == receipt["collector_identity"]["code_source_sha256"], "Preserved JMH collector differs")
        require(digest(directory / "harness.jar") == receipt["harness_sha256"], "Preserved JMH harness differs")
        with ZipFile(directory / "harness.jar") as harness:
            require("org/openjdk/jmh/runner/Runner.class" in harness.namelist(), "Preserved archive is not the JMH harness")
        targets.append(_jmh_targets(directory, receipt))
        content = (directory / "results.json").read_bytes()
        require(sha256_bytes(content) == receipt.get("results_sha256"), "JMH raw results changed")
        try:
            raw = json.loads(content)
            registered = [_jmh_case(*json.loads(value)) for value in receipt["registered_workloads"]]
        except (ValueError, TypeError, KeyError) as error:
            raise EvidenceError(f"Invalid JMH workload registration: {error}") from error
        require(bool(registered) and len(registered) == len(set(registered)), "Missing or duplicate registered JMH workload")
        require(isinstance(raw, list) and bool(raw), "Missing JMH results")
        indexed = {}
        for row in raw:
            key = _jmh_case(row.get("benchmark"), row.get("mode"), row.get("params", {}))
            require(key not in indexed, "Duplicate JMH result")
            require(all(field in row for field in control_keys), "Missing JMH execution condition")
            for field in ("threads", "forks", "warmupBatchSize", "measurementIterations", "measurementBatchSize"):
                require(type(row[field]) is int and row[field] > 0, f"Invalid JMH condition: {field}")
            require(type(row["warmupIterations"]) is int and row["warmupIterations"] >= 0, "Invalid JMH warm-up count")
            metric = row.get("primaryMetric", {})
            require(isinstance(metric.get("scoreUnit"), str) and metric["scoreUnit"], "Missing JMH primary unit")
            median([metric.get("score")])
            samples = metric.get("rawDataHistogram" if row["mode"] == "sample" else "rawData")
            require(isinstance(samples, list) and len(samples) == row["forks"]
                    and all(isinstance(fork, list) and len(fork) == row["measurementIterations"] for fork in samples),
                    "Incomplete JMH fork/iteration data")
            if row["mode"] == "sample":
                for histogram in (value for fork in samples for value in fork):
                    require(isinstance(histogram, list) and bool(histogram), "Missing JMH sample histogram")
                    for pair in histogram:
                        require(isinstance(pair, list) and len(pair) == 2 and type(pair[1]) is int and pair[1] > 0,
                                "Invalid JMH histogram count")
                        median([pair[0]])
                for percentile in ("50.0", "95.0", "99.0"):
                    median([metric.get("scorePercentiles", {}).get(percentile)])
            else:
                median(value for fork in samples for value in fork)
            secondary = row.get("secondaryMetrics", {})
            for name, unit in (("gc.alloc.rate.norm", "B/op"), ("gc.count", "counts")):
                require(name in secondary and secondary[name].get("scoreUnit") == unit, f"Missing JMH GC metric: {name}")
                median([secondary[name].get("score")])
            if "gc.time" in secondary:
                require(secondary["gc.time"].get("scoreUnit") == "ms", "Changed JMH GC time unit")
                median([secondary["gc.time"].get("score")])
            indexed[key] = row
        require(set(indexed) == set(registered), "JMH did not complete its registered workload matrix")
        receipts.append(receipt)
        runs.append(indexed)
        sources.append({"run_id": receipt["run_id"], "repetition": receipt.get("repetition"),
                        "receipt_sha256": sha256_bytes(receipt_bytes), "results_sha256": sha256_bytes(content)})
    verify_repetitions(receipts, repetitions)
    require(all(type(receipt.get("repetition")) is int for receipt in receipts), "Invalid JMH repetition index")
    require({receipt.get("repetition") for receipt in receipts} == set(range(repetitions)), "Missing or duplicate JMH repetition index")
    verify_equal(receipts, ("contract", "arguments", "fixture_identity", "harness_sha256", "environment", "registered_workloads"))
    stable(targets, "JMH loaded runtime")
    require(all(set(run) == set(runs[0]) for run in runs), "Changed JMH workload matrix")
    summaries = []
    for key in sorted(runs[0]):
        rows = [run[key] for run in runs]
        verify_equal(rows, control_keys)
        require(len({row["primaryMetric"]["scoreUnit"] for row in rows}) == 1, "Changed JMH primary unit")
        times = [row["secondaryMetrics"]["gc.time"]["score"] for row in rows if "gc.time" in row["secondaryMetrics"]]
        summary = {"benchmark": key[0], "mode": key[1], "params": dict(key[2]),
                          "primary_unit": rows[0]["primaryMetric"]["scoreUnit"],
                          "primary_score_median": median(row["primaryMetric"]["score"] for row in rows),
                          "allocation_bytes_per_operation_median": median(row["secondaryMetrics"]["gc.alloc.rate.norm"]["score"] for row in rows),
                          "gc_count_median": median(row["secondaryMetrics"]["gc.count"]["score"] for row in rows),
                          "gc_time_ms_median_available": median(times) if times else None,
                          "gc_time_available_repetitions": len(times)}
        if key[1] == "sample":
            summary["per_run_percentile_medians"] = {percentile: median(row["primaryMetric"]["scorePercentiles"][percentile] for row in rows)
                                                     for percentile in ("50.0", "95.0", "99.0")}
        summaries.append(summary)
    return {"contract": "strata-jmh-summary-v1", "status": "passed", "collector_identity": collector_identity(collector_jar),
            "repetitions": repetitions, "case_count": len(summaries), "sources": sources, "cases": summaries}
