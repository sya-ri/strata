#!/usr/bin/env python3
"""Run one opt-in frozen JVM pair using the existing Gradle collectors and processor."""
import argparse
import base64
import csv
from contextlib import contextmanager
from enum import Enum
import hashlib
import html
import importlib.util
import io
import json
import lzma
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import tomllib
from types import SimpleNamespace
from urllib.parse import unquote, urlparse
import zipfile
import xml.etree.ElementTree as ElementTree


class FixtureProject(Enum):
    """Decode the existing CPU collector entry points at the manifest boundary."""
    HISTORICAL = (":quality:benchmarks", "jmhHistorical", "verifyHistoricalWorkloads")
    COMPONENT = (":quality:component-benchmarks", "jmhComponents", "verifyComponentRenderingWork")
    REMOTE = (":quality:remote-benchmarks", "jmhRemote", "verifyRemoteWorkloads")

    @classmethod
    def decode(cls, value):
        """Reject projects without a working existing collector and full work gate."""
        return next((item for item in cls if item.value[0] == value), None)


def require(condition, message):
    """Reject incomplete or changed inputs without certifying partial evidence."""
    if not condition:
        raise ValueError(message)


def read_json(path):
    """Read bounded strict JSON; duplicate keys cannot replace a pinned value."""
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 4 * 1024 * 1024,
            f"Missing or unsafe manifest: {path}")
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique,
                      parse_constant=lambda value: require(False, f"Non-finite JSON value: {value}"))


def write_new(path, value):
    """Preserve attempt evidence and refuse to overwrite an earlier record."""
    with path.open("x", encoding="utf-8") as target:
        json.dump(value, target, indent=2, allow_nan=False)
        target.write("\n")


def digest(path):
    """Hash a regular file's actual bytes rather than its path or name."""
    require(path.is_file() and not path.is_symlink(), f"Missing or unsafe pinned file: {path}")
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def preserve_failed_jvm_diagnostics(directory, workspace, terminal, environment):
    """Keep bounded, sanitized failed-JVM reports from known invocation roots without masking its original failure."""
    receipt = {"schema": "strata-failed-owned-jvm-diagnostics-v1", "qualified": False, "entries": [], "errors": [],
               "maximum_read_bytes": 4 * 1024**2, "maximum_retained_bytes": 4 * 1024**2, "maximum_file_bytes": 1024**2,
               "maximum_files": 16, "read_bytes": 0, "retained_bytes": 0}
    try:
        effective = dict(os.environ, **environment)
        sensitive = r"token|password|passwd|secret|credential|authorization|api[_-]?key|access[_-]?key|private[_-]?key"
        secrets = sorted({value for key, value in effective.items() if value and re.search(sensitive, key, re.I)}, key=len, reverse=True)
        def sanitized(data, crash):
            text = data.decode("utf-8-sig", errors="replace")
            if crash:
                boundary = re.search(r"(?im)^(?:Registers:|Register to memory mapping|Top of Stack:|Instructions:|VM Arguments:|Environment Variables:|.*P R O C E S S.*)", text)
                if boundary:
                    text = text[:boundary.start()]
            text = re.sub(r"(?im)^.*(?:Command Line:|jvm_args:|Configuring env variables|" + sensitive + r").*(?:\n|$)", "[sensitive line omitted]\n", text)
            for value in secrets:
                text = text.replace(value, "[redacted]")
            return re.sub(r"https?://\S+", "[URL omitted]", text).encode("utf-8")
        generations = []
        if terminal.get("nativeInvoked") is True:
            for pid, histories in terminal.get("members", {}).items():
                for member in histories:
                    born, exited = member.get("creationFileTime"), member.get("exitFileTime")
                    image = Path(member.get("image", ""))
                    if (type(member.get("pid")) is int and str(member["pid"]) == pid
                            and type(born) is int and 0 < born and type(exited) is int and born <= exited
                            and member.get("inOwnedJob") is True and member.get("completed") is True and member.get("handleClosed") is True
                            and type(member.get("exitCode")) is int and member["exitCode"] != 0
                            and image.is_absolute() and image.name.lower() in ("java.exe", "javaw.exe")):
                        generations.append(member)
        receipt["known_failed_jvm_generations"] = len(generations)
        receipt["omitted_generations"] = max(0, len(generations) - 16)
        if generations:
            wrapper = (Path(__file__).resolve().parents[2] / "gradle/wrapper/gradle-wrapper.properties").read_bytes()
            version = re.search(r"(?m)^distributionUrl=.*?/gradle-([^/]+?)-(?:bin|all)\.zip\s*$", wrapper.decode("utf-8")).group(1)
            require(re.fullmatch(r"[0-9]+(?:\.[0-9]+)+(?:-[A-Za-z0-9.-]+)?", version), "Invalid fixed Gradle version")
            receipt["gradle_wrapper_sha256"] = hashlib.sha256(wrapper).hexdigest()
            home = Path(effective.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))).resolve()
            workspace = Path(workspace).resolve()
            roots = [("workspace", workspace, workspace), ("gradle-daemon", home, home / "daemon" / version)]
            for member in sorted(generations, key=lambda item: item["exitFileTime"], reverse=True)[:16]:
                owner = {key: member[key] for key in ("pid", "creationFileTime", "exitFileTime")}
                for label, root, folder in roots:
                    names = [f"hs_err_pid{member['pid']}.log"] + ([f"daemon-{member['pid']}.out.log"] if label == "gradle-daemon" else [])
                    for name in names:
                        if (receipt["maximum_read_bytes"] <= receipt["read_bytes"] or receipt["maximum_retained_bytes"] <= receipt["retained_bytes"]
                                or len(receipt["entries"]) == receipt["maximum_files"]):
                            receipt["truncated"] = True
                            continue
                        source = folder / name
                        if not source.exists():
                            continue
                        try:
                            require(not source.is_symlink() and not folder.is_symlink() and not folder.parent.is_symlink(), "Linked diagnostic path")
                            resolved = source.resolve()
                            require(resolved.is_relative_to(root), "Diagnostic escaped its invocation root")
                            before = source.stat()
                            require(stat.S_ISREG(before.st_mode), "Nonregular diagnostic")
                            born_ns = (member["creationFileTime"] - 116444736000000000) * 100
                            exit_ns = (member["exitFileTime"] - 116444736000000000) * 100
                            require(born_ns <= before.st_mtime_ns <= exit_ns, "Diagnostic belongs to another process lifetime")
                            limit = min(receipt["maximum_file_bytes"], receipt["maximum_read_bytes"] - receipt["read_bytes"])
                            offset = max(0, before.st_size - limit) if name.startswith("daemon-") else 0
                            with source.open("rb") as stream:
                                stream.seek(offset)
                                raw = stream.read(limit)
                            receipt["read_bytes"] += len(raw)
                            after = source.stat()
                            require((before.st_size, before.st_mtime_ns, before.st_ino) == (after.st_size, after.st_mtime_ns, after.st_ino)
                                    and source.resolve() == resolved, "Diagnostic changed while being read")
                            crash = name.startswith("hs_err_")
                            if crash:
                                observed_pid = re.search(rb"\bpid\s*[=:]\s*([0-9]+)\b", raw[:8192])
                                require(observed_pid and int(observed_pid[1]) == member["pid"], "Crash report lacks its actual owned PID")
                            retained_limit = min(limit, receipt["maximum_retained_bytes"] - receipt["retained_bytes"])
                            retained = sanitized(raw, crash)[:retained_limit].decode("utf-8", errors="ignore").encode("utf-8")
                            destination = Path(directory) / "failed-jvm-diagnostics" / f"{member['pid']}-{member['creationFileTime']}" / (label + "-" + name)
                            destination.parent.mkdir(parents=True, exist_ok=True)
                            with destination.open("xb") as target:
                                target.write(retained)
                            receipt["retained_bytes"] += len(retained)
                            receipt["entries"].append({"owner": owner, "source_root": label, "source_relative": resolved.relative_to(root).as_posix(),
                                "source_file_bytes": before.st_size, "source_mtime_ns": before.st_mtime_ns, "source_range_offset": offset,
                                "source_range_bytes": len(raw), "source_range_sha256": hashlib.sha256(raw).hexdigest(),
                                "source_file_sha256": hashlib.sha256(raw).hexdigest() if offset == 0 and len(raw) == before.st_size else None,
                                "retained_path": destination.relative_to(directory).as_posix(), "retained_bytes": len(retained),
                                "retained_sha256": hashlib.sha256(retained).hexdigest(), "sanitized": True, "qualified": False})
                        except Exception as failure:
                            receipt["errors"].append({"owner": owner, "source_root": label, "name": name, "error_type": type(failure).__name__})
    except Exception as failure:
        receipt["errors"].append({"stage": "diagnostic-retention", "error_type": type(failure).__name__})
    receipt["status"] = "retained-unqualified" if receipt["entries"] else "unavailable"
    try:
        write_new(Path(directory) / "failed-jvm-diagnostics.json", receipt)
    except Exception as failure:
        receipt["manifest_error_type"] = type(failure).__name__
    return receipt


def canonical_path(value):
    """Decode the existing canonical repository-relative path format without filesystem resolution."""
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_$+./-]+", value), "Invalid repository path")
    relative = PurePosixPath(value)
    require(not relative.is_absolute() and all(part not in (".", "..", ".git") for part in relative.parts)
            and relative.as_posix() == value, "Noncanonical repository path")
    return relative


def relative_path(root, value):
    """Keep manifest files within the checkout, including on Windows."""
    relative = canonical_path(value)
    path = root.joinpath(*relative.parts)
    require(path.resolve().is_relative_to(root.resolve()), "Repository path escapes checkout")
    return path


def pin(root, value):
    """Verify the declared immutable bytes before using a patch or fixture input."""
    require(set(value) == {"path", "sha256"} and re.fullmatch(r"[0-9a-f]{64}", value["sha256"]), "Invalid file pin")
    path = relative_path(root, value["path"])
    require(digest(path) == value["sha256"], f"Pinned bytes changed: {value['path']}")
    return path


def input_paths(inputs, external_inputs=()):
    """Resolve optional logical roots and paths, keeping omitted layouts flat and rejecting path aliases."""
    paths = {}
    prefixes = {}
    reserved = {"con", "prn", "aux", "nul", "conin$", "conout$", *(f"com{index}" for index in range(1, 10)), *(f"lpt{index}" for index in range(1, 10))}
    for index, item in enumerate(inputs):
        require(isinstance(item, dict) and set(item) in ({"label", "pin"}, {"label", "pin", "layout"}), "Invalid fixture input fields")
        path = PurePosixPath(f"input-{index}.jar" if item["label"] in external_inputs else f"input-{index}.bin")
        if "layout" in item:
            layout = item["layout"]
            require(isinstance(layout, dict) and set(layout) == {"root", "path"}, "Invalid input layout fields")
            require(isinstance(layout["root"], str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", layout["root"]), "Invalid input root")
            require(isinstance(layout["path"], str) and len(layout["path"]) <= 4096, "Invalid logical input path")
            path = canonical_path(layout["root"] + "/" + canonical_path(layout["path"]).as_posix())
            require(all(not part.endswith(".") and part.casefold() != ".git" and part.split(".", 1)[0].casefold() not in reserved for part in path.parts),
                    "Nonportable logical input path")
        require(item["label"] not in paths, "Duplicate fixture input label")
        paths[item["label"]] = path
        for depth in range(1, len(path.parts) + 1):
            prefix = PurePosixPath(*path.parts[:depth])
            key = prefix.as_posix().casefold()
            require(key not in prefixes or prefixes[key] == prefix, "Case-colliding logical input paths")
            prefixes[key] = prefix
    files = set(paths.values())
    require(len(files) == len(paths), "Duplicate logical input destination")
    require(all(not any(parent in files for parent in path.parents) for path in files), "Logical input file/directory collision")
    return paths


def load_manifest(root, path):
    """Validate explicit frozen refs and selectors; sampling and repetition settings stay fixed."""
    data = read_json(path)
    if data.get("schema") in ("strata-paired-browser-ci-v1", "strata-source-preparation-ci-v1"):
        return browser_controller().load_manifest(SimpleNamespace(**globals()), root, data)
    required = {"schema", "sources", "runtime_projects", "suites", "inputs", "compiled_pins", "dependency_pins", "java_pins"}
    require(required <= set(data) <= required | {"owner_reports", "owner_continuation", "driver_source", "input_properties"} and data["schema"] == "strata-paired-jvm-ci-v1", "Unsupported paired JVM manifest")
    if "driver_source" in data:
        driver = data["driver_source"]
        require(isinstance(driver, dict) and set(driver) == {"ref", "tree"}
                and all(isinstance(driver[key], str) and re.fullmatch(r"[0-9a-f]{40}", driver[key]) for key in ("ref", "tree")),
                "Pin the exact unpatched driver commit and tree")
    require(set(data["sources"]) == {"fixture", "baseline", "candidate"}, "Supply all three source bindings")
    for source in data["sources"].values():
        require(set(source) == {"ref", "tree", "patches"}, "Invalid source binding")
        require(all(re.fullmatch(r"[0-9a-f]{40}", source[key]) for key in ("ref", "tree")), "Use exact source commit/tree SHAs")
        require(isinstance(source["patches"], list), "Invalid source patch list")
        for patch in source["patches"]:
            pin(root, patch)
    projects = data["runtime_projects"]
    require(isinstance(projects, list) and projects and len(set(projects)) == len(projects), "Missing or duplicate runtime project")
    require(all(re.fullmatch(r":(?:api|velocity-api|runtime:(?:core|headless|remote|velocity|minecraft|minecraft-fonts-lwjgl))", item) for item in projects),
            "Unsupported CPU runtime project")
    require(isinstance(data["suites"], list) and data["suites"], "No paired suites selected")
    names = set()
    for suite in data["suites"]:
        fields = {"project", "name", "benchmarks", "workloads", "parameters", "expected_cases"}
        require(fields <= set(suite) <= fields | {"profilers", "operation_reports", "preflight", "test_gates", "modes", "compiled_main", "external_runtime_inputs"}, "Invalid suite fields")
        if "compiled_main" in suite or "external_runtime_inputs" in suite:
            require(FixtureProject.decode(suite["project"]) == FixtureProject.HISTORICAL, "Compiled launch selectors require the existing historical template")
        if "compiled_main" in suite:
            require(isinstance(suite["compiled_main"], str) and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", suite["compiled_main"]), "Use one compiled JVM main class")
        if "external_runtime_inputs" in suite:
            selected = suite["external_runtime_inputs"]
            require(isinstance(selected, dict) and set(selected) == {"baseline", "candidate"}
                    and all(isinstance(label, str) for label in selected.values()), "Bind both external runtime input labels")
        require(suite.get("modes", ["avgt", "sample"]) in (["avgt"], ["avgt", "sample"]),
                "Preserve the declared Standard average-time profile, optionally with SampleTime")
        require(FixtureProject.decode(suite["project"]) is not None, "Unsupported fixture project")
        require(re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]*", suite["name"]) and suite["name"] not in names, "Invalid or duplicate suite name")
        names.add(suite["name"])
        for key in ("benchmarks", "workloads"):
            values = suite[key]
            require(isinstance(values, list) and len(values) == len(set(values))
                    and all(re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_.$]*", value) for value in values), "Invalid or duplicate generated selector")
        require(isinstance(suite["parameters"], dict)
                and all(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) and isinstance(values, list) and values
                        and len(values) == len(set(values)) and all(isinstance(item, str) and re.fullmatch(r"[A-Za-z0-9_+.-]+", item) for item in values)
                        for key, values in suite["parameters"].items()), "Invalid compiled parameter selection")
        require(isinstance(suite["expected_cases"], int) and not isinstance(suite["expected_cases"], bool) and 0 < suite["expected_cases"], "Declare the complete selected case count")
        profilers = suite.get("profilers", [])
        require(isinstance(profilers, list) and len(profilers) == len(set(profilers))
                and all(isinstance(value, str) and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", value) for value in profilers), "Use unique compiled JMH profiler class names")
        gates = suite.get("test_gates", [])
        require(isinstance(gates, list) and len(gates) <= 16_384, "Invalid independent test gate inventory")
        tasks = set()
        for gate in gates:
            require(isinstance(gate, dict) and set(gate) == {"task", "required_tests"}, "Invalid independent test gate fields")
            task = gate["task"]
            require(isinstance(task, str) and re.fullmatch(r":[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)+", task)
                    and task.rsplit(":", 1)[0] in projects and task not in tasks, "Use unique existing runtime test tasks")
            tasks.add(task)
            tests = gate["required_tests"]
            require(isinstance(tests, list) and tests and len(tests) <= 16_384 and len(tests) == len(set(tests))
                    and all(isinstance(test, str) and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+#[^#\r\n\x00]+", test) for test in tests),
                    "Declare exact independent JUnit class and method bindings")
        if "preflight" in suite:
            control = suite["preflight"]
            require(isinstance(control, dict) and set(control) == {"main", "qualification", "runtime_classes", "controls"}, "Invalid compiled preflight fields")
            classes = [control["main"], *control["runtime_classes"].values()]
            require(set(control["runtime_classes"]) == set(projects)
                    and all(isinstance(value, str) and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", value) for value in classes), "Declare each loaded preflight runtime class and compiled main")
            pin(root, control["qualification"])
            require(isinstance(control["controls"], list) and 0 < len(control["controls"]) <= 16_384, "Declare the whole preflight control inventory")
            identifiers = []
            for item in control["controls"]:
                require(isinstance(item, dict) and set(item) == {"id", "arguments"}
                        and isinstance(item["id"], str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", item["id"]), "Invalid preflight control identity")
                require(isinstance(item["arguments"], list) and len(item["arguments"]) <= 256
                        and all(isinstance(value, str) and len(value) <= 4096 and "\x00" not in value for value in item["arguments"]), "Use bounded compiled control arguments")
                identifiers.append(item["id"])
            require(len(identifiers) == len(set(identifiers)), "Duplicate preflight control identity")
    reports = data.get("owner_reports", [])
    require(isinstance(reports, list) and len(reports) <= 256, "Invalid owner report inventory")
    for report in reports:
        fields = {"name", "project", "template", "kind", "main", "comparison", "workload_id", "phase_keys", "rows", "warmup", "samples", "runtime_classes"}
        require(isinstance(report, dict) and fields <= set(report) <= fields | {"controls", "paired_jmh"}, "Invalid compiled owner report fields")
        require(isinstance(report["name"], str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", report["name"])
                and report["name"] not in names, "Invalid or duplicate owner report name")
        names.add(report["name"])
        kind = report["kind"]
        require(kind in ("java-exec", "test"), "Use an existing JavaExec or Test template")
        require((kind == "java-exec" and FixtureProject.decode(report["project"]) is not None)
                or (kind == "test" and report["project"] in projects), "Owner report template is outside frozen projects")
        require(re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", report["template"]), "Use one existing template task")
        require(all(isinstance(report[key], str) and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", report[key])
                    for key in ("main", "comparison")), "Declare compiled owner report entry points")
        require(isinstance(report["workload_id"], str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", report["workload_id"]), "Invalid owner workload identity")
        keys, rows = report["phase_keys"], report["rows"]
        require(isinstance(keys, list) and keys and len(keys) == len(set(keys))
                and all(isinstance(key, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", key) for key in keys), "Declare owner phase keys")
        require(isinstance(rows, list) and 0 < len(rows) <= 16_384
                and all(isinstance(row, dict) and set(row) == set(keys)
                        and all(isinstance(value, (str, int)) and not isinstance(value, bool) for value in row.values()) for row in rows)
                and len({json.dumps(row, sort_keys=True) for row in rows}) == len(rows), "Declare the complete unique owner row inventory")
        require(all(isinstance(report[key], int) and not isinstance(report[key], bool) for key in ("warmup", "samples"))
                and 0 <= report["warmup"] <= 1_000_000 and 0 < report["samples"] <= 1_000_000, "Invalid owner profile counts")
        controls = report.get("controls", [])
        require(isinstance(controls, list) and len(controls) <= 256 and len(controls) == len(set(controls))
                and all(isinstance(field, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", field) for field in controls), "Invalid raw owner control fields")
        classes = report["runtime_classes"]
        require(isinstance(classes, dict) and classes and len(classes) <= len(projects), "Missing actual owner runtime bindings")
        for module, binding in classes.items():
            require(re.fullmatch(r"[A-Za-z0-9_-]+", module) and isinstance(binding, dict) and set(binding) == {"project", "class"}
                    and binding["project"] in projects and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", binding["class"]),
                    "Invalid actual owner runtime representative")
        require(len({binding["project"] for binding in classes.values()}) == len(classes), "Duplicate owner runtime binding")
        if "paired_jmh" in report:
            paired = report["paired_jmh"]
            require(isinstance(paired, dict) and set(paired) == {"suite", "fixture_class"}
                    and report["kind"] == "java-exec", "Use one declared original JavaExec pairing")
            matches = [suite for suite in data["suites"] if suite["name"] == paired["suite"]]
            require(len(matches) == 1 and matches[0]["project"] == report["project"]
                    and matches[0]["expected_cases"] == len(report["rows"]), "Pair owner rows to one complete JMH suite")
            require(isinstance(paired["fixture_class"], str)
                    and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", paired["fixture_class"])
                    and paired["fixture_class"].rsplit(".", 1)[1] in {name.rsplit(".", 1)[-1] for name in matches[0]["benchmarks"]},
                    "Declare the actual paired compiled fixture class")
    continuation = data.get("owner_continuation")
    if continuation is not None:
        require(isinstance(continuation, dict) and set(continuation) == {"name", "runtime_sha256", "collector_sha256"}, "Invalid complete owner continuation")
        selected = [report for report in reports if report["name"] == continuation["name"]]
        require(len(selected) == 1 and "paired_jmh" not in selected[0], "Continuation requires one complete independent owner domain")
        runtime = continuation["runtime_sha256"]
        require(isinstance(runtime, dict) and set(runtime) == {"baseline", "candidate"}, "Continuation requires both original runtime inventories")
        for values in runtime.values():
            require(isinstance(values, dict) and set(values) == set(projects)
                    and all(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) for value in values.values()), "Incomplete original runtime byte pins")
        require(isinstance(continuation["collector_sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", continuation["collector_sha256"]), "Missing original collector byte pin")
    labels = set()
    for suite in data["suites"]:
        if "operation_reports" in suite:
            operation = suite["operation_reports"]
            require(set(operation) == {"adapters", "warmup", "samples", "expected_rows", "expected_controls"}, "Invalid operation report fields")
            require(suite["benchmarks"] and isinstance(operation["adapters"], dict) and operation["adapters"], "Operation reports require explicit fixtures and required adapters")
            require(all(isinstance(value, str) and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_.$]*", value)
                        for pair in operation["adapters"].items() for value in pair), "Invalid compiled operation adapter identity")
            require(len(set(operation["adapters"].values())) == len(operation["adapters"]), "Duplicate operation adapter")
            for key in ("warmup", "samples", "expected_rows", "expected_controls"):
                require(isinstance(operation[key], int) and not isinstance(operation[key], bool), "Operation counts must be integers")
            require(0 <= operation["warmup"] <= 1_000_000 and 0 < operation["samples"] <= 1_000_000
                    and 0 <= operation["expected_controls"] <= operation["expected_rows"] == suite["expected_cases"], "Incomplete or unbounded operation inventory")
    for value in data["inputs"]:
        require({"label", "pin"} <= set(value) <= {"label", "pin", "layout"} and re.fullmatch(r"[A-Za-z0-9_.-]+", value["label"])
                and value["label"] not in labels, "Invalid or duplicate fixture input")
        labels.add(value["label"])
        pin(root, value["pin"])
    input_paths(data["inputs"], {label for suite in data["suites"] for label in suite.get("external_runtime_inputs", {}).values()})
    bindings = data.get("input_properties", {})
    require(isinstance(bindings, dict) and len(bindings) <= 32, "Invalid frozen inventory input properties")
    for name, sides in bindings.items():
        require(isinstance(name, str) and re.fullmatch(r"strata\.performance\.[a-z][A-Za-z0-9]*Inventory", name),
                "Inventory inputs cannot override workload, sampling or JVM settings")
        require(isinstance(sides, dict) and set(sides) == {"baseline", "candidate"}
                and all(isinstance(label, str) and label in labels for label in sides.values()),
                "Bind both inventory sides to the complete immutable input map")

    for suite in data["suites"]:
        require(set(suite.get("external_runtime_inputs", {}).values()) <= labels, "External runtime input is not registered")
        for label in suite.get("external_runtime_inputs", {}).values():
            registered = next(item for item in data["inputs"] if item["label"] == label)
            require(Path(registered["pin"]["path"]).suffix == ".jar", "External runtime input must be a JAR")
    require(set(data["compiled_pins"]) == {"fixture", "baseline", "candidate"}, "Missing compiled pin inventory")
    for side, pins in data["compiled_pins"].items():
        require(isinstance(pins, list), f"Invalid compiled pin list: {side}")
        paths = [item["path"] for item in pins]
        require(len(paths) == len(set(paths)), "Duplicate compiled pin")
        for item in pins:
            require(set(item) == {"path", "sha256"} and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]), "Invalid compiled pin")
            relative_path(root, item["path"])
    require(isinstance(data["dependency_pins"], list), "Invalid frozen dependency inventory")
    dependency_paths = [item["path"] for item in data["dependency_pins"]]
    require(len(dependency_paths) == len(set(dependency_paths)), "Duplicate frozen dependency")
    for item in data["dependency_pins"]:
        require(set(item) == {"path", "sha256"} and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]), "Invalid dependency pin")
        relative_path(root, item["path"])
    require(isinstance(data["java_pins"], list), "Invalid Java byte inventory")
    roles = set()
    for item in data["java_pins"]:
        require(set(item) == {"role", "executable_sha256", "release_sha256"} and item["role"] in ("toolchain", "launcher") and item["role"] not in roles, "Invalid or duplicate Java role")
        roles.add(item["role"])
        require(all(re.fullmatch(r"[0-9a-f]{64}", item[key]) for key in ("executable_sha256", "release_sha256")), "Invalid Java byte pin")
    return data


def suite_launch_flags(suite, side, inputs):
    """Select the existing compiled entry and byte-fenced archive from immutable registered inputs."""
    flags = []
    if "compiled_main" in suite:
        flags.append("-Pstrata.performance.compiledMain=" + suite["compiled_main"])
    if "external_runtime_inputs" in suite:
        label = suite["external_runtime_inputs"][side]
        require(label in inputs, "External runtime input is not registered")
        item = inputs[label]
        archive = Path(item["path"])
        require(archive.suffix == ".jar" and digest(archive) == item["sha256"], "External runtime archive changed or is not a JAR")
        flags.append("-Pstrata.performance.externalRuntime=" + str(archive))
    return flags


def git(root, *arguments):
    """Use only this job's private source checkouts and preserve command failures."""
    return subprocess.check_output(["git", "-C", str(root), *arguments], text=True, encoding="utf-8").strip()


def source_guard(workspace, tree):
    """Require the complete staged source tree and unchanged raw working files outside the owned Java phase."""
    require(git(workspace, "write-tree") == tree, "Frozen source index changed")
    require((workspace / ".git/info/attributes").read_bytes() == b"* -text\n", "Frozen checkout attributes changed")
    subprocess.run(["git", "-C", str(workspace), "diff", "--exit-code", "--no-ext-diff"], check=True)


def materialize(root, output, side, source, input_root=None):
    """Reconstruct the exact source tree from a fetched commit plus immutable binary patches."""
    workspace = output / "workspaces" / side
    if subprocess.run(["git", "-C", str(root), "cat-file", "-e", source["ref"] + "^{commit}"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0:
        git(root, "fetch", "--no-tags", "origin", source["ref"])
    subprocess.run(["git", "clone", "--quiet", "--shared", "--no-checkout", str(root), str(workspace)], check=True)
    git(workspace, "config", "core.autocrlf", "false")
    git(workspace, "config", "core.longpaths", "true")
    # Preserve frozen blob bytes before the first checkout can convert older text.
    attributes = workspace / ".git/info/attributes"
    attributes.parent.mkdir(parents=True, exist_ok=True)
    attributes.write_bytes(b"* -text\n")
    git(workspace, "checkout", "--quiet", "--detach", source["ref"])
    for patch in source["patches"]:
        subprocess.run(["git", "-C", str(workspace), "apply", "--index", "--binary", str(pin(root if input_root is None else input_root, patch))], check=True)
    require(git(workspace, "write-tree") == source["tree"], "Frozen patched index changed")
    git(workspace, "checkout-index", "--all", "--force")
    source_guard(workspace, source["tree"])
    archive = output / f"source-{side}.zip"
    git(workspace, "archive", "--format=zip", f"--output={archive}", source["tree"])
    return workspace, {"ref": source["ref"], "tree": source["tree"], "archive_sha256": digest(archive), "patches": source["patches"], "raw_checkout_attributes_sha256": digest(attributes)}


def compiled_application_tree(root):
    """Match the existing ArtifactIdentity selected-entry hash before any sampler runs."""
    files = sorted(root.rglob("*"), key=lambda path: path.relative_to(root).as_posix().encode("utf-16-be"))
    require(root.is_dir() and not root.is_symlink() and len(files) <= 16_384
            and all(not path.is_symlink() for path in files), "Unsafe compiled fixture tree")
    selected = [path for path in files if path.is_file() and (path.suffix == ".class"
                or path.relative_to(root).as_posix().startswith(("assets/", "data/")))]
    require(selected and sum(path.stat().st_size for path in selected) <= 64 * 1024**2, "Missing or oversized compiled fixture")
    identity = hashlib.sha256()
    for path in selected:
        name = path.relative_to(root).as_posix().encode("utf-8")
        identity.update(str(len(name)).encode("ascii") + b":" + name + bytes((0,)))
        identity.update(path.read_bytes())
        identity.update(bytes((255,)))
    return identity.hexdigest()


def paired_owner_source_plan(fixture, specification, sources, runtime):
    """Bind both actual prepared source trees and archive inventories to one unchanged compiled fixture."""
    resource = specification["paired_jmh"]["fixture_class"].replace(".", "/") + ".class"
    build = fixture.joinpath(*specification["project"].strip(":").split(":"), "build")
    roots = [build / relative for relative in ("classes/kotlin/jmh", "classes/java/jmh", "jmh-generated-classes")]
    matches = [root for root in roots if root.joinpath(*PurePosixPath(resource).parts).is_file()]
    require(len(matches) == 1, "Missing or shadowed paired compiled fixture")
    rows = []
    for side in ("baseline", "candidate"):
        source = sources[side]
        archives = {label: runtime[side][binding["project"]]["sha256"]
                    for label, binding in specification["runtime_classes"].items()}
        rows.append({"revision": source["tree"], "prepared_source_kind": "Git tree after exact ref and patches",
                     "source_binding": source, "archives": archives})
    require(rows[0]["revision"] != rows[1]["revision"] and rows[0]["archives"] != rows[1]["archives"],
            "Paired sources or actual runtime archives do not distinguish baseline and candidate")
    return {"fixture_revision": sources["fixture"]["tree"], "fixture_source_binding": sources["fixture"],
            "fixture_tree_sha256": compiled_application_tree(matches[0]), "sources": rows}


def paired_owner_arguments(directory, repetition, plan, fixture, expected_rows):
    """Retain the original four arguments and actual JMH library-property origin without changing VM flags."""
    receipt = directory / "receipt.json"
    metadata = read_json(receipt)
    require(metadata["status"] == "passed" and metadata["repetition"] == repetition
            and digest(directory / "results.json") == metadata["results_sha256"], "Original paired JMH receipt changed")
    require(digest(Path(plan["path"])) == plan["sha256"], "Immutable paired source plan changed")
    rows = read_json(directory / "results.json")
    require(len(rows) == expected_rows, "Incomplete original paired JMH results")
    prefix = "-Dstrata.performance.inputs="
    paths = [[value[len(prefix):] for value in row["jvmArgs"] if value.startswith(prefix)] for row in rows]
    require(all(len(value) == 1 and value == paths[0] for value in paths), "Missing or inconsistent actual JMH library-input origin")
    libraries = Path(paths[0][0])
    require(libraries.is_file() and not libraries.is_symlink() and libraries.resolve().is_relative_to(fixture.resolve()),
            "Paired library-input origin escaped the owned fixture")
    return [str(receipt), plan["path"]], libraries


def archive_task(project):
    """Select the already-published JVM archive task without discovering unrelated projects."""
    return project + (":jvmJar" if project in (":api", ":runtime:core", ":performance-testkit") else ":jar")


def archive_file(workspace, project):
    """Require one ordinary binary archive rather than selecting a sources or old release JAR."""
    libraries = workspace.joinpath(*project.strip(":").split(":"), "build", "libs")
    values = [path for path in libraries.glob("*.jar") if not path.name.endswith(("-sources.jar", "-javadoc.jar", "-all.jar", "-plugin.jar"))]
    require(len(values) == 1, f"Expected one current runtime archive: {project}")
    return values[0]


def verify_owner_report(report, specification, repetition, runtime, seen):
    """Validate raw complete rows and actual loaded B/C JARs; processing remains in the shared kit."""
    require(report.get("status") == "passed" and report.get("schema_version") == 1
            and report.get("workload_id") == specification["workload_id"] and report.get("repetition") == repetition,
            "Wrong or incomplete owner report")
    identity = report.get("run_id")
    require(isinstance(identity, str) and re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", identity)
            and identity not in seen, "Missing or reused owner invocation")
    phases = report.get("phases", [])
    keys = specification["phase_keys"]
    actual = [{key: phase.get(key) for key in keys} for phase in phases]
    require(len(actual) == len(specification["rows"])
            and {json.dumps(row, sort_keys=True) for row in actual} == {json.dumps(row, sort_keys=True) for row in specification["rows"]}
            and all(phase.get("samples") == specification["samples"] and phase.get("warmup") == specification["warmup"] for phase in phases),
            "Incomplete or changed owner row/profile inventory")
    require(all(all(field in phase for field in specification.get("controls", [])) for phase in phases), "Missing original untimed owner controls")
    modules = report.get("runtime_metadata", {}).get("modules", [])
    require(len(modules) == len(specification["runtime_classes"]), "Incomplete owner runtime origin inventory")
    loaded = {module.get("module"): module for module in modules}
    require(set(loaded) == set(specification["runtime_classes"]), "Changed owner runtime modules")
    for label, binding in specification["runtime_classes"].items():
        module = loaded[label]
        archive = runtime[binding["project"]]
        resource = binding["class"].replace(".", "/") + ".class"
        with zipfile.ZipFile(archive["path"]) as jar:
            expected = hashlib.sha256(jar.read(resource)).hexdigest()
        require(module.get("status") == "resolved" and module.get("representativeClass") == binding["class"]
                and module.get("codeSource", {}).get("status") == "available" and module["codeSource"].get("sha256") == archive["sha256"]
                and module.get("classResource", {}).get("status") == "available" and module["classResource"].get("sha256") == expected
                and module.get("classTree", {}).get("status") == "available", "Owner report loaded a substituted runtime")
    seen.add(identity)


def class_load_inventory(owner, expected=None):
    """Capture one fixed attempt-owned staging directory without reading aliases or foreign paths."""
    require(owner.is_dir() and not owner.is_symlink(), "Missing owned class-load staging directory")
    files = list(owner.iterdir())
    require(all(path.is_file() and not path.is_symlink() for path in files), "Unsafe staged class-load diagnostic")
    current = {path.name: digest(path) for path in files}
    if expected is not None:
        require(current == expected, "Changed or deleted captured class-load staging bytes")
    return current


def preserve_class_loads(owner, directory, previous):
    """Pin only fresh phase files while rejecting changes or deletion of previously retained raw logs."""
    current = class_load_inventory(owner)
    require(previous.items() <= current.items(), "Changed or deleted previous class-load diagnostic")
    destination = directory / "class-loads"
    require(destination.is_dir() and not destination.is_symlink(), "Missing owned phase class-load directory")
    copied = [{"source": str(owner / name), **preserve_file(owner / name, destination / name)}
              for name in sorted(current.keys() - previous.keys())]
    write_new(directory / "class-load-inventory.json", {"owner": str(owner), "previous": previous, "copied": copied})
    return current


def class_loads(directory):
    """Retain complete per-VM files; PID reuse keeps independent timestamped diagnostics."""
    owner = directory / "class-loads"
    require(owner.is_dir() and not owner.is_symlink(), "Missing owned class-load directory")
    texts, identities = [], {}
    for path in sorted(owner.iterdir()):
        name = re.fullmatch(r"classes-([0-9]+)-[0-9_-]+\.log", path.name)
        require(name is not None and path.is_file() and not path.is_symlink()
                and path.stat().st_size <= 1024 * 1024, "Invalid or oversized class-load diagnostic")
        pid = int(name[1])
        text = path.read_text(encoding="utf-8")
        observed = set(map(int, re.findall(r"(?m)^\[([0-9]+)\]\[info *\]\[class,load *\]", text)))
        require(observed == {pid} and text.endswith("\n"), "Missing, wrong-PID or truncated class-load diagnostic")
        texts.append(text)
        identities[str(path)] = {"path": str(path), "sha256": digest(path), "pid": pid}
    return "".join(texts), identities


def verify_generation_accounting(terminal):
    """Reconcile every closed physical PID/birth history with actual terminal Job accounting."""
    creation_capture = terminal.get("processCreationCapture")
    require(creation_capture in (None, "debug-before-continue-v1", "debug-before-continue-v2"), "Unknown owned process creation capture")
    if creation_capture is not None:
        require(isinstance(terminal.get("processAcquisition"), dict), "Creation capture lacks its physical handle inventory")
    accounting = terminal.get("generationAccounting", {})
    require(isinstance(accounting, dict) and accounting.get("schema") == "strata-owned-generation-accounting-v1"
            and accounting.get("complete") is True and terminal.get("notificationReaderJoined") is True,
            "Missing complete physical generation accounting")
    require(terminal.get("nativeInvoked") is True and terminal.get("naturalRelease") is True and terminal.get("handlesClosed") is True
            and "error" not in terminal and "cleanupError" not in terminal
            and type(terminal.get("activeProcessesAfterCleanup")) is int and terminal["activeProcessesAfterCleanup"] == 0,
            "Generation accounting lacks natural closed terminal proof")
    identities = set()
    for pid, histories in terminal["members"].items():
        require(re.fullmatch(r"[1-9][0-9]*", pid) is not None and isinstance(histories, list) and histories,
                "Invalid physical process histories")
        previous_exit = None
        for member in histories:
            if creation_capture is not None:
                require("creationEventBeforeContinue" not in member or type(member["creationEventBeforeContinue"]) is bool,
                        "Malformed actual creation-event witness")
                if creation_capture == "debug-before-continue-v2":
                    require("exitDuplicateBeforeContinue" not in member or type(member["exitDuplicateBeforeContinue"]) is bool,
                            "Malformed actual pre-continuation exit duplicate")
                    require((member.get("creationEventBeforeContinue") is True) == (member.get("exitDuplicateBeforeContinue") is True),
                            "Creation lease lacks its same-generation duplicate before exit continuation")
            require(type(member.get("pid")) is int and member["pid"] == int(pid) and type(member.get("creationFileTime")) is int
                    and type(member.get("exitFileTime")) is int and member["creationFileTime"] <= member["exitFileTime"]
                    and 0 < member["creationFileTime"]
                    and (previous_exit is None or previous_exit <= member["creationFileTime"])
                    and member.get("inOwnedJob") is True and member.get("completed") is True and member.get("handleClosed") is True
                    and type(member.get("exitCode")) is int and isinstance(member.get("image"), str) and bool(member["image"])
                    and type(member.get("affinityMask")) is int and member["affinityMask"] == terminal["inheritedAffinityMask"]
                    and type(member.get("systemAffinityMask")) is int and member["systemAffinityMask"] == terminal["systemAffinityMask"],
                    "Unclosed or altered physical process generation")
            identity = (int(pid), member["creationFileTime"])
            require(identity not in identities, "Duplicate physical process generation")
            identities.add(identity)
            previous_exit = member["exitFileTime"]
    require(0 < len(identities) and type(terminal.get("totalProcesses")) is int
            and all(type(accounting.get(key)) is int and accounting[key] == len(identities)
                    for key in ("totalProcesses", "capturedGenerations", "closedGenerations"))
            and terminal["totalProcesses"] == len(identities), "Physical histories do not reconcile with actual Job totalProcesses")
    if "processAcquisition" in terminal:
        acquisition = terminal["processAcquisition"]
        require(isinstance(acquisition, dict)
                and all(type(acquisition.get(key)) is int and acquisition[key] == 0
                        for key in ("pending", "metadataInFlight", "physicalHandles", "unpublishedByteBound"))
                and type(acquisition.get("maximumPhysicalHandles")) is int and 0 < acquisition["maximumPhysicalHandles"] <= 256
                and type(acquisition.get("maximumPending")) is int and 0 <= acquisition["maximumPending"] <= acquisition["maximumPhysicalHandles"],
                "Acquired physical handles lack bounded drained ownership")
        published_bound = sum(1024 + 6 * (len(member["image"].encode("utf-16-le")) // 2)
                              for histories in terminal["members"].values() for member in histories)
        require(type(acquisition.get("publishedByteBound")) is int and acquisition["publishedByteBound"] == published_bound
                and type(terminal.get("memberHistorySerializedByteBound")) is int
                and published_bound <= terminal["memberHistorySerializedByteBound"] <= 3 * 1024**2,
                "Acquired physical history exceeds its shared byte bound")
        counters = ("newProcessNotifications", "notificationOpenSuccesses", "notificationOpenFailures", "notificationImageSuccesses",
                    "notificationImageFailures", "notificationMetadataSuccesses", "notificationMetadataFailures")
        delivered = terminal.get("newProcessNotifications")
        acquired = terminal.get("notificationOpenSuccesses")
        acquisition_failures = terminal.get("notificationOpenFailures")
        if creation_capture is not None:
            counters += ("notificationDuplicateSuccesses", "notificationDuplicateFailures")
            acquired = terminal.get("notificationDuplicateSuccesses")
            acquisition_failures = terminal.get("notificationDuplicateFailures")
            delivered = terminal.get("processCreationEvents")
            abi = terminal.get("processCreationAbi", {})
            counts = terminal.get("debugEventCounts", {})
            require(isinstance(abi, dict) and all(type(abi.get(key)) is int for key in ("pointerBytes", "eventBytes", "unionOffset", "firstChanceOffset"))
                    and tuple(abi[key] for key in ("pointerBytes", "eventBytes", "unionOffset", "firstChanceOffset")) in ((8, 176, 16, 152), (4, 96, 12, 80))
                    and isinstance(counts, dict) and all(isinstance(key, str) and re.fullmatch(r"[1-9]", key) and type(value) is int and 0 <= value for key, value in counts.items())
                    and counts.get("3") == counts.get("5") == delivered,
                    "Creation capture lacks its actual ABI or complete native create/exit event counts")
            witnessed = sum(member.get("creationEventBeforeContinue") is True for histories in terminal["members"].values() for member in histories)
            if creation_capture == "debug-before-continue-v2":
                counters += ("creationLeaseTransfers", "exitDuplicateSuccesses", "exitDuplicateFailures")
                require(terminal.get("creationLeaseTransfers") == terminal.get("exitDuplicateSuccesses") == delivered
                        and terminal.get("exitDuplicateFailures") == 0, "Creation leases lack complete owned exit duplicates")
            handle_source = "debug-event-lease-with-exit-duplicate" if creation_capture == "debug-before-continue-v2" else "duplicate-debug-event-handle"
            require(type(delivered) is int and 0 < delivered == witnessed <= terminal["totalProcesses"]
                    and type(terminal.get("creationEventsAfterFailure")) is int and terminal["creationEventsAfterFailure"] == 0
                    and "creationCaptureError" not in terminal
                    and type(acquisition.get("debugProcessHandles")) is int and acquisition["debugProcessHandles"] == 0
                    and terminal.get("processCreationHandleSource") == handle_source
                    and terminal.get("notificationOpenSuccesses") == terminal.get("notificationOpenFailures") == acquisition_failures == 0,
                    "Creation events do not reconcile with actual closed Job generations")
        require(all(type(terminal.get(key)) is int and 0 <= terminal[key] for key in counters)
                and terminal["notificationImageFailures"] == terminal["notificationMetadataFailures"] == 0
                and delivered == acquired + acquisition_failures
                and acquired == terminal["notificationImageSuccesses"] == terminal["notificationMetadataSuccesses"],
                "Acquired process image or metadata lacks complete delivery")


def owned_generation(terminal, pid, vm_start, unique_invocation_pid=False):
    """Select one captured PID/birth generation; legacy single-record receipts remain readable."""
    histories = terminal["members"].get(str(pid), [])
    legacy = isinstance(histories, dict)
    histories = [histories] if legacy else histories
    require(isinstance(histories, list), "Invalid owned process history")
    births, matching, previous_exit = set(), [], None
    for member in histories:
        require(isinstance(member, dict) and member.get("pid", pid if legacy else None) == pid
                and isinstance(member.get("creationFileTime"), int) and not isinstance(member["creationFileTime"], bool), "Altered owned process identity")
        birth = member["creationFileTime"]
        require(birth not in births, "Duplicate owned process generation")
        require(previous_exit is None or previous_exit <= birth, "Overlapping or reordered owned process generations")
        births.add(birth)
        if "exitFileTime" in member:
            require(isinstance(member["exitFileTime"], int) and not isinstance(member["exitFileTime"], bool)
                    and birth <= member["exitFileTime"], "Altered owned process lifetime")
            previous_exit = member["exitFileTime"]
        else:
            require(legacy and len(histories) == 1, "Missing actual process lifetime proof")
        birth_ms = (birth - 116444736000000000) // 10000
        exit_ms = (member["exitFileTime"] - 116444736000000000) // 10000 if "exitFileTime" in member else None
        require(exit_ms is None or birth_ms <= exit_ms, "Altered owned process lifetime")
        if 0 <= vm_start - birth_ms <= 2000 and (exit_ms is None or vm_start <= exit_ms):
            matching.append(member)
    if not matching and unique_invocation_pid and not legacy and len(histories) == 1:
        # A fresh XML PID can bind a unique fully accounted generation without claiming its coarse reconstructed start is kernel birth.
        birth_ms = (histories[0]["creationFileTime"] - 116444736000000000) // 10000
        if vm_start < birth_ms:
            verify_generation_accounting(terminal)
            matching.append(histories[0])
    require(len(matching) == 1, "Missing or ambiguous owned process generation")
    member = matching[0]
    require(member["inOwnedJob"] is True and member["completed"] is True and member["exitCode"] == 0
            and member.get("handleClosed", legacy) is True, "Actual JVM generation or zero-exit proof missing")
    return member


def class_diagnostic(identities, pid, vm_diagnostic, vm_files, terminal=None, unique_invocation_pid=False):
    """Use exact start stamps or uniquely pinned owned generations when logging expands filenames independently."""
    candidates = [item for item in identities.values() if item["pid"] == pid]
    if not candidates:
        return None
    name = re.fullmatch(r"vm-pid" + str(pid) + r"-([0-9_-]+)\.log", vm_diagnostic.name)
    if name is None:
        same_pid = sum(len(re.findall(r"<hotspot_log\b[^>]*\bprocess='" + str(pid) + r"'\s+time_ms='[0-9]+'",
                                     path.read_text(encoding="utf-8"))) for path in vm_files)
        require(len(candidates) == same_pid == 1, "Missing reused-PID VM diagnostic timestamp")
        return candidates[0]
    stamps = {name[1]}
    if terminal is not None:
        starts = re.findall(r"<hotspot_log\b[^>]*\bprocess='" + str(pid) + r"'\s+time_ms='([0-9]+)'",
                            vm_diagnostic.read_text(encoding="utf-8"))
        require(len(starts) == 1, "Ambiguous VM diagnostic start")
        stamps.add(time.strftime("%Y-%m-%d_%H-%M-%S", time.localtime(int(starts[0]) // 1000)))
    matching = [item for item in candidates if Path(item["path"]).name in {f"classes-{pid}-{stamp}.log" for stamp in stamps}]
    require(len(matching) <= 1, "Ambiguous reused-PID class-load diagnostic")
    if matching and Path(matching[0]["path"]).name == f"classes-{pid}-{name[1]}.log":
        return matching[0]
    return unique_class_diagnostic(candidates, pid, vm_diagnostic, vm_files, terminal, unique_invocation_pid)


def unique_class_diagnostic(candidates, pid, vm_diagnostic, vm_files, terminal, unique_invocation_pid=False):
    """Bind an exact header-start file or unique independent expansion to its owned lifetime and fresh pin."""
    same_pid = sum(len(re.findall(r"<hotspot_log\b[^>]*\bprocess='" + str(pid) + r"'\s+time_ms='[0-9]+'",
                                 path.read_text(encoding="utf-8"))) for path in vm_files)
    if terminal is None:
        return None
    raw = vm_diagnostic.read_text(encoding="utf-8")
    starts = re.findall(r"<hotspot_log\b[^>]*\bprocess='" + str(pid) + r"'\s+time_ms='([0-9]+)'", raw)
    require(len(starts) == 1, "Ambiguous unique VM diagnostic")
    stamp = time.strftime("%Y-%m-%d_%H-%M-%S", time.localtime(int(starts[0]) // 1000))
    matching = [item for item in candidates if Path(item["path"]).name == f"classes-{pid}-{stamp}.log"]
    require(len(matching) <= 1, "Ambiguous reused-PID class-load diagnostic")
    if not matching and (len(candidates) != 1 or same_pid != 1):
        return None
    history = terminal["members"].get(str(pid), [])
    if not isinstance(history, list) or not history:
        return None
    helpers = []
    if len(history) != 1:
        if any(not isinstance(item, dict) or not isinstance(item.get("image"), str) or not item["image"] for item in history):
            return None
        java = [item for item in history if Path(item["image"]).name.casefold() in {"java.exe", "javaw.exe"}]
        if not matching and len(java) != 1:
            return None
        helpers = [item for item in history if item not in java]
    member = owned_generation(terminal, pid, int(starts[0]), unique_invocation_pid=unique_invocation_pid and same_pid == 1)
    if helpers:
        homes = re.findall(r"(?m)^java\.home=([^\r\n]+)", raw)
        require(len(homes) == 1 and member in java, "Unique JVM and actual Java generation disagree")
        java_installation_home(member["image"], html.unescape(homes[0]),
                               re.findall(r"(?m)^java\.vm\.specification\.version=([^\r\n]+)", raw))
        for helper in helpers:
            require(Path(helper["image"]).is_absolute() and helper["inOwnedJob"] is True and helper["completed"] is True
                    and helper["handleClosed"] is True and helper["exitCode"] == 0
                    and helper["affinityMask"] == terminal["inheritedAffinityMask"]
                    and helper["systemAffinityMask"] == terminal["systemAffinityMask"], "Unbound or unclosed non-Java helper generation")
    arguments = re.findall(r"<args>\s*([^<]+?)\s*</args>", raw)
    flags = re.findall(r"(?:^|\s)-Xlog:class\+load=info:file=(.+?):pid,level,tags:filecount=0(?=\s|$)",
                       html.unescape(arguments[0]) if len(arguments) == 1 else "")
    require(len(flags) == 1, "Missing unique-generation class-file flag")
    flag = Path(flags[0])
    require(flag.name.count("%p") == flag.name.count("%t") == 1, "Changed unique-generation class-file pattern")
    candidate = matching[0] if matching else candidates[0]
    diagnostic = Path(candidate["path"])
    pattern = re.escape(flag.name).replace("%p", str(pid)).replace("%t", "[0-9_-]+")
    require(re.fullmatch(pattern, diagnostic.name) is not None, "Class-file flag and fresh diagnostic disagree")
    inventory = read_json(diagnostic.parent.parent / "class-load-inventory.json")
    pins = [pin for pin in inventory["copied"] if Path(pin["path"]).resolve() == diagnostic.resolve()]
    require(len(pins) == 1 and diagnostic.name not in inventory["previous"]
            and pins[0]["sha256"] == candidate["sha256"] == digest(diagnostic)
            and Path(pins[0]["source"]).parent.resolve() == Path(inventory["owner"]).resolve()
            and Path(pins[0]["source"]).name == diagnostic.name, "Changed or missing fresh class-file pin")
    parts = list(flag.parent.parts)
    while parts and parts[0] == "..":
        parts.pop(0)
    require(parts and not any(part in ("..", ".") for part in parts), "Ambiguous class-file flag path")
    source_parent = Path(pins[0]["source"]).parent
    if flag.is_absolute():
        require(flag.parent.resolve() == source_parent.resolve(), "Wrong absolute class-file owner")
    else:
        require([os.path.normcase(part) for part in source_parent.parts[-len(parts):]]
                == [os.path.normcase(part) for part in parts], "Wrong relative class-file owner")
    return {**candidate, "unique_generation": member, "actual_file_flag": flags[0],
            "closed_non_java_helper_generations": helpers,
            "relative_path_relationship": "inferred from the verified bounded launcher" if not flag.is_absolute() else None}


def class_file_flags(text):
    """Read complete non-rotating file targets from actual VM arguments, without inventing a working directory."""
    arguments = re.findall(r"<args>\s*([^<]+?)\s*</args>", text)
    require(len(arguments) <= 1, "Ambiguous VM argument block")
    return re.findall(r"(?:^|\s)-Xlog:class\+load=info:file=(.+?):pid,level,tags:filecount=0(?=\s|$)",
                      html.unescape(arguments[0]) if arguments else "")


def independent_class_file(directory, phase_directory, result, vm):
    """Bind an immutable controller artifact to its actual file flag; relative-path correspondence is inferred from executed code."""
    flags = vm.get("class_load_file_flags", [])
    if not flags:
        return None
    diagnostic = Path(vm["diagnostic_path"])
    require(diagnostic.resolve().parent == (directory.parents[2] / "vms").resolve()
            and diagnostic.is_file() and not diagnostic.is_symlink() and digest(diagnostic) == vm["diagnostic_sha256"],
            "Changed independent VM argument diagnostic")
    raw = diagnostic.read_text(encoding="utf-8")
    headers = re.findall(r"<hotspot_log\b[^>]*\bprocess='([0-9]+)'\s+time_ms='([0-9]+)'", raw)
    require(headers == [(str(vm["pid"]), str(vm["vm_start_ms"]))] and class_file_flags(raw) == flags,
            "Changed independent VM file flag or generation")
    matches = []
    for flag in flags:
        target = Path(flag)
        relative = not target.is_absolute()
        parts = list(target.parts)
        while parts and parts[0] == "..":
            parts.pop(0)
        require(parts and not any(part in ("..", ".") for part in parts), "Ambiguous independent class-file target")
        for artifact in result["artifacts"]:
            path = pin(directory, artifact)
            if relative:
                require(1 < len(parts), "Independent relative file flag lacks a unique target path")
                matched = [os.path.normcase(part) for part in path.parts[-len(parts):]] == [os.path.normcase(part) for part in parts]
            else:
                matched = target.resolve() == path.resolve()
            if matched:
                require(path.stat().st_size <= 1024 * 1024, "Independent class-load diagnostic exceeds its retained bound")
                text = path.read_text(encoding="utf-8")
                observed = set(map(int, re.findall(r"(?m)^\[([0-9]+)\]\[info *\]\[class,load *\]", text)))
                require(observed == {vm["pid"]} and text.endswith("\n"), "Wrong-PID or truncated independent class-load diagnostic")
                matches.append({"path": str(path), "sha256": artifact["sha256"], "pid": vm["pid"], "vm_start_ms": vm["vm_start_ms"],
                                "actual_file_flag": flag, "working_directory_observation": None,
                                "relative_path_relationship": "inferred from the verified executed controller's fresh absolute-file/relative-flag contract" if relative else "absolute observed file flag"})
    require(len(matches) <= 1, "Ambiguous independent class-file artifact")
    return matches[0] if matches else None


def java_installation_home(executable, runtime_home, specification_versions=()):
    """Bind a held launcher image to its runtime; Java 8 JDK tools use the nested jre home."""
    executable, runtime_home = Path(executable), Path(runtime_home)
    home = executable.parent.parent
    require(executable.is_absolute() and runtime_home.is_absolute()
            and ".." not in executable.parts and ".." not in runtime_home.parts
            and executable.name.lower() == "java.exe" and executable.parent.name.lower() == "bin",
            "Invalid actual Java launcher path")
    require(os.path.normcase(str(runtime_home)) == os.path.normcase(str(home))
            or list(specification_versions) == ["1.8"]
            and os.path.normcase(str(runtime_home)) == os.path.normcase(str(home / "jre")),
            "Java runtime home differs from its actual launcher installation")
    return home


def verify_vms(directory, files, terminal, allowed, expected_forks=None, maximum_diagnostic_bytes=1024 * 1024, require_all_java_generations=False, daemon_metaspace_mib=1024, java_exec_heap_mib=768, java_exec_metaspace_mib=384):
    """Bind actual last-effective JVM caps to closed diagnostics and captured OS fork generations."""
    require(type(maximum_diagnostic_bytes) is int and 0 < maximum_diagnostic_bytes <= 16 * 1024**2,
            "Invalid finite VM diagnostic byte bound")
    require(type(require_all_java_generations) is bool, "Invalid Java generation coverage mode")
    require(type(daemon_metaspace_mib) is int and 0 < daemon_metaspace_mib <= 1024, "Invalid finite daemon metaspace cap")
    require(type(java_exec_heap_mib) is int and 64 <= java_exec_heap_mib <= 768
            and type(java_exec_metaspace_mib) is int and 0 < java_exec_metaspace_mib <= 384, "Invalid finite JavaExec caps")
    if require_all_java_generations:
        require(terminal.get("naturalRelease") is True and terminal.get("handlesClosed") is True
                and type(terminal.get("activeProcessesAfterCleanup")) is int and terminal["activeProcessesAfterCleanup"] == 0,
                "Java generation coverage requires natural closed terminal proof")
        verify_generation_accounting(terminal)
    pid_log_counts = {}
    for path in files:
        require(path.stat().st_size <= maximum_diagnostic_bytes, "VM diagnostic exceeds its retained bound")
        headers = re.findall(r"<hotspot_log\b[^>]*\bprocess='([0-9]+)'\s+time_ms='([0-9]+)'", path.read_text(encoding="utf-8"))
        require(len(headers) == 1, "VM diagnostic lacks a unique PID header")
        pid = int(headers[0][0])
        pid_log_counts[pid] = pid_log_counts.get(pid, 0) + 1
    profiles = {"wrapper": (64, 256), "daemon": (768, daemon_metaspace_mib), "probe": (32, 256), "generator": (512, 256), "worker": (512, 256), "java_exec": (java_exec_heap_mib, java_exec_metaspace_mib), "fork": (768, 384)}
    console = (directory / "console.log").read_text(encoding="utf-8", errors="replace")
    loaded_classes, class_identities = class_loads(directory)
    origins = [json.loads(line.split("paired.jvm.origin ", 1)[1]) for line in console.splitlines() if "paired.jvm.origin " in line]
    records, forks, version_probes = [], [], []
    observed_generations = []
    for path in files:
        diagnostic_bytes = path.stat().st_size
        require(diagnostic_bytes <= maximum_diagnostic_bytes, "VM diagnostic exceeds its retained bound")
        value = path.read_text(encoding="utf-8")
        headers = re.findall(r"<hotspot_log\b[^>]*\bprocess='([0-9]+)'\s+time_ms='([0-9]+)'", value)
        blocks = re.findall(r"<vm_arguments>([\s\S]*?)</vm_arguments>", value)
        require(len(headers) == len(blocks) == 1 and value.rstrip().endswith("</hotspot_log>")
                and len(re.findall(r"<hotspot_log_done\b[^>]*/>", value)) == 1, "VM diagnostic lacks natural terminal proof")
        commands = re.findall(r"<command>\s*([^<]+?)\s*</command>", blocks[0])
        homes = re.findall(r"(?m)^java\.home=([^\r\n]+)", blocks[0])
        classpaths = re.findall(r"(?m)^java\.class\.path=([^\r\n]*)", blocks[0])
        version_probe = not commands and "<command" not in blocks[0] and classpaths == [""]
        require(len(homes) == len(classpaths) == 1 and (len(commands) == 1 and bool(classpaths[0]) or version_probe), "VM actual main/home/classpath missing")
        command = html.unescape(commands[0].strip()) if commands else None
        main = command.split(" ", 1)[0] if command else None
        if version_probe:
            environment = read_json(directory / "request.json")["environment"]["JAVA_TOOL_OPTIONS"]
            arguments = re.findall(r"<args>\s*([^<]+?)\s*</args>", blocks[0])
            require(environment.startswith("-Xmx32m -XX:MaxMetaspaceSize=256m ") and len(arguments) == 1
                    and html.unescape(arguments[0].strip()) in {environment + " -XX:+UnlockExperimentalVMOptions " + quiet + "-XX:CompileCommand=blackhole,some/fake/Class.method"
                                                for quiet in ("", "-XX:CompileCommand=quiet ")}, "Unbound JMH compiler-blackhole version probe")
            role = "probe"
        elif main.endswith("gradle-wrapper.jar") or main == "org.gradle.wrapper.GradleWrapperMain":
            role = "wrapper"
        elif main == "org.gradle.launcher.daemon.bootstrap.GradleDaemon":
            role = "daemon"
        elif main == "JavaProbe":
            role = "probe"
        elif main == "org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator":
            role = "generator"
        elif main in ("org.gradle.process.internal.worker.GradleWorkerMain", "worker.org.gradle.process.internal.worker.GradleWorkerMain"):
            role = "worker"
        elif main == "org.openjdk.jmh.runner.ForkedMain":
            role = "fork"
        else:
            role = "java_exec"
        caps = {}
        for name, cap in zip(("MaxHeapSize", "MaxMetaspaceSize"), profiles[role]):
            flags = re.findall(r"(?:^|\s)-XX:" + name + r"=([0-9]+)(?=\s|$)", value)
            require(len(flags) == 1 and int(flags[0]) == cap * 1024**2, f"Changed or unavailable actual finite VM cap: {role}/{name}")
            caps[name] = int(flags[0])
        pid, vm_start = map(int, headers[0])
        specifications = re.findall(r"(?m)^java\.vm\.specification\.version=([^\r\n]+)", blocks[0])
        actual_executable = Path(html.unescape(homes[0]), "bin/java.exe")
        if require_all_java_generations or role == "fork":
            member = owned_generation(terminal, pid, vm_start, unique_invocation_pid=require_all_java_generations and pid_log_counts[pid] == 1)
            actual_executable = Path(member["image"])
            java_installation_home(actual_executable, html.unescape(homes[0]), specifications)
        require(os.path.normcase(str(actual_executable)) in allowed and digest(actual_executable) == allowed[os.path.normcase(str(actual_executable))], "Unknown or changed Java executable")
        if role == "java_exec":
            require(any(origin["main"] == main and os.path.normcase(origin["executable"]) == os.path.normcase(str(actual_executable))
                        and origin["classpath"] == html.unescape(classpaths[0]) for origin in origins), "Unbound actual JavaExec origin")
        evidence = {"pid": pid, "vm_start_ms": vm_start, "role": role, "command": command, "caps": caps, "classpath": html.unescape(classpaths[0]), "home": html.unescape(homes[0]), "executable": str(actual_executable), "diagnostic_sha256": digest(path), "diagnostic_bytes": diagnostic_bytes, "diagnostic_byte_bound": maximum_diagnostic_bytes}
        evidence["vm_specification_versions"] = specifications
        if require_all_java_generations:
            require(os.path.normcase(member["image"]) == os.path.normcase(str(actual_executable))
                    and member["affinityMask"] == terminal["inheritedAffinityMask"]
                    and member["systemAffinityMask"] == terminal["systemAffinityMask"], "Java diagnostic generation changed image or affinity")
            evidence["os_generation"] = member
            delta = vm_start - (member["creationFileTime"] - 116444736000000000) // 10000
            evidence["vm_start_minus_creation_ms"] = delta
            evidence["binding_basis"] = "unique_owned_pid_in_complete_invocation" if delta < 0 else "timestamp_within_captured_lifetime"
            observed_generations.append((pid, member["creationFileTime"]))
        flags = class_file_flags(value)
        if flags:
            evidence.update(diagnostic_path=str(path), class_load_file_flags=flags)
        if version_probe:
            evidence["version_probe_arguments"] = html.unescape(arguments[0].strip())
            version_probes.append(evidence)
        diagnostic = class_diagnostic(class_identities, pid, path, files, terminal, unique_invocation_pid=require_all_java_generations)
        if diagnostic is not None:
            evidence["class_load_diagnostic"] = diagnostic
            if "unique_generation" in diagnostic:
                member = diagnostic["unique_generation"]
                require(os.path.normcase(member["image"]) == os.path.normcase(str(actual_executable))
                        and member["affinityMask"] == terminal["inheritedAffinityMask"]
                        and member["systemAffinityMask"] == terminal["systemAffinityMask"], "Unique JVM changed executable or affinity")
                evidence["os_generation"] = member
        if role == "fork":
            member = owned_generation(terminal, pid, vm_start)
            require(os.path.normcase(member["image"]) == os.path.normcase(str(actual_executable)), "JMH fork OS generation and diagnostic disagree")
            require(member["affinityMask"] == terminal["inheritedAffinityMask"] and member["systemAffinityMask"] == terminal["systemAffinityMask"], "JMH fork changed inherited affinity")
            require(diagnostic is not None, "PID-qualified JMH fork main origin missing or ambiguous")
            loaded = re.findall(r"(?m)^\s*\[" + str(pid) + r"\]\[info *\]\[class,load *\]\s+org\.openjdk\.jmh\.runner\.ForkedMain source: ([^\r\n]+)", Path(diagnostic["path"]).read_text(encoding="utf-8"))
            require(len(loaded) == 1, "PID-qualified JMH fork main origin missing or ambiguous")
            evidence["actual_loaded_main"] = loaded[0].strip()
            evidence["os_generation"] = member
            forks.append((pid, member["creationFileTime"]))
        records.append(evidence)
    for probe in version_probes:
        authorities = [record for record in records if record["role"] == "java_exec" and record["home"] == probe["home"] and "class_load_diagnostic" in record]
        require(any(re.search(r"(?m)^\s*\[" + str(record["pid"]) + r"\]\[info *\]\[class,load *\]\s+org\.openjdk\.jmh\.runner\.CompilerHints source: [^\r\n]+",
                              Path(record["class_load_diagnostic"]["path"]).read_text(encoding="utf-8"))
                    for record in authorities), "JMH version probe lacks a bound JavaExec compiler-hints origin")
    require(files and len(forks) == len(set(forks)), "Missing JVM diagnostics or duplicate JMH fork generation")
    if require_all_java_generations:
        captured_generations = set()
        for pid, histories in terminal["members"].items():
            require(re.fullmatch(r"[1-9][0-9]*", pid) is not None and isinstance(histories, list),
                    "Complete Java coverage requires explicit process histories")
            for member in histories:
                if Path(member["image"]).name.lower() in ("java.exe", "javaw.exe"):
                    require(os.path.normcase(member["image"]) in allowed, "Unknown owned Java executable")
                    identity = (int(pid), member["creationFileTime"])
                    require(identity not in captured_generations, "Duplicate captured Java generation")
                    captured_generations.add(identity)
        require(len(observed_generations) == len(set(observed_generations))
                and set(observed_generations) == captured_generations, "Missing or duplicate owned Java generation diagnostic")
    if expected_forks is not None:
        require(len(forks) == expected_forks, "Incomplete actual JMH fork ownership/caps/affinity matrix")
    captured = [record["class_load_diagnostic"]["path"] for record in records if "class_load_diagnostic" in record]
    require(set(captured) == set(class_identities) and len(captured) == len(set(captured)), "Unbound or duplicate class-load JVM identity")
    write_new(directory / "vms.json", records)


def properties(path, values):
    """Write Java Properties without losing project colons or Windows path separators."""
    def escape(value):
        return value.replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=").replace(" ", "\\ ")
    with path.open("x", encoding="utf-8") as target:
        for key, value in sorted(values.items()):
            target.write(f"{escape(key)}={escape(value)}\n")


def input_property_files(output, data, archives):
    """Bind only declared frozen inventory files; preserve both sides in the common input closure."""
    bindings = data.get("input_properties", {})
    if not bindings:
        return {}
    files = {}
    for side in ("baseline", "candidate"):
        entries = {name: archives[sides[side]] for name, sides in bindings.items()}
        require(all(digest(Path(item["path"])) == item["sha256"] for item in entries.values()),
                "Frozen inventory input bytes changed")
        path = output / f"input-properties-{side}.json"
        write_new(path, {"schema": "strata-paired-jvm-input-properties-v1", "entries": entries})
        files[side] = {"path": path, "sha256": digest(path)}
    return files


def verify_input_property_files(files):
    """Guard the complete immutable selector/input closure, including the currently unselected side."""
    for item in files.values():
        require(digest(item["path"]) == item["sha256"], "Frozen inventory selector changed")
        for archive in read_json(item["path"])["entries"].values():
            require(digest(Path(archive["path"])) == archive["sha256"], "Frozen inventory bytes changed")


def verify_input_properties(directory, selected, vm_files):
    """Require every actual JavaExec to apply every pinned declared property, with no ignored selector."""
    entries = read_json(selected["path"])["entries"]
    console = (directory / "console.log").read_text(encoding="utf-8-sig", errors="replace")
    origins = [json.loads(row) for row in re.findall(r"(?m)^paired\.jvm\.origin (.+)$", console)]
    records = [json.loads(row) for row in re.findall(r"(?m)^paired\.jvm\.inputProperty (.+)$", console)]
    tasks = [origin["task"] for origin in origins]
    require(tasks and len(tasks) == len(set(tasks)), "Inventory inputs have no unique executed JavaExec consumer")
    expected = [{"task": task, "property": name, **item} for task in tasks for name, item in entries.items()]
    require(len(records) == len(expected) and sorted(records, key=lambda item: (item.get("task", ""), item.get("property", "")))
            == sorted(expected, key=lambda item: (item["task"], item["property"])),
            "Inventory input was missing, unknown, altered or unconsumed by its launcher")
    verified = [vm for vm in read_json(directory / "vms.json") if vm["role"] == "java_exec"]
    require(len(verified) == len(tasks), "Inventory input lacks each actual JavaExec diagnostic")
    diagnostics = {digest(path): path for path in vm_files}
    for vm in verified:
        require(vm["diagnostic_sha256"] in diagnostics, "Inventory JVM diagnostic bytes changed")
        raw = diagnostics[vm["diagnostic_sha256"]].read_text(encoding="utf-8")
        arguments = re.findall(r"<vm_arguments>([\s\S]*?)</vm_arguments>", raw)
        properties = re.findall(r"<properties>([\s\S]*?)</properties>", arguments[0]) if len(arguments) == 1 else []
        require(len(properties) == 1, "Inventory input lacks actual JVM properties")
        raw = html.unescape(properties[0])
        for name, item in entries.items():
            values = re.findall(r"(?m)^" + re.escape(name) + r"=([^\r\n]+)", raw)
            require(len(values) == 1 and os.path.normcase(os.path.normpath(values[0]))
                    == os.path.normcase(os.path.normpath(item["path"])),
                    "Inventory property was missing, changed or overwritten in the actual JVM")
    write_new(directory / "input-properties-verified.json", {"selector_sha256": selected["sha256"], "applied": records})


def path_identity(path):
    """Hash an entire bounded classpath directory or archive, including resources and control bytes."""
    if path.is_file():
        require(path.stat().st_size <= 64 * 1024**2, "Prepared classpath archive exceeds its byte bound")
        return digest(path)
    require(path.is_dir() and not path.is_symlink(), "Missing prepared classpath directory")
    files = sorted(path.rglob("*"), key=lambda item: item.relative_to(path).as_posix().encode("utf-16-be"))
    require(len(files) <= 16_384 and all(not item.is_symlink() for item in files), "Unsafe or oversized prepared classpath tree")
    files = [item for item in files if item.is_file()]
    require(sum(item.stat().st_size for item in files) <= 64 * 1024**2, "Prepared classpath tree exceeds its byte bound")
    identity = hashlib.sha256()
    for item in files:
        identity.update(f"{item.relative_to(path).as_posix()}={digest(item)}\n".encode("utf-8"))
    return identity.hexdigest()


def preserve_file(source, destination):
    """Retain immutable bytes before any control or timing can fail, checking both sides of the copy."""
    identity = path_identity(source)
    require(not destination.exists(), "Prepared evidence already exists")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    require(digest(source) == digest(destination) == identity, "Prepared archive changed during preservation")
    return {"path": destination.as_posix(), "sha256": identity}


def preserve_inputs(root, owner, inputs, external_inputs=()):
    """Preserve only declared pinned files at their frozen logical destinations, rejecting link redirection."""
    paths = input_paths(inputs, external_inputs)
    result = {}
    for item in inputs:
        destination = relative_path(owner, paths[item["label"]].as_posix())
        for path in (destination, *destination.parents):
            try:
                status = path.lstat()
            except FileNotFoundError:
                status = None
            require(status is None or (not stat.S_ISLNK(status.st_mode) and not (getattr(status, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT)),
                    "Linked prepared input path")
            if path == owner:
                break
        result[item["label"]] = preserve_file(pin(root, item["pin"]), destination)
    expected = {owner.joinpath(*path.parts) for path in paths.values()}
    expected |= {parent for path in tuple(expected) for parent in path.parents if parent != owner and parent.is_relative_to(owner)}
    require(set(owner.rglob("*")) == expected, "Incomplete or undeclared prepared input tree")
    return result


def prepared_classpath(path):
    """Verify every retained classpath byte before reuse; a fresh hash cannot replace its captured pin."""
    inventory = read_json(path)
    require(set(inventory) == {"schema", "entries"} and inventory["schema"] == "strata-paired-jvm-classpath-v1"
            and isinstance(inventory["entries"], list) and 0 < len(inventory["entries"]) <= 256, "Invalid prepared classpath inventory")
    paths, sources = set(), set()
    for entry in inventory["entries"]:
        require(set(entry) == {"source", "path", "sha256"} and re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]), "Invalid prepared classpath pin")
        retained = Path(entry["path"])
        require(retained.is_absolute() and retained.resolve().is_relative_to(path.parent.resolve()), "Prepared classpath escapes its retained owner")
        require(entry["path"] not in paths and entry["source"] not in sources, "Duplicate prepared classpath origin")
        paths.add(entry["path"])
        sources.add(entry["source"])
        require(path_identity(retained) == entry["sha256"] and path_identity(Path(entry["source"])) == entry["sha256"], "Prepared classpath bytes changed")
    return inventory


def class_entry(classpath, name):
    """Require a unique compiled class source rather than allowing shadowed control/runtime classes."""
    resource = name.replace(".", "/") + ".class"
    matches = []
    for entry in classpath["entries"]:
        path = Path(entry["path"])
        if path.is_dir():
            present = path.joinpath(*PurePosixPath(resource).parts).is_file()
        else:
            with zipfile.ZipFile(path) as archive:
                present = resource in archive.namelist()
        if present:
            matches.append(entry)
    require(len(matches) == 1, f"Missing or shadowed compiled preflight class: {name}")
    return matches[0]



def owner_processing_collector(classpath, collector):
    """Use the actual retained processing JAR path after proving the original collector bytes."""
    selected = class_entry(prepared_classpath(classpath), "dev.s7a.strata.performance.JvmPerformanceMeter")
    require(selected["sha256"] == digest(collector), "Selected owner comparison collector bytes changed")
    return selected["path"]


def preflight_request(input_root, output, suite, side, classpath_path, runtime, sources, java, inputs):
    """Bind one complete untimed controller request to the actual preserved and qualified input closure."""
    specification = suite["preflight"]
    classpath = prepared_classpath(classpath_path)
    classes = {name: class_entry(classpath, name) for name in [specification["main"],
               "dev.s7a.strata.performance.JvmPerformanceMeter", "org.openjdk.jmh.runner.Runner", *specification["runtime_classes"].values()]}
    for project, name in specification["runtime_classes"].items():
        require(classes[name]["sha256"] == runtime[project]["sha256"], "Preflight class resolves outside its actual runtime archive")
    directory = output / "preflight" / suite["name"] / side
    directory.mkdir(parents=True, exist_ok=False)
    qualification = preserve_file(pin(input_root, specification["qualification"]), directory / "qualification.json")
    request = {"schema": "strata-paired-jvm-preflight-request-v1", "project": suite["project"], "suite": suite["name"], "side": side,
               "main": specification["main"], "controls": specification["controls"], "qualification": qualification,
               "classpath": classpath, "required_classes": classes, "runtime": runtime, "sources": sources,
               "java": java, "inputs": inputs, "output": directory.as_posix()}
    write_new(directory / "request.json", request)
    return directory


def verify_preflight(directory, phase_directory, terminal, expected_request_sha256):
    """Fail closed on incomplete controls, changed bytes, wrong actual loaded origins or unowned JVMs."""
    request_path = directory / "request.json"
    require(digest(request_path) == expected_request_sha256, "Control changed its pinned request bytes")
    request = read_json(request_path)
    receipt = read_json(directory / "receipt.json")
    require(set(receipt) == {"schema", "status", "request_sha256", "controls"}
            and receipt["schema"] == "strata-paired-jvm-preflight-receipt-v1" and receipt["status"] == "passed"
            and receipt["request_sha256"] == digest(request_path), "Missing or wrong-identity qualified preflight receipt")
    expected = [item["id"] for item in request["controls"]]
    require(isinstance(receipt["controls"], list) and [item.get("id") for item in receipt["controls"]] == expected,
            "Incomplete or reordered whole preflight control inventory")
    console = (phase_directory / "console.log").read_text(encoding="utf-8", errors="replace")
    loaded_classes, class_identities = class_loads(phase_directory)
    vms = read_json(phase_directory / "vms.json")
    captured_class_identities = {vm["class_load_diagnostic"]["path"]:
                                 {"pid": vm["pid"], "path": vm["class_load_diagnostic"]["path"], "sha256": vm["class_load_diagnostic"]["sha256"]}
                                 for vm in vms if "class_load_diagnostic" in vm}
    require(class_identities == captured_class_identities, "Control changed its captured class-load diagnostic")
    artifacts, independent_files = set(), {}
    for result in receipt["controls"]:
        require(set(result) == {"id", "status", "observations", "origins", "artifacts"} and result["status"] == "passed"
                and isinstance(result["observations"], dict) and result["observations"], "Failed or unavailable preflight control observations")
        require(isinstance(result["origins"], dict) and set(result["origins"]) == set(request["required_classes"]), "Missing actual control class origins")
        for name, origin in result["origins"].items():
            require(isinstance(origin, dict) and set(origin) == {"pid"} and isinstance(origin["pid"], int)
                    and not isinstance(origin["pid"], bool), "Invalid actual control JVM identity")
            pid = origin["pid"]
            matching = [vm for vm in vms if vm["pid"] == pid and vm["role"] == "java_exec"]
            bound = [(vm, independent_class_file(directory, phase_directory, result, vm)) for vm in matching]
            bound = [(vm, diagnostic) for vm, diagnostic in bound if diagnostic is not None]
            require(len(bound) <= 1, "Ambiguous independent control file generation")
            if bound:
                matching = [bound[0][0]]
            require(len(matching) == 1, "Missing or ambiguous actual control JVM identity")
            vm = matching[0]
            member = owned_generation(terminal, pid, vm["vm_start_ms"])
            require(vm is not None and vm["role"] == "java_exec" and member is not None
                    and member["inOwnedJob"] is True and member["completed"] is True and member["exitCode"] == 0
                    and member["affinityMask"] == terminal["inheritedAffinityMask"] and member["systemAffinityMask"] == terminal["systemAffinityMask"],
                    "Preflight observation lacks naturally released owning JVM proof")
            require(os.path.normcase(member["image"]) == os.path.normcase(vm.get("executable", str(Path(vm["home"], "bin/java.exe")))), "Control loaded origin and OS executable disagree")
            if "executable" in vm:
                java_installation_home(member["image"], vm["home"], vm["vm_specification_versions"])
            birth_ms = (member["creationFileTime"] - 116444736000000000) // 10000
            require(0 <= vm["vm_start_ms"] - birth_ms <= 2000, "Control OS generation and diagnostic disagree")
            pattern = r"(?m)^\s*\[" + str(pid) + r"\]\[info *\]\[class,load *\]\s+" + re.escape(name) + r" source: ([^\r\n]+)"
            diagnostic = bound[0][1] if bound else vm.get("class_load_diagnostic")
            if bound:
                independent_files[diagnostic["path"]] = diagnostic
            vm_classes = Path(diagnostic["path"]).read_text(encoding="utf-8") if diagnostic is not None else ""
            require(diagnostic is not None or len([item for item in vms if item["pid"] == pid]) == 1,
                    "Ambiguous independent control process generation")
            loaded = re.findall(pattern, vm_classes)
            observed = re.findall(pattern, console)
            require((len(loaded) == 1 and (not observed or observed == loaded or
                                        bound and all(line == loaded[0] for line in observed) and len(observed) == len([item for item in vms if item["pid"] == pid])))
                    or (not loaded and len(observed) == 1 and len([item for item in vms if item["pid"] == pid]) == 1),
                    "Missing or ambiguous PID-qualified control class load")
            loaded = loaded or observed
            location = urlparse(loaded[0].strip())
            require(location.scheme == "file" and not location.netloc, "Control loaded class from a non-local origin")
            actual_path = unquote(location.path)
            if re.match(r"^/[A-Za-z]:/", actual_path):
                actual_path = actual_path[1:]
            expected_path = Path(request["required_classes"][name]["path"])
            require(os.path.normcase(str(Path(actual_path).resolve())) == os.path.normcase(str(expected_path.resolve())), "Control loaded class from the wrong prepared archive")
        require(isinstance(result["artifacts"], list) and result["artifacts"], "Missing actual preflight observation artifacts")
        for artifact in result["artifacts"]:
            require(set(artifact) == {"path", "sha256"}, "Invalid control observation artifact")
            path = pin(directory, artifact)
            require(path.stat().st_size <= 64 * 1024**2 and artifact["path"] not in artifacts
                    and artifact["path"] not in ("receipt.json", "request.json", "qualification.json"), "Unsafe or duplicate observation artifact")
            artifacts.add(artifact["path"])
    for entry in request["classpath"]["entries"]:
        require(path_identity(Path(entry["path"])) == entry["sha256"] and path_identity(Path(entry["source"])) == entry["sha256"], "Control changed prepared or original classpath bytes")
    require(digest(Path(request["qualification"]["path"])) == request["qualification"]["sha256"], "Control qualification bytes changed")
    for entry in request["inputs"].values():
        require(digest(Path(entry["path"])) == entry["sha256"], "Control changed frozen input bytes")
    write_new(directory / "verified.json", {"status": "passed", "request_sha256": digest(request_path), "receipt_sha256": digest(directory / "receipt.json"), "controls": expected,
                                          "independent_class_load_files": list(independent_files.values())})


def preserve_test_reports(workspace, directory, gate):
    """Retain all actual JUnit bytes even when the owning test phase fails before admission."""
    reports = workspace.joinpath(*gate["task"].strip(":").split(":")[:-1], "build", "test-results", gate["task"].rsplit(":", 1)[1])
    require(not reports.is_symlink(), "Unsafe independent JUnit output")
    files = sorted(reports.glob("TEST-*.xml")) if reports.is_dir() else []
    require(len(files) <= 16_384 and all(path.is_file() and not path.is_symlink() for path in files)
            and sum(path.stat().st_size for path in files) <= 64 * 1024**2, "Unsafe or oversized independent JUnit reports")
    artifacts = [{"name": report.name, **preserve_file(report, directory / "junit" / report.name)} for report in files]
    write_new(directory / "junit-inventory.json", {"task": gate["task"], "reports": artifacts})


def verify_test_gate(directory, gate):
    """Require complete actual JUnit counts and each exact independent method to run successfully."""
    inventory = read_json(directory / "junit-inventory.json")
    require(inventory["task"] == gate["task"] and inventory["reports"], "Missing actual independent JUnit output")
    observed, counts = {}, {name: 0 for name in ("tests", "failures", "errors", "skipped")}
    for artifact in inventory["reports"]:
        report = Path(artifact["path"])
        require(report.resolve().parent == (directory / "junit").resolve() and digest(report) == artifact["sha256"], "Changed independent JUnit report")
        suite = ElementTree.fromstring(report.read_bytes())
        require(suite.tag == "testsuite", "Invalid independent JUnit report root")
        actual = {name: 0 for name in counts}
        for case in suite.findall("testcase"):
            identity = case.get("classname", "") + "#" + case.get("name", "").removesuffix("[jvm]").removesuffix("()")
            actual["tests"] += 1
            for name, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                actual[name] += int(case.find(tag) is not None)
            observed.setdefault(identity, []).append(all(case.find(tag) is None for tag in ("failure", "error", "skipped")))
        for name in counts:
            value = suite.get(name)
            require(value is not None and re.fullmatch(r"[0-9]+", value) and int(value) == actual[name], "Inconsistent actual JUnit counts")
            counts[name] += actual[name]
    require(0 < counts["tests"] and counts["failures"] == counts["errors"] == 0, "Failed independent JUnit gate")
    require(all(observed.get(test) == [True] for test in gate["required_tests"]), "Required independent JUnit method missing, repeated, failed or skipped")
    write_new(directory / "junit-verified.json", {"status": "passed", "task": gate["task"], "required_tests": gate["required_tests"],
                                                "counts": counts, "reports": inventory["reports"]})


def test_classpath_preparation(data):
    """Resolve every declared complete Test gate's libraries before validating frozen dependency pins."""
    tasks = sorted({gate["task"] for suite in data["suites"] for gate in suite.get("test_gates", [])})
    return [":strataPairedPrepareTestClasspaths", "-Pstrata.paired.prepareTestClasspaths=" + ",".join(tasks)] if tasks else []


def fixture_projects(data):
    """Keep every declared compiled fixture project prepared, including owner-only continuations."""
    paths = [pin["path"] for pins in data["compiled_pins"].values() for pin in pins]
    pinned = {project.value[0] for project in FixtureProject
              if any(path.startswith(project.value[0].strip(":").replace(":", "/") + "/build/") for path in paths)}
    return sorted({suite["project"] for suite in data["suites"]} | pinned)


def pinned_fixture_preparation(data, side, fixture_tasks):
    """Build declared collector and fixture outputs on each runtime side before checking their pins."""
    pins = [item["path"] for item in data["compiled_pins"][side]]
    tasks = []
    for task in fixture_tasks:
        project, name = task.rsplit(":", 1)
        root = project.strip(":").replace(":", "/") + "/build/"
        prefixes = [root + suffix for suffix in ("classes/kotlin/test/", "classes/java/test/", "resources/test/")] if name == "testClasses" else [root]
        if any(path.startswith(prefix) for path in pins for prefix in prefixes):
            tasks.append(task)
    return tasks


def verify_jmh_completion(data, invocation_ids, comparisons, completed):
    """Require every declared mode's complete ordered B0/C0/B1/C1/B2/C2 sequence and comparison."""
    modes = [(suite["name"], mode) for suite in data["suites"] for mode in suite.get("modes", ["avgt", "sample"])]
    expected = [(name, mode, side, repetition) for name, mode in modes
                for repetition in range(3) for side in ("baseline", "candidate")]
    require(len(invocation_ids) == len(modes) * 6, "Whole attempt invocation count is incomplete")
    require(len(comparisons) == len(modes), "Whole attempt comparison count is incomplete")
    require([tuple(item[:4]) for item in completed] == expected, "Whole attempt collection sequence is missing, repeated or reordered")
    require(len(completed) == len(invocation_ids) and {item[4] for item in completed} == invocation_ids,
            "Whole attempt receipt identities are missing, repeated or changed")
    require([(name, mode) for name, mode, _ in comparisons] == modes, "Whole attempt comparisons are missing, repeated or reordered")


def table(output, comparisons, phases, operations=(), owners=()):
    """Render the existing processor's medians and deltas without new aggregation or timing gates."""
    rows = []
    for suite, mode, comparison in comparisons:
        for case in comparison["cases"]:
            units = {"primary_score_median": case["primary_unit"], "allocation_bytes_per_operation_median": "B/op", "gc_count_median": "counts", "gc_time_ms_median_available": "ms"}
            for metric, value in case["metrics"].items():
                rows.append({"suite": suite, "mode": mode, "benchmark": case["benchmark"], "params": json.dumps(case["params"], sort_keys=True),
                             "metric": metric, "unit": units.get(metric, ""), **value})
            for metric, value in case.get("secondary_metrics", {}).items():
                rows.append({"suite": suite, "mode": mode, "benchmark": case["benchmark"], "params": json.dumps(case["params"], sort_keys=True),
                             "metric": "jmh_secondary." + metric, **value})
            for metric, before in case.get("per_run_percentile_medians", {}).get("baseline", {}).items():
                after = case["per_run_percentile_medians"]["candidate"][metric]
                rows.append({"suite": suite, "mode": mode, "benchmark": case["benchmark"], "params": json.dumps(case["params"], sort_keys=True),
                             "metric": f"sample_percentile_{metric}", "unit": case["primary_unit"], "baseline": before, "candidate": after, "delta": after-before,
                             "candidate_to_baseline": after / before if before != 0 else None})
    for suite, comparison in operations:
        for phase in comparison["phases"]:
            for metric, before in phase["before"].items():
                unit = "B/sample" if metric.endswith("bytes_per_sample") else "B" if metric.endswith("bytes") else "ns/sample" if metric.endswith("ns_per_sample") else "ns"
                rows.append({"suite": suite, "mode": "owner-thread-operation", "benchmark": phase["operation_id"], "params": "{}",
                             "metric": metric, "unit": unit, "baseline": before, "candidate": phase["after"][metric],
                             "delta": phase["delta"][metric], "candidate_to_baseline": None})
    for specification, comparison in owners:
        for phase in comparison["phases"]:
            identity = {key: phase[key] for key in specification["phase_keys"]}
            for metric, before in phase["before"].items():
                unit = "B/cycle" if metric.endswith("bytes_per_cycle") else "ns/cycle" if metric.endswith("ns_per_cycle") else ""
                rows.append({"suite": specification["name"], "mode": "owner-thread-report", "benchmark": specification["workload_id"],
                             "params": json.dumps(identity, sort_keys=True), "metric": metric, "unit": unit,
                             "baseline": before, "candidate": phase["after"][metric], "delta": phase["delta"][metric], "candidate_to_baseline": None})
    with (output / "table.csv").open("x", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    with (output / "report.md").open("x", encoding="utf-8") as target:
        target.write("# Paired JVM evidence\n\nAll values are from this attempt on the same hosted VM. Time is per declared fixture batch; no wall-time pass threshold is applied.\n\n")
        target.write("| Suite/mode/case | Metric | Unit | Baseline | Candidate | Delta |\n| --- | --- | --- | ---: | ---: | ---: |\n")
        for row in rows:
            values = ["unavailable" if row[key] is None else f"{row[key]:.9g}" for key in ("baseline", "candidate", "delta")]
            target.write(f"| {row['suite']}/{row['mode']}/{row['benchmark']} {row['params']} | {row['metric']} | {row['unit']} | {' | '.join(values)} |\n")
        target.write("\n| Phase | Seconds |\n| --- | ---: |\n")
        for phase in phases:
            target.write(f"| {phase['name']} | {phase['elapsed_seconds']:.3f} |\n")
        target.write("\nJMH elapsed and stock GC allocation retain their harness scope. Optional owner-thread operation CPU/allocation use separate compiled reports and declared sample boundaries; absent or unsupported counters stay unavailable. Job/process/all-thread CPU and allocation include broader work. Heap-boundary samples do not prove retained-object size. Meter counter reads and adapter dispatch have collection cost; batching is declared in raw rows. Additional JMH profiler metrics remain in raw results. CPU native font-library controls require their full work gate and actual library load/release; GPU/window/upload/input-latency/FPS and loaded-game acceptance is N/A for this CPU job. Cross-environment timing is not a runtime improvement percentage.\n")


def pack(output):
    """Upload every evidence byte in one archive, excluding reusable intermediate workspaces."""
    archive = output.parent / (output.name + ".zip")
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as target:
        for path in sorted(output.rglob("*")):
            if path.is_file() and path.relative_to(output).parts[0] != "workspaces":
                require(not path.is_symlink(), "Evidence contains a symlink")
                target.write(path, path.relative_to(output).as_posix())
    return archive


def invoke(arguments, root, console):
    """Drain bounded diagnostics and close the owning helper on overflow or a missing terminal."""
    overflow = threading.Event()
    process = subprocess.Popen(arguments, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    def drain():
        retained = 0
        with console.open("xb") as target:
            while chunk := process.stdout.read(65536):
                available = max(0, 16 * 1024**2 - retained)
                target.write(chunk[:available])
                retained += len(chunk)
                if retained > 16 * 1024**2:
                    overflow.set()
    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    deadline = time.monotonic() + 1860
    try:
        while process.poll() is None and not overflow.is_set() and time.monotonic() < deadline:
            time.sleep(0.1)
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=30)
        reader.join(timeout=30)
        require(not reader.is_alive() and not overflow.is_set() and time.monotonic() < deadline, "Invocation exceeded diagnostic or outer cleanup bound")
        return process.returncode
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=30)
        process.stdout.close()



def continuation_scope(data, owner_report):
    """Select one entire independent owner domain while retaining the complete outer frozen manifest."""
    configured = data.get("owner_continuation", {}).get("name")
    require(owner_report is None or configured is None or owner_report == configured, "Explicit owner domain differs from the frozen continuation")
    owner_report = configured if owner_report is None else owner_report
    if owner_report is None:
        return data, {"kind": "whole-manifest", "whole_issue_acceptance": False}
    matches = [report for report in data.get("owner_reports", []) if report["name"] == owner_report]
    require(len(matches) == 1 and "paired_jmh" not in matches[0], "Select one complete independent owner report")
    return {**data, "suites": [], "owner_reports": matches}, {
        "kind": "complete-owner-domain-continuation", "owner_report": owner_report,
        "complete_rows": len(matches[0]["rows"]), "whole_issue_acceptance": False,
        "original_jmh_suites": [{"name": suite["name"], "complete_rows": suite["expected_cases"]} for suite in data["suites"]],
        "original_owner_reports": [{"name": report["name"], "complete_rows": len(report["rows"])} for report in data.get("owner_reports", [])],
        "prior_rows_adopted": False}



def verify_continuation_archives(data, runtime, collector):
    """Reject changed compiled bytes before collecting a complete independent continuation."""
    continuation = data.get("owner_continuation")
    if continuation is None:
        return
    require(set(runtime) == {"baseline", "candidate"}, "Missing prepared continuation runtime")
    for side, expected in continuation["runtime_sha256"].items():
        require(set(runtime[side]) == set(expected), "Prepared continuation runtime inventory changed")
        for project, sha in expected.items():
            require(digest(Path(runtime[side][project]["path"])) == sha, "Continuation runtime differs from original measured bytes")
    require(digest(collector) == continuation["collector_sha256"], "Continuation collector differs from original measured bytes")


def run(root, manifest_path, output, input_root=None, input_producer="", execution_head=None, owner_report=None, explicit_manual=False, input_bundle=None, input_artifacts=None):
    """Own a fresh whole attempt; an exception preserves failure and never adopts completed rows."""
    input_root = root if input_root is None else input_root
    require(os.name == "nt", "Paired CI requires the Windows owned-process boundary")
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    record = {"schema": "strata-paired-jvm-attempt-v1", "status": "failed", "run_id": os.environ.get("GITHUB_RUN_ID"),
              "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"), "sources": {}, "phases": [], "gpu_window_loaded_game": "N/A"}
    helper = Path(__file__).with_name("invoke-windows.ps1")
    init = Path(__file__).with_name("bounded-jvm.init.gradle.kts")
    try:
        actual_head = git(root, "rev-parse", "HEAD")
        require(execution_head is None or actual_head == execution_head, "Paired execution head changed after selection")
        record.update(driver_head=actual_head, input_producer=input_producer, manifest_sha256=digest(manifest_path))
        if input_bundle is not None:
            record["dispatch_inputs"] = preserve_file(input_bundle, output / "dispatch-inputs.zip")
            require(record["dispatch_inputs"]["sha256"] == input_producer, "Dispatch input bytes changed")
            record["dispatch_artifacts"] = input_artifacts or []
        homes = sorted({os.environ["STRATA_PAIRED_TOOLCHAIN"], os.environ["JAVA_HOME"]})
        versions = tomllib.loads((root / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
        for key, provider, version in (("STRATA_PAIRED_TOOLCHAIN", "Eclipse Adoptium", versions["performance-toolchain"].split("+", 1)[0]), ("JAVA_HOME", "Oracle Corporation", versions["performance-launcher"])):
            release = dict(line.split("=", 1) for line in (Path(os.environ[key]) / "release").read_text(encoding="utf-8").splitlines() if "=" in line)
            require(release["IMPLEMENTOR"].strip('"') == provider and release["JAVA_VERSION"].strip('"') == version, "Actual Java provider/version differs from the fixed catalog profile")
        gradle_cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
        allowed_java = {os.path.normcase(str(Path(home, "bin/java.exe"))): digest(Path(home, "bin/java.exe")) for home in homes}
        allowed_releases = {Path(home, "release"): digest(Path(home, "release")) for home in homes}
        vm_directory = output / "vms"
        vm_directory.mkdir()
        class_staging = output / "class-loads"
        class_staging.mkdir()
        captured_class_loads = {}
        common = ["-Pstrata.jvmOnly=true", "--no-daemon", "--no-parallel", "--max-workers=1", "--no-build-cache", "--no-configuration-cache", "--console=plain",
                  "-Pkotlin.compiler.execution.strategy=in-process", "-Pdetekt.use.worker.api=false", "-Dorg.gradle.java.installations.auto-detect=false",
                  "-Dorg.gradle.java.installations.auto-download=false", "-Dorg.gradle.java.installations.fromEnv=", "-Dorg.gradle.java.installations.paths=" + ",".join(homes), "-I", str(init)]

        selected_input_files = {}

        def phase(name, workspace, arguments, expected_forks=None, test_gate=None):
            """Admit a full bounded call with upload reserve, then require natural Job release."""
            nonlocal captured_class_loads
            require(git(root, "rev-parse", "HEAD") == record["driver_head"], "Paired execution head changed before invocation")
            verify_driver_sources(record["driver_sources"])
            verify_input_property_files(selected_input_files)
            if "driver_source" in record:
                verify_driver_source(root, record["driver_head"], data, explicit_manual)
            require(time.monotonic() - started + 1830 + 180 < 6 * 60 * 60, "Insufficient job time for the full next invocation and upload reserve")
            for executable, expected in allowed_java.items():
                require(digest(Path(executable)) == expected, "Pinned Java executable bytes changed before invocation")
            for release, expected in allowed_releases.items():
                require(digest(release) == expected, "Pinned Java release bytes changed before invocation")
            directory = output / "phases" / name
            directory.mkdir(parents=True, exist_ok=False)
            class_directory = directory / "class-loads"
            class_directory.mkdir()
            logs = vm_directory.as_posix()
            flags = f"-XX:+UnlockDiagnosticVMOptions -XX:+PrintCommandLineFlags -XX:-DisplayVMOutput -XX:+LogVMOutput -XX:LogFile={logs}/vm-%p-%t.log"
            environment = {"JAVA_OPTS": "-Xmx64m -XX:MaxMetaspaceSize=256m", "GRADLE_OPTS": "", "JAVA_TOOL_OPTIONS": f"-Xmx32m -XX:MaxMetaspaceSize=256m {flags}", "JDK_JAVA_OPTIONS": "", "_JAVA_OPTIONS": "",
                           "STRATA_PAIRED_WORKSPACE": workspace.as_posix(), "STRATA_PAIRED_LOGS": logs, "STRATA_PAIRED_CLASS_LOGS": class_staging.as_posix(), "STRATA_PAIRED_DAEMON_METASPACE_MIB": "1024"}
            matches = [side for side in selected_input_files if f"-Pstrata.paired.runtime={runtime_manifests[side]}" in arguments]
            require(len(matches) <= 1, "Ambiguous runtime inventory input side")
            input_flags = []
            if matches:
                selected = selected_input_files[matches[0]]
                input_flags = [f"-Pstrata.paired.inputProperties={selected['path']}"]
            args = arguments + input_flags + common + [f"-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 {flags}"]
            request = {"workspace": str(workspace), "arguments": args, "environment": environment, "minimumFreePhysicalBytes": 4 * 1024**3,
                       "jobCommittedBytes": 3 * 1024**3, "workSeconds": 1800, "cleanupSeconds": 30}
            write_new(directory / "request.json", request)
            phase_started = time.monotonic()
            previous_vms = set(vm_directory.glob("*.log"))
            previous_class_loads = class_load_inventory(class_staging, captured_class_loads)
            try:
                exit_code = invoke(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(helper), "-Request", str(directory / "request.json"), "-Report", str(directory / "terminal.json")], root, directory / "console.log")
            finally:
                try:
                    captured_class_loads = preserve_class_loads(class_staging, directory, previous_class_loads)
                finally:
                    if test_gate is not None:
                        preserve_test_reports(workspace, directory, test_gate)
            verify_driver_sources(record["driver_sources"])
            terminal = read_json(directory / "terminal.json")
            if "driver_source" in record:
                verify_driver_source(root, record["driver_head"], data, explicit_manual)
            record["phases"].append({"name": name, "elapsed_seconds": time.monotonic()-phase_started, "exit_code": exit_code, "terminal": terminal,
                                    "available_disk_bytes": shutil.disk_usage(output).free})
            if exit_code != 0:
                record["phases"][-1]["failed_jvm_diagnostics"] = preserve_failed_jvm_diagnostics(directory, workspace, terminal, environment)
            require(exit_code == 0 and terminal["exitCode"] == 0 and terminal["naturalRelease"] is True and terminal["handlesClosed"] is True,
                    f"Owned phase failed: {name}; retained {directory}")
            admission = read_json(directory / "child-admission.json")
            require(admission["inOwnedJob"] is True and admission["jobName"] == terminal["jobName"] and admission["availablePhysicalBytes"] >= 4 * 1024**3,
                    "Missing fresh pre-Java RAM guard inside the actual owning Job")
            if record["phases"]:
                first = record["phases"][0]["terminal"]
                require(terminal["inheritedAffinityMask"] == first["inheritedAffinityMask"] and terminal["systemAffinityMask"] == first["systemAffinityMask"], "Host affinity changed between paired phases")
            verify_vms(directory, sorted(set(vm_directory.glob("*.log"))-previous_vms), terminal, allowed_java, expected_forks)
            if matches:
                verify_input_properties(directory, selected, sorted(set(vm_directory.glob("*.log"))-previous_vms))
            verify_input_property_files(selected_input_files)
            for release, expected in allowed_releases.items():
                require(digest(release) == expected, "Pinned Java release bytes changed during invocation")
            return directory

        data = load_manifest(input_root, manifest_path)
        data, record["scope"] = continuation_scope(data, owner_report)
        driver = verify_driver_source(root, record["driver_head"], data, explicit_manual)
        if driver is not None:
            verify_input_producer(root, record["driver_head"], input_root, manifest_path, input_producer, record["manifest_sha256"], input_bundle)
            record["driver_source"] = driver
            record["scope"].update(consumer_ci_establishes_subject_acceptance=False, prior_rows_adopted=False)
        java_roles = {"toolchain": Path(os.environ["STRATA_PAIRED_TOOLCHAIN"]), "launcher": Path(os.environ["JAVA_HOME"])}
        write_new(output / "java-installations.json", {role: {"home": str(home), "release": (home / "release").read_text(encoding="utf-8"),
                                                             "release_sha256": digest(home / "release"), "executable_sha256": digest(home / "bin/java.exe")}
                                                      for role, home in java_roles.items()})
        for item in data["java_pins"]:
            home = java_roles[item["role"]]
            require(digest(home / "bin/java.exe") == item["executable_sha256"] and digest(home / "release") == item["release_sha256"], "Fixed Java executable or release bytes changed")
        hardware = subprocess.check_output(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "@{processors=@(Get-CimInstance Win32_Processor | Select-Object Name,Manufacturer,NumberOfCores,NumberOfLogicalProcessors); os=(Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,BuildNumber,TotalVisibleMemorySize); power=(powercfg /getactivescheme)} | ConvertTo-Json -Depth 4"], text=True, encoding="utf-8-sig")
        write_new(output / "host.json", json.loads(hardware))
        shutil.copyfile(manifest_path, output / "manifest.json")
        record["executables"] = [{"path": str(path), "sha256": digest(path)} for path in [Path(sys.executable), Path(shutil.which("pwsh")), *[Path(home, "bin/java.exe") for home in homes]]]
        record["driver_sources"] = preserve_driver_sources(root, output)
        require(all(item["execution_head"] == record["driver_head"] for item in record["driver_sources"].values()), "Driver head changed during preservation")
        record["driver_files"] = {name: item["sha256"] for name, item in record["driver_sources"].items()}
        workspaces = {}
        for side, source in data["sources"].items():
            identical = next((key for key in workspaces if data["sources"][key] == source), None)
            if identical is None:
                workspaces[side], record["sources"][side] = materialize(root, output, side, source, input_root)
            else:
                workspaces[side], record["sources"][side] = workspaces[identical], record["sources"][identical]
        if input_producer and driver is None:
            require(source_files(workspaces["candidate"], data["sources"]["candidate"]["tree"]) == source_files(root, record["driver_head"]), "Candidate source differs from execution head")
        fixture = workspaces["fixture"]
        owners = fixture_projects(data)
        fixture_tasks = [archive_task(":performance-testkit")] + [project + ":jmhCompileGeneratedClasses" for project in owners]
        fixture_tasks += [report["project"] + ":testClasses" for report in data.get("owner_reports", []) if report["kind"] == "test"]
        test_classpaths = test_classpath_preparation(data)
        for side in ("baseline", "candidate"):
            tasks = [archive_task(project) for project in data["runtime_projects"]]
            if workspaces[side] == fixture:
                tasks += fixture_tasks
            else:
                tasks += pinned_fixture_preparation(data, side, fixture_tasks)
            phase(f"prepare-{side}", workspaces[side], tasks + test_classpaths)
        if fixture not in (workspaces["baseline"], workspaces["candidate"]):
            phase("prepare-fixture", fixture, fixture_tasks)
        for item in data["dependency_pins"]:
            pin(gradle_cache, item)
        for side, values in data["compiled_pins"].items():
            for value in values:
                pin(workspaces[side], value)
        runtime_manifests, runtime_archives = {}, {}
        record["archives"] = {}
        for side in ("baseline", "candidate"):
            runtime_archives[side] = {project: preserve_file(archive_file(workspaces[side], project), output / "prepared" / side / f"target-{index}.jar")
                                      for index, project in enumerate(data["runtime_projects"])}
            mapping = {project: item["path"] for project, item in runtime_archives[side].items()}
            runtime_manifests[side] = output / f"runtime-{side}.properties"
            properties(runtime_manifests[side], mapping)
            record["archives"][side] = {project: digest(Path(path)) for project, path in mapping.items()}
        inputs = output / "fixture-inputs.properties"
        external_inputs = {label for suite in data["suites"] for label in suite.get("external_runtime_inputs", {}).values()}
        input_archives = preserve_inputs(input_root, output / "prepared" / "inputs", data["inputs"], external_inputs)
        paired_owner_plans = {}
        for specification in data.get("owner_reports", []):
            if "paired_jmh" in specification:
                destination = output / "prepared" / "inputs" / (specification["name"] + "-source-plan.json")
                write_new(destination, paired_owner_source_plan(fixture, specification, record["sources"], runtime_archives))
                item = {"path": str(destination), "sha256": digest(destination)}
                label = "paired-source-" + specification["name"]
                require(label not in input_archives, "Paired source plan overlaps an original input label")
                input_archives[label] = item
                paired_owner_plans[specification["name"]] = item
        properties(inputs, {label: item["path"] for label, item in input_archives.items()})
        selected_input_files = input_property_files(output, data, input_archives)
        collector = archive_file(fixture, ":performance-testkit")
        verify_continuation_archives(data, runtime_archives, collector)
        prepared = output / "compiled"
        prepared.mkdir()
        shutil.copyfile(collector, prepared / collector.name)
        for owner in owners:
            build = fixture.joinpath(*owner.strip(":").split(":"), "build")
            for relative in ("classes/kotlin/jmh", "jmh-generated-classes", "jmh-generated-resources"):
                original = build / relative
                require(original.is_dir(), "Missing common compiled fixture closure")
                shutil.copytree(original, prepared / owner.strip(":").replace(":", "-") / relative)
        comparisons, invocation_ids, operation_comparisons, operation_ids, admitted_suites = [], set(), [], set(), []
        completed_jmh = []
        java_identity = read_json(output / "java-installations.json")
        # Every independent suite/side control completes before the first timed invocation in the attempt.
        for suite in data["suites"]:
            project, task, verifier = FixtureProject.decode(suite["project"]).value
            selectors = [f"-Pstrata.performance.{key}={','.join(suite[key])}" for key in ("benchmarks", "workloads") if suite[key]]
            parameters = output / (suite["name"] + "-parameters.properties")
            if suite["parameters"]:
                properties(parameters, {key: ",".join(values) for key, values in suite["parameters"].items()})
                selectors.append(f"-Pstrata.performance.parameters={parameters}")
            selectors += [f"-Pstrata.performance.fixtureInputs={inputs}"]
            classpaths = {}
            for side in ("baseline", "candidate"):
                for index, gate in enumerate(suite.get("test_gates", [])):
                    source_guard(workspaces[side], data["sources"][side]["tree"])
                    checked = phase(f"tests-{suite['name']}-{side}-{index}", workspaces[side], [gate["task"]], 0, gate)
                    verify_test_gate(checked, gate)
                    source_guard(workspaces[side], data["sources"][side]["tree"])
                flags = [f"-Pstrata.paired.runtime={runtime_manifests[side]}"] + suite_launch_flags(suite, side, input_archives)
                if "preflight" in suite:
                    retained = output / "prepared-classpaths" / suite["name"] / side
                    flags += [f"-Pstrata.paired.preserveClasspath={retained}"]
                    classpaths[side] = retained / "classpath.json"
                phase(f"admit-{suite['name']}-{side}", fixture, [project + ":" + verifier] + flags + selectors)
                if "preflight" in suite:
                    control = preflight_request(input_root, output, suite, side, classpaths[side], runtime_archives[side], record["sources"], java_identity, input_archives)
                    classpaths[side] = {"path": classpaths[side], "sha256": digest(classpaths[side])}
                    request_hash = digest(control / "request.json")
                    actual = phase(f"preflight-{suite['name']}-{side}", fixture,
                                   [project + ":strataPairedPreflight", f"-Pstrata.paired.template={verifier}",
                                    f"-Pstrata.paired.preflight={control / 'request.json'}", f"-Pstrata.paired.classpath={classpaths[side]['path']}",
                                    f"-Pstrata.paired.runtime={runtime_manifests[side]}"] + suite_launch_flags(suite, side, input_archives) + selectors, 0)
                    verify_preflight(control, actual, record["phases"][-1]["terminal"], request_hash)
                    require(digest(classpaths[side]["path"]) == classpaths[side]["sha256"], "Control changed its prepared classpath inventory")
            admitted_suites.append((suite, project, task, selectors, classpaths))
        paired_jmh_bindings = {}
        for suite, project, task, selectors, classpaths in admitted_suites:
            bindings = {"baseline": [], "candidate": []}
            for mode in suite.get("modes", ["avgt", "sample"]):
                runs = {"baseline": [], "candidate": []}
                for repetition in range(3):
                    for side in ("baseline", "candidate"):
                        for key, workspace in workspaces.items():
                            source_guard(workspace, data["sources"][key]["tree"])
                        for archives in runtime_archives.values():
                            for item in archives.values():
                                require(digest(Path(item["path"])) == item["sha256"], "Prepared runtime bytes changed before collection")
                        for item in input_archives.values():
                            require(digest(Path(item["path"])) == item["sha256"], "Frozen input bytes changed before collection")
                        if "preflight" in suite:
                            pin(input_root, suite["preflight"]["qualification"])
                        destination = output / "raw" / suite["name"] / mode / side / f"run-{repetition}"
                        flags = [f"-Pstrata.performance.mode={mode}", f"-Pstrata.performance.repetition={repetition}",
                                 f"-Pstrata.performance.suite={suite['name']}", f"-Pstrata.paired.runtime={runtime_manifests[side]}"] + suite_launch_flags(suite, side, input_archives)
                        if side in classpaths:
                            require(digest(classpaths[side]["path"]) == classpaths[side]["sha256"], "Prepared classpath inventory changed before collection")
                            prepared_classpath(classpaths[side]["path"])
                            flags.append(f"-Pstrata.paired.classpath={classpaths[side]['path']}")
                        if suite.get("profilers"):
                            flags.append("-Pstrata.performance.profilers=" + ",".join(suite["profilers"]))
                        if FixtureProject.decode(project) == FixtureProject.HISTORICAL:
                            flags += [f"-Pstrata.performance.historicalOutputRoot={destination.parent}"]
                            destination = destination.parent / (suite["name"] + ("-sample" if mode == "sample" else "")) / f"run-{repetition}"
                        else:
                            flags += [f"-Pstrata.performance.output={destination}"]
                        phase(f"collect-{suite['name']}-{mode}-{side}-{repetition}", fixture, [project + ":" + task] + selectors + flags, suite["expected_cases"])
                        receipt = read_json(destination / "receipt.json")
                        require(receipt["status"] == "passed" and receipt["fork_verification"] == "loaded-artifacts-per-iteration-v1"
                                and len(receipt["registered_workloads"]) == suite["expected_cases"] and receipt["run_id"] not in invocation_ids,
                                "Missing cases, fork proof or independent invocation")
                        invocation_ids.add(receipt["run_id"])
                        completed_jmh.append((suite["name"], mode, side, repetition, receipt["run_id"]))
                        runs[side].append(str(destination))
                        if mode == "avgt":
                            bindings[side].append(str(destination))
                request_path = output / f"{suite['name']}-{mode}-request.json"
                comparison_path = output / f"{suite['name']}-{mode}-comparison.json"
                write_new(request_path, {"command": "jmh-comparison", "collector": str(collector), "baseline": runs["baseline"], "candidate": runs["candidate"],
                                         "output": str(comparison_path), "repetitions": 3})
                phase(f"compare-{suite['name']}-{mode}", fixture, [":performance-testkit:processEvidence", f"-Pstrata.performance.request={request_path}"])
                comparison = read_json(comparison_path)
                require(comparison["status"] == "passed" and len(comparison["cases"]) == suite["expected_cases"], "Incomplete paired comparison")
                comparisons.append((suite["name"], mode, comparison))
            paired_jmh_bindings[suite["name"]] = {"runs": bindings, "selectors": selectors}
            if "operation_reports" in suite:
                operation = suite["operation_reports"]
                runs = {"baseline": [], "candidate": []}
                for repetition in range(3):
                    for side in ("baseline", "candidate"):
                        for key, workspace in workspaces.items():
                            source_guard(workspace, data["sources"][key]["tree"])
                        destination = output / "raw" / suite["name"] / "operations" / side / f"run-{repetition}"
                        request_path = output / f"{suite['name']}-operations-{side}-{repetition}-request.json"
                        write_new(request_path, {**operation, "collector": str(collector), "jmh_directory": bindings[side][repetition],
                                                 "output": str(destination), "repetition": repetition, "source_bindings": record["sources"]})
                        flags = [f"-Pstrata.performance.operationRequest={request_path}", f"-Pstrata.paired.runtime={runtime_manifests[side]}"] + suite_launch_flags(suite, side, input_archives)
                        if side in classpaths:
                            require(digest(classpaths[side]["path"]) == classpaths[side]["sha256"], "Prepared classpath inventory changed before operation collection")
                            prepared_classpath(classpaths[side]["path"])
                            pin(input_root, suite["preflight"]["qualification"])
                            flags.append(f"-Pstrata.paired.classpath={classpaths[side]['path']}")
                        phase(f"collect-{suite['name']}-operations-{side}-{repetition}", fixture,
                              [project + ":" + task] + flags + selectors, 0)
                        report = read_json(destination / "report.json")
                        require(report["status"] == "passed" and len(report["phases"]) == operation["expected_rows"]
                                and sum(row["control"] for row in report["phases"]) == operation["expected_controls"]
                                and report["run_id"] not in invocation_ids | operation_ids, "Incomplete or reused operation invocation")
                        operation_ids.add(report["run_id"])
                        runs[side].append(str(destination / "report.json"))
                request_path = output / f"{suite['name']}-operations-comparison-request.json"
                comparison_path = output / f"{suite['name']}-operations-comparison.json"
                write_new(request_path, {"command": "operation-comparison", "collector": str(collector),
                                         "baseline": runs["baseline"], "candidate": runs["candidate"], "output": str(comparison_path)})
                phase(f"compare-{suite['name']}-operations", fixture, [":performance-testkit:processEvidence", f"-Pstrata.performance.request={request_path}"])
                comparison = read_json(comparison_path)
                require(comparison["status"] == "passed" and len(comparison["phases"]) == operation["expected_rows"], "Incomplete operation comparison")
                operation_comparisons.append((suite["name"], comparison))
        owner_comparisons, owner_ids, owner_controls = [], set(), []
        for specification in data.get("owner_reports", []):
            runs, classpaths = {"baseline": [], "candidate": []}, {}
            library_manifest = output / (specification["name"] + "-libraries.properties")
            for repetition in range(3):
                for side in ("baseline", "candidate"):
                    for key, workspace in workspaces.items():
                        source_guard(workspace, data["sources"][key]["tree"])
                    for archives in runtime_archives.values():
                        for item in archives.values():
                            require(digest(Path(item["path"])) == item["sha256"], "Prepared owner runtime changed")
                    destination = output / "raw" / specification["name"] / side / f"run-{repetition}.json"
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    request = output / f"{specification['name']}-{side}-{repetition}-owner-request.json"
                    arguments, paired_selectors = [str(destination), str(repetition)], []
                    if "paired_jmh" in specification:
                        paired = paired_jmh_bindings[specification["paired_jmh"]["suite"]]
                        plan = paired_owner_plans[specification["name"]]
                        extra, library_manifest = paired_owner_arguments(Path(paired["runs"][side][repetition]), repetition,
                                                                       plan, fixture, len(specification["rows"]))
                        arguments += extra
                        paired_selectors = paired["selectors"]
                    write_new(request, {"schema": "strata-paired-jvm-owner-request-v1", "project": specification["project"],
                                        "template": specification["template"], "kind": specification["kind"], "main": specification["main"],
                                        "arguments": arguments, "inputs": str(library_manifest)})
                    flags = [f"-Pstrata.paired.report={request}", f"-Pstrata.paired.runtime={runtime_manifests[side]}",
                             f"-Pstrata.performance.fixtureInputs={inputs}"]
                    if side not in classpaths:
                        retained = output / "prepared-classpaths" / specification["name"] / side
                        flags.append(f"-Pstrata.paired.preserveClasspath={retained}")
                        classpaths[side] = retained / "classpath.json"
                    else:
                        prepared_classpath(classpaths[side])
                        flags.append(f"-Pstrata.paired.classpath={classpaths[side]}")
                    phase(f"collect-{specification['name']}-owner-{side}-{repetition}", fixture,
                          [specification["project"] + ":strataPairedEvidence"] + flags + paired_selectors, 0)
                    prepared = prepared_classpath(classpaths[side])
                    require(class_entry(prepared, "dev.s7a.strata.performance.JvmPerformanceMeter")["sha256"] == digest(collector), "Owner report substituted its collector")
                    verify_owner_report(read_json(destination), specification, repetition, runtime_archives[side], invocation_ids | operation_ids | owner_ids)
                    raw = read_json(destination)
                    owner_ids.add(raw["run_id"])
                    if specification.get("controls"):
                        owner_controls.append({"suite": specification["name"], "side": side, "repetition": repetition, "run_id": raw["run_id"],
                                               "rows": [{**{key: row[key] for key in specification["phase_keys"]},
                                                         **{key: row[key] for key in specification["controls"]}} for row in raw["phases"]]})
                    runs[side].append(str(destination))
            comparison_path = output / (specification["name"] + "-owner-comparison.json")
            request = output / (specification["name"] + "-comparison-owner-request.json")
            processing_collector = owner_processing_collector(classpaths["candidate"], collector)
            write_new(request, {"schema": "strata-paired-jvm-owner-request-v1", "project": specification["project"],
                                "template": specification["template"], "kind": specification["kind"], "main": specification["comparison"],
                                "arguments": runs["baseline"] + runs["candidate"] + [processing_collector, str(comparison_path)], "inputs": str(library_manifest)})
            phase(f"compare-{specification['name']}-owner", fixture,
                  [specification["project"] + ":strataPairedEvidence", f"-Pstrata.paired.report={request}",
                   f"-Pstrata.paired.runtime={runtime_manifests['candidate']}", f"-Pstrata.paired.classpath={classpaths['candidate']}",
                   f"-Pstrata.performance.fixtureInputs={inputs}"])
            comparison = read_json(comparison_path)
            require(comparison["status"] == "passed" and len(comparison["phases"]) == len(specification["rows"]), "Incomplete owner comparison")
            owner_comparisons.append((specification, comparison))
        require(len(owner_ids) == len(data.get("owner_reports", [])) * 6, "Whole owner report attempt incomplete")
        record["owner_report_cells"] = sum(len(report["rows"]) * 6 for report in data.get("owner_reports", []))
        write_new(output / "owner-controls.json", owner_controls)
        for side, workspace in workspaces.items():
            source_guard(workspace, data["sources"][side]["tree"])
            for value in data["compiled_pins"][side]:
                pin(workspace, value)
        for item in data["dependency_pins"]:
            pin(gradle_cache, item)
        for suite, project, task, selectors, classpaths in admitted_suites:
            for item in classpaths.values():
                require(digest(item["path"]) == item["sha256"], "Prepared classpath inventory changed during collection")
                prepared_classpath(item["path"])
        verify_jmh_completion(data, invocation_ids, comparisons, completed_jmh)
        require(len(operation_ids) == sum("operation_reports" in suite for suite in data["suites"]) * 6, "Supplemental operation attempt incomplete")
        record["score_cells"] = sum(suite["expected_cases"] * len(suite.get("modes", ["avgt", "sample"])) * 6 for suite in data["suites"])
        record["operation_cells"] = sum(suite["operation_reports"]["expected_rows"] * 6 for suite in data["suites"] if "operation_reports" in suite)
        table(output, comparisons, record["phases"], operation_comparisons, owner_comparisons)
        if driver is not None:
            verify_driver_source(root, record["driver_head"], data, explicit_manual)
            with (output / "report.md").open("a", encoding="utf-8") as target:
                target.write(f"\nFrozen shared driver `{driver['ref']}` / tree `{driver['tree']}` executed as `{driver['execution_head']}` / tree `{driver['execution_tree']}`.\n")
                for side in ("fixture", "baseline", "candidate"):
                    subject = record["sources"][side]
                    target.write(f"\n{side.title()} subject `{subject['ref']}` / tree `{subject['tree']}` / source archive SHA-256 `{subject['archive_sha256']}`.\n")
                target.write("\nConsumer CI does not certify these subject sources. Whole-Issue acceptance remains separate; no prior rows were adopted.\n")
        if owner_controls:
            with (output / "report.md").open("a", encoding="utf-8") as target:
                target.write("\nUntimed original owner controls are raw counts/transitions, not wall-time or percentile measurements. Every independent invocation is retained.\n\n| Suite | Side | Repetition | Original row/control fields |\n| --- | --- | ---: | --- |\n")
                for control in owner_controls:
                    for row in control["rows"]:
                        target.write(f"| {control['suite']} | {control['side']} | {control['repetition']} | {json.dumps(row, sort_keys=True)} |\n")
        record["status"] = "passed"
    except BaseException as failure:
        record["error"] = f"{type(failure).__name__}: {failure}"
        raise
    finally:
        record["elapsed_seconds"] = time.monotonic()-started
        write_new(output / "attempt.json", record)
        write_new(output / "file-inventory.json", {path.relative_to(output).as_posix(): {"sha256": digest(path), "bytes": path.stat().st_size}
                                                  for path in sorted(output.rglob("*")) if path.is_file() and path.relative_to(output).parts[0] != "workspaces"})
        archive = pack(output)
        print(f"Preserved {record['status']} attempt: {archive}", flush=True)


def source_files(root, tree):
    """Compare all tracked source paths, modes and blob identities, excluding only transient inputs."""
    rows = {}
    for entry in git(root, "ls-tree", "-r", "-z", tree).split("\0"):
        if entry:
            identity, path = entry.split("\t", 1)
            if path != ".github/performance" and not path.startswith(".github/performance/"):
                rows[path] = identity
    return rows


def verify_driver_source(root, execution_head, data, explicit_manual):
    """Admit a separate frozen driver only for explicit manual inputs; guard every actual tracked driver byte and mode."""
    driver = data.get("driver_source")
    if driver is None:
        return None
    require(explicit_manual and os.environ.get("GITHUB_EVENT_NAME") in (None, "workflow_dispatch"),
            "Separate driver source requires explicit manual immutable inputs")
    require(git(root, "cat-file", "-t", driver["ref"]) == "commit"
            and git(root, "rev-parse", driver["ref"] + "^{tree}") == driver["tree"], "Frozen driver commit or tree changed")
    require(subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", driver["ref"], execution_head],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0, "Frozen driver is not an execution ancestor")
    execution_tree = git(root, "rev-parse", execution_head + "^{tree}")
    require(source_files(root, driver["tree"]) == source_files(root, execution_tree), "Frozen driver source differs from execution head")
    require(git(root, "write-tree") == execution_tree
            and subprocess.run(["git", "-C", str(root), "diff", "--quiet", "--no-ext-diff", "HEAD"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0,
            "Actual driver source index or working bytes changed")
    return {**driver, "execution_head": execution_head, "execution_tree": execution_tree}


def declared_inputs(root, head):
    """Resolve one immutable trailer only from a clean head whose sole parent owns the data."""
    require(not git(root, "ls-tree", "-z", head, "--", ".github/performance"), "Execution head contains transient performance inputs")
    message = subprocess.check_output(["git", "-C", str(root), "show", "-s", "--format=%B", head])
    parsed = subprocess.check_output(["git", "-C", str(root), "interpret-trailers", "--parse"], input=message).decode("utf-8").splitlines()
    markers = [line for line in parsed if line.partition(":")[0].lower() == "performance-inputs"]
    raw_markers = [line for line in message.decode("utf-8").splitlines() if re.match(r"\s*Performance-Inputs\b", line, re.IGNORECASE)]
    if not raw_markers and not markers:
        return None
    require(len(markers) == len(raw_markers) == 1, "Missing, malformed or duplicate Performance-Inputs trailer")
    match = re.fullmatch(r"Performance-Inputs: ([0-9a-f]{40}):(.+)", markers[0])
    require(match is not None and raw_markers[0] == markers[0], "Malformed Performance-Inputs trailer")
    producer, manifest = match.groups()
    relative_path(root, manifest)
    require(manifest.startswith(".github/performance/") and manifest.endswith(".json"), "Manifest must belong to the transient input subtree")
    require(git(root, "rev-list", "--parents", "-n", "1", head).split() == [head, producer], "Input producer is not the sole execution-head parent")
    changes = git(root, "diff", "--no-renames", "--name-status", "-z", producer, head).split("\0")
    require(changes[-1] == "" and len(changes) % 2 == 1 and len(changes) != 1,
            "Consumer must remove its transient input subtree")
    require(all(changes[index] == "D" and changes[index + 1].startswith(".github/performance/")
                for index in range(0, len(changes) - 1, 2)), "Consumer changes nondata source or modes")
    require(source_files(root, producer) == source_files(root, head), "Producer source differs from execution head")
    for entry in git(root, "ls-tree", "-r", "-z", producer, "--", ".github/performance").split("\0"):
        if entry:
            require(entry.split("\t", 1)[0].split()[:2] in (["100644", "blob"], ["100755", "blob"]), "Unsafe producer input entry")
    return producer, manifest


def verify_input_producer(root, head, input_root, manifest, producer, manifest_sha256, bundle=None):
    """Bind collection to exact dispatch ZIP bytes or the historical immutable replay declaration."""
    require(producer and digest(manifest) == manifest_sha256, "Collection manifest differs from selected immutable inputs")
    if bundle is not None:
        require(digest(bundle) == producer, "Collection dispatch input bytes changed")
    else:
        declaration = declared_inputs(root, head)
        require(declaration is not None and declaration == (producer, manifest.relative_to(input_root).as_posix()),
                "Separate driver collection requires immutable declared producer inputs")


def unpack_dispatch_inputs(raw, directory):
    """Extract bounded regular metadata without overwriting source or following path aliases."""
    require(0 < len(raw) <= 6 * 1024**2, "Dispatch input ZIP exceeds 6 MiB")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries = archive.infolist()
        require(0 < len(entries) <= 256 and sum(item.file_size for item in entries) <= 4 * 1024**2,
                "Dispatch inputs exceed 256 files or 4 MiB expanded")
        paths = set()
        reserved = {"con", "prn", "aux", "nul", *(f"com{index}" for index in range(1, 10)), *(f"lpt{index}" for index in range(1, 10))}
        for entry in entries:
            path = PurePosixPath(entry.filename)
            require(path.as_posix() == entry.filename and entry.filename.startswith(".github/performance/")
                    and entry.filename.casefold() not in paths and not entry.is_dir()
                    and entry.external_attr >> 16 in (0, 0o600, 0o100644)
                    and all(not part.endswith((".", " ")) and part.split(".", 1)[0].casefold() not in reserved for part in path.parts),
                    "Unsafe or duplicate dispatch input entry")
            paths.add(entry.filename.casefold())
            target = relative_path(directory, entry.filename)
            require(not target.exists() and not any(parent.is_symlink() for parent in target.parents), "Dispatch input overwrites source")
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as source, target.open("xb") as destination:
                shutil.copyfileobj(source, destination)


def dispatch_artifact_inputs(directory, manifest, download=False):
    """Resolve optional large pinned metadata from same-repository immutable Actions artifacts."""
    descriptor = directory / ".github/performance/dispatch-artifacts.json"
    if not descriptor.exists():
        return set(), []
    providers = read_json(descriptor)
    require(isinstance(providers, list) and 0 < len(providers) <= 4, "Invalid dispatch artifact providers")
    pins = {item["pin"]["path"]: item["pin"]["sha256"] for item in read_json(relative_path(directory, manifest))["inputs"]}
    targets, receipts = set(), []
    for provider in providers:
        require(set(provider) == {"id", "bytes", "sha256", "archive", "files"}
                and type(provider["id"]) is int and 0 < provider["id"]
                and type(provider["bytes"]) is int and 0 < provider["bytes"] <= 128 * 1024**2
                and re.fullmatch(r"[0-9a-f]{64}", provider["sha256"])
                and isinstance(provider["files"], list) and 0 < len(provider["files"]) <= 256, "Invalid dispatch artifact identity")
        relative_path(directory, provider["archive"])
        for item in provider["files"]:
            require(set(item) == {"member", "target"} and item["target"] in pins
                    and item["target"].startswith(".github/performance/") and item["target"].casefold() not in targets,
                    "Artifact targets must be unique declared metadata input pins")
            relative_path(directory, item["member"])
            relative_path(directory, item["target"])
            targets.add(item["target"].casefold())
            local = relative_path(directory, item["target"])
            if local.exists():
                require(not download and digest(local) == pins[item["target"]], "Artifact input already exists or changed")
        if not download:
            continue
        repository = os.environ.get("GITHUB_REPOSITORY", "")
        require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository), "Missing artifact repository")
        cache = Path(os.environ.get("RUNNER_TEMP", str(directory.parent))) / "strata-dispatch-artifacts"
        cache.mkdir(parents=True, exist_ok=True)
        artifact = cache / (provider["sha256"] + ".zip")
        endpoint = f"repos/{repository}/actions/artifacts/{provider['id']}"
        metadata = json.loads(subprocess.check_output(["gh", "api", endpoint], timeout=120))
        require(metadata["id"] == provider["id"] and metadata["size_in_bytes"] == provider["bytes"]
                and metadata["digest"] == "sha256:" + provider["sha256"] and not metadata["expired"], "Artifact metadata differs from pinned provider")
        if not artifact.exists():
            raw = subprocess.check_output(["gh", "api", endpoint + "/zip"], timeout=120)
            require(len(raw) == provider["bytes"] and hashlib.sha256(raw).hexdigest() == provider["sha256"], "Artifact provider bytes changed")
            with artifact.open("xb") as target:
                target.write(raw)
        require(artifact.stat().st_size == provider["bytes"] and digest(artifact) == provider["sha256"], "Cached input artifact changed")
        with zipfile.ZipFile(artifact) as outer:
            require(len(outer.namelist()) == len(set(outer.namelist())), "Duplicate artifact archive members")
            entry = outer.getinfo(provider["archive"])
            require(entry.file_size <= 128 * 1024**2, "Artifact archive exceeds its byte bound")
            with zipfile.ZipFile(io.BytesIO(outer.read(entry))) as archive:
                require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate artifact input members")
                expanded = 0
                for item in provider["files"]:
                    entry = archive.getinfo(item["member"])
                    expanded += entry.file_size
                    require(expanded <= 4 * 1024**2 and not entry.is_dir()
                            and (entry.external_attr >> 16) & 0o170000 in (0, 0o100000), "Unsafe or oversized artifact metadata")
                    raw = archive.read(entry)
                    require(hashlib.sha256(raw).hexdigest() == pins[item["target"]], "Artifact input pin changed")
                    target = relative_path(directory, item["target"])
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with target.open("xb") as stream:
                        stream.write(raw)
        receipts.append(provider)
    return targets, receipts


def pack_dispatch_inputs(directory, manifest):
    """Encode local metadata as workflow input without creating a commit or branch."""
    require(directory.is_dir() and not directory.is_symlink(), "Supply a regular local metadata directory")
    require(isinstance(manifest, str) and len(manifest) <= 4096, "Dispatch manifest path exceeds 4,096 characters")
    read_json(relative_path(directory, manifest))
    external, _ = dispatch_artifact_inputs(directory, manifest)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_STORED) as archive:
        files = sorted(directory.rglob("*"))
        require(not any(path.is_symlink() for path in files), "Linked dispatch input")
        regular = [path for path in files if path.is_file() and path.relative_to(directory).as_posix().casefold() not in external]
        require(len(regular) <= 256 and sum(path.stat().st_size for path in regular) <= 4 * 1024**2,
                "Dispatch inputs exceed 256 files or 4 MiB expanded")
        for path in regular:
            entry = zipfile.ZipInfo(path.relative_to(directory).as_posix(), (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_STORED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, path.read_bytes())
    raw = buffer.getvalue()
    compressed = lzma.compress(raw)
    require(len(compressed) <= 45000, "Compressed dispatch inputs must be at most 45,000 bytes")
    with tempfile.TemporaryDirectory(prefix="strata-dispatch-check-") as temporary:
        unpack_dispatch_inputs(raw, Path(temporary))
    return {"paired_manifest": manifest, "paired_inputs": base64.b64encode(compressed).decode("ascii")}


def dispatch_input_bytes():
    """Read the manual payload from its event file, avoiding shell and environment string limits."""
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or not event_path:
        return None
    value = read_json(Path(event_path)).get("inputs", {}).get("paired_inputs", "")
    require(isinstance(value, str) and len(value) <= 60000, "Dispatch inputs exceed 60,000 characters")
    if not value:
        return None
    decoder = lzma.LZMADecompressor(memlimit=64 * 1024**2)
    raw = decoder.decompress(base64.b64decode(value, validate=True), 6 * 1024**2 + 1)
    require(decoder.eof and not decoder.unused_data and len(raw) <= 6 * 1024**2,
            "Truncated, trailing or oversized compressed dispatch inputs")
    return raw


@contextmanager
def selected_inputs(root, explicit=None, base=None, head=None, data_ref=None, manifest_sha256=None, expected_head=None):
    """Keep Git execution and privately checked-out pinned data separate for the full consumer lifetime."""
    execution_head = git(root, "rev-parse", "HEAD")
    require(expected_head is None or execution_head == expected_head, "Paired execution checkout differs from expected head")
    require((base is None) == (head is None), "Supply both PR base and head")
    if head is not None:
        require(all(re.fullmatch(r"[0-9a-f]{40}", value) for value in (base, head)), "Use exact PR commit identities")
        require(execution_head == head, "Paired planning checkout differs from PR head")
    bundle = dispatch_input_bytes()
    if bundle is not None:
        require(explicit is not None and base is None and head is None, "Dispatch inputs require an explicit manual manifest")
        require(len(explicit) <= 4096, "Dispatch manifest path exceeds 4,096 characters")
        require(declared_inputs(root, execution_head) is None, "Dispatch inputs cannot use a producer commit")
        require(git(root, "write-tree") == git(root, "rev-parse", execution_head + "^{tree}")
                and subprocess.run(["git", "-C", str(root), "diff", "--quiet", "--no-ext-diff", "HEAD"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0,
                "Dispatch driver tracked bytes differ from its revision")
        bundle_sha = hashlib.sha256(bundle).hexdigest()
        require(data_ref is None or data_ref == bundle_sha, "Dispatch payload differs from planned bytes")
        with tempfile.TemporaryDirectory(prefix="strata-dispatch-inputs-") as temporary:
            output = Path(temporary)
            source = {"ref": execution_head, "tree": git(root, "rev-parse", execution_head + "^{tree}"), "patches": []}
            input_root, _ = materialize(root, output, "input-driver", source)
            unpack_dispatch_inputs(bundle, input_root)
            path = relative_path(input_root, explicit)
            _, artifacts = dispatch_artifact_inputs(input_root, explicit, download=True)
            data = load_manifest(input_root, path)
            driver = verify_driver_source(root, execution_head, data, True)
            candidate, _ = materialize(root, output, "candidate-check", data["sources"]["candidate"], input_root)
            if data["schema"] != "strata-source-preparation-ci-v1" and driver is None:
                require(source_files(candidate, data["sources"]["candidate"]["tree"]) == source_files(root, execution_head),
                        "Candidate source differs from execution head")
            if data["schema"] == "strata-paired-browser-ci-v1":
                browser_controller().verify_adapters(SimpleNamespace(**globals()), candidate, data)
            actual_sha = digest(path)
            require(manifest_sha256 is None or manifest_sha256 == actual_sha, "Manifest differs from planned bytes")
            bundle_path = output / "dispatch-inputs.zip"
            bundle_path.write_bytes(bundle)
            require(git(root, "rev-parse", "HEAD") == execution_head, "Dispatch execution head changed")
            yield {"root": input_root, "path": path, "manifest": explicit, "producer": bundle_sha,
                   "sha256": actual_sha, "execution_head": execution_head, "bundle": bundle_path, "artifacts": artifacts,
                   "explicit_manual": driver is not None}
        return
    declaration = declared_inputs(root, execution_head)
    require(data_ref is None or declaration is not None and data_ref == declaration[0], "Producer differs from execution-head trailer")
    if declaration is not None:
        producer, manifest = declaration
        require(explicit is None or explicit == manifest, "Explicit manifest differs from execution-head trailer")
        with tempfile.TemporaryDirectory(prefix="strata-paired-inputs-") as temporary:
            output = Path(temporary)
            input_root, _ = materialize(root, output, "input-producer", {"ref": producer, "tree": git(root, "rev-parse", producer + "^{tree}"), "patches": []})
            path = relative_path(input_root, manifest)
            data = load_manifest(input_root, path)
            explicit_manual = explicit is not None and base is None and head is None
            driver = verify_driver_source(root, execution_head, data, explicit_manual)
            candidate, _ = materialize(root, output, "candidate-check", data["sources"]["candidate"], input_root)
            if data["schema"] != "strata-source-preparation-ci-v1" and driver is None:
                require(source_files(candidate, data["sources"]["candidate"]["tree"]) == source_files(root, execution_head), "Candidate source differs from execution head")
            if data["schema"] == "strata-paired-browser-ci-v1":
                browser_controller().verify_adapters(SimpleNamespace(**globals()), candidate, data)
            actual_sha = digest(path)
            require(manifest_sha256 is None or manifest_sha256 == actual_sha, "Manifest differs from planned bytes")
            require(git(root, "rev-parse", "HEAD") == execution_head, "Paired execution head changed during materialization")
            yield {"root": input_root, "path": path, "manifest": manifest, "producer": producer, "sha256": actual_sha, "execution_head": execution_head,
                   "explicit_manual": driver is not None}
    else:
        require(explicit is None and data_ref is None and manifest_sha256 is None, "Missing Performance-Inputs trailer for explicit input")
        require(git(root, "rev-parse", "HEAD") == execution_head, "Paired execution head changed during selection")
        yield {"root": root, "path": None, "manifest": "", "producer": "", "sha256": "", "execution_head": execution_head}


def select_manifest(root, explicit=None, base=None, head=None):
    """Select immutable commit intent or an explicit manual input without a historical default."""
    with selected_inputs(root, explicit, base, head) as selection:
        return selection["manifest"]


def preserve_driver_sources(root, output, extra=()):
    """Retain the three actual live controller sources separately from prepared source archives."""
    records = {}
    for name in ("paired-jvm.py", "invoke-windows.ps1", "bounded-jvm.init.gradle.kts", *extra):
        path = root / "gradle/performance" / name
        relative = path.relative_to(root).as_posix()
        mode, kind, blob = git(root, "ls-tree", "HEAD", "--", relative).split("\t", 1)[0].split()
        require(kind == "blob" and mode in ("100644", "100755"), "Unsafe driver source mode")
        records[name] = {"source": str(path), "git_path": relative, "git_blob": blob, "git_mode": mode,
                         "execution_head": git(root, "rev-parse", "HEAD"), **preserve_file(path, output / "driver" / name)}
    verify_driver_sources(records)
    return records


def verify_driver_sources(records):
    """Refuse changes to the live or retained driver closure before and after each invocation."""
    for item in records.values():
        root = Path(item["source"]).parents[2]
        require(git(root, "rev-parse", "HEAD") == item["execution_head"], "Execution head changed")
        require(git(root, "hash-object", "--path=" + item["git_path"], item["source"]) == item["git_blob"], "Driver bytes differ from execution head")
        require(git(root, "ls-files", "--stage", "--", item["git_path"]).split("\t", 1)[0] == f"{item['git_mode']} {item['git_blob']} 0", "Driver tracked mode or identity changed")
        require(digest(Path(item["source"])) == digest(Path(item["path"])) == item["sha256"], "Actual driver source changed")


def browser_controller():
    """Load the shared Web adapter route without changing the existing CPU collector contract."""
    specification = importlib.util.spec_from_file_location("paired_browser", Path(__file__).with_name("paired-browser.py"))
    controller = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(controller)
    return controller



def main():
    """Select the opt-in manifest during CI planning or execute one fresh Windows attempt."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest")
    parser.add_argument("--pack-inputs", type=Path, help="Encode an off-repository metadata directory for gh workflow run --json")
    parser.add_argument("--data-ref")
    parser.add_argument("--manifest-sha256")
    parser.add_argument("--expected-head")
    parser.add_argument("--base")
    parser.add_argument("--head")
    parser.add_argument("--select", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--owner-report", help="Continue one complete declared independent owner domain; does not establish whole Issue acceptance")
    args = parser.parse_args()
    if args.pack_inputs is not None:
        require(args.manifest is not None and args.output is not None and not args.select
                and all(value is None for value in (args.data_ref, args.manifest_sha256, args.expected_head, args.base, args.head, args.owner_report)),
                "Packing requires only --pack-inputs, --manifest and a new --output JSON path")
        write_new(args.output, pack_dispatch_inputs(args.pack_inputs.resolve(), args.manifest))
        return
    root = Path(__file__).resolve().parents[2]
    require(not args.select or args.owner_report is None, "CI selection retains the whole declared manifest")
    if args.expected_head is not None:
        require(re.fullmatch(r"[0-9a-f]{40}", args.expected_head) is not None and git(root, "rev-parse", "HEAD") == args.expected_head,
                "Paired execution checkout differs from expected head")
    require(args.select or args.base is None and args.head is None, "PR selection arguments require --select")
    with selected_inputs(root, args.manifest, args.base, args.head, args.data_ref, args.manifest_sha256, args.expected_head) as selection:
        if args.select:
            if "bundle" in selection:
                target = root / "build/performance-ci/dispatch-inputs.zip"
                target.parent.mkdir(parents=True, exist_ok=True)
                require(not target.exists(), "Dispatch selection output already exists")
                shutil.copyfile(selection["bundle"], target)
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as target:
                    for key in ("manifest", "data_ref", "manifest_sha256"):
                        value = selection[{"manifest": "manifest", "data_ref": "producer", "manifest_sha256": "sha256"}[key]]
                        target.write(f"paired_{key}={value}\n")
            print(selection["manifest"])
        else:
            require(args.output is not None, "Supply a fresh attempt output directory")
            require(selection["path"] is not None, "Supply explicit paired inputs")
            data = load_manifest(selection["root"], selection["path"])
            if data["schema"] in ("strata-paired-browser-ci-v1", "strata-source-preparation-ci-v1"):
                require(args.owner_report is None, "Browser collection retains the whole declared manifest")
                browser_controller().run(SimpleNamespace(**globals()), root, selection, args.output.resolve())
            else:
                run(root, selection["path"], args.output.resolve(), selection["root"], selection["producer"], selection["execution_head"], args.owner_report,
                    selection.get("explicit_manual", False), input_bundle=selection.get("bundle"), input_artifacts=selection.get("artifacts"))


if __name__ == "__main__":
    main()
