#!/usr/bin/env python3
"""Run one opt-in frozen JVM pair using the existing Gradle collectors and processor."""
import argparse
import csv
from enum import Enum
import hashlib
import html
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import threading
import time
import tomllib
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


def relative_path(root, value):
    """Keep manifest files within the checkout, including on Windows."""
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_$+./-]+", value), "Invalid repository path")
    relative = PurePosixPath(value)
    require(not relative.is_absolute() and all(part not in (".", "..", ".git") for part in relative.parts)
            and relative.as_posix() == value, "Noncanonical repository path")
    path = root.joinpath(*relative.parts)
    require(path.resolve().is_relative_to(root.resolve()), "Repository path escapes checkout")
    return path


def pin(root, value):
    """Verify the declared immutable bytes before using a patch or fixture input."""
    require(set(value) == {"path", "sha256"} and re.fullmatch(r"[0-9a-f]{64}", value["sha256"]), "Invalid file pin")
    path = relative_path(root, value["path"])
    require(digest(path) == value["sha256"], f"Pinned bytes changed: {value['path']}")
    return path


def load_manifest(root, path):
    """Validate explicit frozen refs and selectors; sampling and repetition settings stay fixed."""
    data = read_json(path)
    required = {"schema", "sources", "runtime_projects", "suites", "inputs", "compiled_pins", "dependency_pins", "java_pins"}
    require(set(data) == required and data["schema"] == "strata-paired-jvm-ci-v1", "Unsupported paired JVM manifest")
    require(set(data["sources"]) == {"fixture", "baseline", "candidate"}, "Supply all three source bindings")
    for source in data["sources"].values():
        require(set(source) == {"ref", "tree", "patches"}, "Invalid source binding")
        require(all(re.fullmatch(r"[0-9a-f]{40}", source[key]) for key in ("ref", "tree")), "Use exact source commit/tree SHAs")
        require(isinstance(source["patches"], list), "Invalid source patch list")
        for patch in source["patches"]:
            pin(root, patch)
    projects = data["runtime_projects"]
    require(isinstance(projects, list) and projects and len(set(projects)) == len(projects), "Missing or duplicate runtime project")
    require(all(re.fullmatch(r":(?:api|runtime:(?:core|headless|remote|minecraft|minecraft-fonts-lwjgl))", item) for item in projects),
            "Unsupported CPU runtime project")
    require(isinstance(data["suites"], list) and data["suites"], "No paired suites selected")
    names = set()
    for suite in data["suites"]:
        fields = {"project", "name", "benchmarks", "workloads", "parameters", "expected_cases"}
        require(fields <= set(suite) <= fields | {"profilers", "operation_reports", "preflight", "test_gates"}, "Invalid suite fields")
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
            require(isinstance(task, str) and re.fullmatch(r":[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*:(?:test|jvmTest|fontTest[A-Za-z0-9_]+)", task)
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
        require(set(value) == {"label", "pin"} and re.fullmatch(r"[A-Za-z0-9_.-]+", value["label"])
                and value["label"] not in labels, "Invalid or duplicate fixture input")
        labels.add(value["label"])
        pin(root, value["pin"])
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


def git(root, *arguments):
    """Use only this job's private source checkouts and preserve command failures."""
    return subprocess.check_output(["git", "-C", str(root), *arguments], text=True, encoding="utf-8").strip()


def source_guard(workspace, tree):
    """Require the complete staged source tree and unchanged raw working files outside the owned Java phase."""
    require(git(workspace, "write-tree") == tree, "Frozen source index changed")
    require((workspace / ".git/info/attributes").read_bytes() == b"* -text\n", "Frozen checkout attributes changed")
    subprocess.run(["git", "-C", str(workspace), "diff", "--exit-code", "--no-ext-diff"], check=True)


def materialize(root, output, side, source):
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
        subprocess.run(["git", "-C", str(workspace), "apply", "--index", "--binary", str(pin(root, patch))], check=True)
    require(git(workspace, "write-tree") == source["tree"], "Frozen patched index changed")
    git(workspace, "checkout-index", "--all", "--force")
    source_guard(workspace, source["tree"])
    archive = output / f"source-{side}.zip"
    git(workspace, "archive", "--format=zip", f"--output={archive}", source["tree"])
    return workspace, {"ref": source["ref"], "tree": source["tree"], "archive_sha256": digest(archive), "patches": source["patches"], "raw_checkout_attributes_sha256": digest(attributes)}


def archive_task(project):
    """Select the already-published JVM archive task without discovering unrelated projects."""
    return project + (":jvmJar" if project in (":api", ":runtime:core", ":performance-testkit") else ":jar")


def archive_file(workspace, project):
    """Require one ordinary binary archive rather than selecting a sources or old release JAR."""
    libraries = workspace.joinpath(*project.strip(":").split(":"), "build", "libs")
    values = [path for path in libraries.glob("*.jar") if not path.name.endswith(("-sources.jar", "-javadoc.jar", "-all.jar"))]
    require(len(values) == 1, f"Expected one current runtime archive: {project}")
    return values[0]


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
    """Retain complete bounded per-VM attribution with exact PID filenames and no rotation/truncation."""
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
        require(pid not in identities and observed == {pid} and text.endswith("\n"), "Missing, duplicate, wrong-PID or truncated class-load diagnostic")
        texts.append(text)
        identities[pid] = {"path": str(path), "sha256": digest(path)}
    return "".join(texts), identities


def verify_vms(directory, files, terminal, allowed, expected_forks=None):
    """Bind actual last-effective JVM caps to closed diagnostics and captured OS fork generations."""
    profiles = {"wrapper": (64, 256), "daemon": (768, 1024), "probe": (32, 256), "generator": (512, 256), "worker": (512, 256), "java_exec": (768, 384), "fork": (768, 384)}
    console = (directory / "console.log").read_text(encoding="utf-8", errors="replace")
    loaded_classes, class_identities = class_loads(directory)
    origins = [json.loads(line.split("paired.jvm.origin ", 1)[1]) for line in console.splitlines() if "paired.jvm.origin " in line]
    records, forks, version_probes = [], [], []
    for path in files:
        require(path.stat().st_size <= 1024 * 1024, "VM diagnostic exceeds its retained bound")
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
            require(any(origin["main"] == main and os.path.normcase(origin["executable"]) == os.path.normcase(str(Path(html.unescape(homes[0]), "bin/java.exe")))
                        and origin["classpath"] == html.unescape(classpaths[0]) for origin in origins), "Unbound actual JavaExec origin")
            role = "java_exec"
        caps = {}
        for name, cap in zip(("MaxHeapSize", "MaxMetaspaceSize"), profiles[role]):
            flags = re.findall(r"(?:^|\s)-XX:" + name + r"=([0-9]+)(?=\s|$)", value)
            require(len(flags) == 1 and int(flags[0]) == cap * 1024**2, f"Changed or unavailable actual finite VM cap: {role}/{name}")
            caps[name] = int(flags[0])
        pid, vm_start = map(int, headers[0])
        member = terminal["members"].get(str(pid))
        actual_executable = Path(html.unescape(homes[0]), "bin/java.exe")
        require(os.path.normcase(str(actual_executable)) in allowed and digest(actual_executable) == allowed[os.path.normcase(str(actual_executable))], "Unknown or changed Java executable")
        evidence = {"pid": pid, "vm_start_ms": vm_start, "role": role, "command": command, "caps": caps, "classpath": html.unescape(classpaths[0]), "home": html.unescape(homes[0]), "diagnostic_sha256": digest(path)}
        if version_probe:
            evidence["version_probe_arguments"] = html.unescape(arguments[0].strip())
            version_probes.append(evidence)
        if role == "fork":
            require(member is not None and member["inOwnedJob"] is True and member["completed"] is True and member["exitCode"] == 0,
                    "Actual JMH fork generation or zero-exit proof missing")
            birth_ms = (member["creationFileTime"] - 116444736000000000) // 10000
            require(0 <= vm_start - birth_ms <= 2000 and os.path.normcase(member["image"]) == os.path.normcase(str(actual_executable)), "JMH fork OS generation and diagnostic disagree")
            require(member["affinityMask"] == terminal["inheritedAffinityMask"] and member["systemAffinityMask"] == terminal["systemAffinityMask"], "JMH fork changed inherited affinity")
            loaded = re.findall(r"(?m)^\s*\[" + str(pid) + r"\]\[info *\]\[class,load *\]\s+org\.openjdk\.jmh\.runner\.ForkedMain source: ([^\r\n]+)", loaded_classes)
            require(len(loaded) == 1, "PID-qualified JMH fork main origin missing or ambiguous")
            evidence["actual_loaded_main"] = loaded[0].strip()
            evidence["os_generation"] = member
            forks.append(pid)
        records.append(evidence)
    for probe in version_probes:
        authorities = [record["pid"] for record in records if record["role"] == "java_exec" and record["home"] == probe["home"]]
        require(any(re.search(r"(?m)^\s*\[" + str(pid) + r"\]\[info *\]\[class,load *\]\s+org\.openjdk\.jmh\.runner\.CompilerHints source: [^\r\n]+", loaded_classes)
                    for pid in authorities), "JMH version probe lacks a bound JavaExec compiler-hints origin")
    require(files and len(forks) == len(set(forks)), "Missing JVM diagnostics or reused JMH fork")
    if expected_forks is not None:
        require(len(forks) == expected_forks, "Incomplete actual JMH fork ownership/caps/affinity matrix")
    require(set(class_identities) <= {record["pid"] for record in records}, "Unbound class-load JVM identity")
    for record in records:
        if record["pid"] in class_identities:
            record["class_load_diagnostic"] = class_identities[record["pid"]]
    write_new(directory / "vms.json", records)


def properties(path, values):
    """Write Java Properties without losing project colons or Windows path separators."""
    def escape(value):
        return value.replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=").replace(" ", "\\ ")
    with path.open("x", encoding="utf-8") as target:
        for key, value in sorted(values.items()):
            target.write(f"{escape(key)}={escape(value)}\n")


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


def preflight_request(root, output, suite, side, classpath_path, runtime, sources, java, inputs):
    """Bind one complete untimed controller request to the actual preserved and qualified input closure."""
    specification = suite["preflight"]
    classpath = prepared_classpath(classpath_path)
    classes = {name: class_entry(classpath, name) for name in [specification["main"],
               "dev.s7a.strata.performance.JvmPerformanceMeter", "org.openjdk.jmh.runner.Runner", *specification["runtime_classes"].values()]}
    for project, name in specification["runtime_classes"].items():
        require(classes[name]["sha256"] == runtime[project]["sha256"], "Preflight class resolves outside its actual runtime archive")
    directory = output / "preflight" / suite["name"] / side
    directory.mkdir(parents=True, exist_ok=False)
    qualification = preserve_file(pin(root, specification["qualification"]), directory / "qualification.json")
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
    vms = {item["pid"]: item for item in read_json(phase_directory / "vms.json")}
    captured_class_identities = {pid: vm["class_load_diagnostic"] for pid, vm in vms.items() if "class_load_diagnostic" in vm}
    require(class_identities == captured_class_identities, "Control changed its captured class-load diagnostic")
    artifacts = set()
    for result in receipt["controls"]:
        require(set(result) == {"id", "status", "observations", "origins", "artifacts"} and result["status"] == "passed"
                and isinstance(result["observations"], dict) and result["observations"], "Failed or unavailable preflight control observations")
        require(isinstance(result["origins"], dict) and set(result["origins"]) == set(request["required_classes"]), "Missing actual control class origins")
        for name, origin in result["origins"].items():
            require(isinstance(origin, dict) and set(origin) == {"pid"} and isinstance(origin["pid"], int)
                    and not isinstance(origin["pid"], bool), "Invalid actual control JVM identity")
            pid = origin["pid"]
            vm = vms.get(pid)
            member = terminal["members"].get(str(pid))
            require(vm is not None and vm["role"] == "java_exec" and member is not None
                    and member["inOwnedJob"] is True and member["completed"] is True and member["exitCode"] == 0
                    and member["affinityMask"] == terminal["inheritedAffinityMask"] and member["systemAffinityMask"] == terminal["systemAffinityMask"],
                    "Preflight observation lacks naturally released owning JVM proof")
            require(os.path.normcase(member["image"]) == os.path.normcase(str(Path(vm["home"], "bin/java.exe"))), "Control loaded origin and OS executable disagree")
            birth_ms = (member["creationFileTime"] - 116444736000000000) // 10000
            require(0 <= vm["vm_start_ms"] - birth_ms <= 2000, "Control OS generation and diagnostic disagree")
            pattern = r"(?m)^\s*\[" + str(pid) + r"\]\[info *\]\[class,load *\]\s+" + re.escape(name) + r" source: ([^\r\n]+)"
            loaded = re.findall(pattern, loaded_classes)
            observed = re.findall(pattern, console)
            require((len(loaded) == 1 and (not observed or observed == loaded))
                    or (not loaded and len(observed) == 1), "Missing or ambiguous PID-qualified control class load")
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
    write_new(directory / "verified.json", {"status": "passed", "request_sha256": digest(request_path), "receipt_sha256": digest(directory / "receipt.json"), "controls": expected})


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


def table(output, comparisons, phases, operations=()):
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


def run(root, manifest_path, output):
    """Own a fresh whole attempt; an exception preserves failure and never adopts completed rows."""
    require(os.name == "nt", "Paired CI requires the Windows owned-process boundary")
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    record = {"schema": "strata-paired-jvm-attempt-v1", "status": "failed", "run_id": os.environ.get("GITHUB_RUN_ID"),
              "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"), "sources": {}, "phases": [], "gpu_window_loaded_game": "N/A"}
    helper = Path(__file__).with_name("invoke-windows.ps1")
    init = Path(__file__).with_name("bounded-jvm.init.gradle.kts")
    try:
        record.update(driver_head=git(root, "rev-parse", "HEAD"), manifest_sha256=digest(manifest_path))
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

        def phase(name, workspace, arguments, expected_forks=None, test_gate=None):
            """Admit a full bounded call with upload reserve, then require natural Job release."""
            nonlocal captured_class_loads
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
                           "STRATA_PAIRED_WORKSPACE": workspace.as_posix(), "STRATA_PAIRED_LOGS": logs, "STRATA_PAIRED_CLASS_LOGS": class_staging.as_posix()}
            args = arguments + common + [f"-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 {flags}"]
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
            terminal = read_json(directory / "terminal.json")
            record["phases"].append({"name": name, "elapsed_seconds": time.monotonic()-phase_started, "exit_code": exit_code, "terminal": terminal,
                                    "available_disk_bytes": shutil.disk_usage(output).free})
            require(exit_code == 0 and terminal["exitCode"] == 0 and terminal["naturalRelease"] is True and terminal["handlesClosed"] is True,
                    f"Owned phase failed: {name}; retained {directory}")
            admission = read_json(directory / "child-admission.json")
            require(admission["inOwnedJob"] is True and admission["jobName"] == terminal["jobName"] and admission["availablePhysicalBytes"] >= 4 * 1024**3,
                    "Missing fresh pre-Java RAM guard inside the actual owning Job")
            if record["phases"]:
                first = record["phases"][0]["terminal"]
                require(terminal["inheritedAffinityMask"] == first["inheritedAffinityMask"] and terminal["systemAffinityMask"] == first["systemAffinityMask"], "Host affinity changed between paired phases")
            verify_vms(directory, sorted(set(vm_directory.glob("*.log"))-previous_vms), terminal, allowed_java, expected_forks)
            for release, expected in allowed_releases.items():
                require(digest(release) == expected, "Pinned Java release bytes changed during invocation")
            return directory

        data = load_manifest(root, manifest_path)
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
        record["driver_files"] = {path.name: digest(path) for path in (Path(__file__), helper, init)}
        workspaces = {}
        for side, source in data["sources"].items():
            identical = next((key for key in workspaces if data["sources"][key] == source), None)
            if identical is None:
                workspaces[side], record["sources"][side] = materialize(root, output, side, source)
            else:
                workspaces[side], record["sources"][side] = workspaces[identical], record["sources"][identical]
        fixture = workspaces["fixture"]
        owners = sorted({suite["project"] for suite in data["suites"]})
        fixture_tasks = [archive_task(":performance-testkit")] + [project + ":jmhCompileGeneratedClasses" for project in owners]
        for side in ("baseline", "candidate"):
            tasks = [archive_task(project) for project in data["runtime_projects"]]
            if workspaces[side] == fixture:
                tasks += fixture_tasks
            phase(f"prepare-{side}", workspaces[side], tasks)
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
        input_archives = {item["label"]: preserve_file(pin(root, item["pin"]), output / "prepared" / "inputs" / f"input-{index}.bin")
                          for index, item in enumerate(data["inputs"])}
        properties(inputs, {label: item["path"] for label, item in input_archives.items()})
        collector = archive_file(fixture, ":performance-testkit")
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
                flags = [f"-Pstrata.paired.runtime={runtime_manifests[side]}"]
                if "preflight" in suite:
                    retained = output / "prepared-classpaths" / suite["name"] / side
                    flags += [f"-Pstrata.paired.preserveClasspath={retained}"]
                    classpaths[side] = retained / "classpath.json"
                phase(f"admit-{suite['name']}-{side}", fixture, [project + ":" + verifier] + flags + selectors)
                if "preflight" in suite:
                    control = preflight_request(root, output, suite, side, classpaths[side], runtime_archives[side], record["sources"], java_identity, input_archives)
                    classpaths[side] = {"path": classpaths[side], "sha256": digest(classpaths[side])}
                    request_hash = digest(control / "request.json")
                    actual = phase(f"preflight-{suite['name']}-{side}", fixture,
                                   [project + ":strataPairedPreflight", f"-Pstrata.paired.template={verifier}",
                                    f"-Pstrata.paired.preflight={control / 'request.json'}", f"-Pstrata.paired.classpath={classpaths[side]['path']}",
                                    f"-Pstrata.paired.runtime={runtime_manifests[side]}"] + selectors, 0)
                    verify_preflight(control, actual, record["phases"][-1]["terminal"], request_hash)
                    require(digest(classpaths[side]["path"]) == classpaths[side]["sha256"], "Control changed its prepared classpath inventory")
            admitted_suites.append((suite, project, task, selectors, classpaths))
        for suite, project, task, selectors, classpaths in admitted_suites:
            bindings = {"baseline": [], "candidate": []}
            for mode in ("avgt", "sample"):
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
                            pin(root, suite["preflight"]["qualification"])
                        destination = output / "raw" / suite["name"] / mode / side / f"run-{repetition}"
                        flags = [f"-Pstrata.performance.mode={mode}", f"-Pstrata.performance.repetition={repetition}",
                                 f"-Pstrata.performance.suite={suite['name']}", f"-Pstrata.paired.runtime={runtime_manifests[side]}"]
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
                        flags = [f"-Pstrata.performance.operationRequest={request_path}", f"-Pstrata.paired.runtime={runtime_manifests[side]}"]
                        if side in classpaths:
                            require(digest(classpaths[side]["path"]) == classpaths[side]["sha256"], "Prepared classpath inventory changed before operation collection")
                            prepared_classpath(classpaths[side]["path"])
                            pin(root, suite["preflight"]["qualification"])
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
        require(len(invocation_ids) == len(data["suites"]) * 12 and len(comparisons) == len(data["suites"]) * 2, "Whole attempt incomplete")
        require(len(operation_ids) == sum("operation_reports" in suite for suite in data["suites"]) * 6, "Supplemental operation attempt incomplete")
        record["score_cells"] = sum(suite["expected_cases"] * 12 for suite in data["suites"])
        record["operation_cells"] = sum(suite["operation_reports"]["expected_rows"] * 6 for suite in data["suites"] if "operation_reports" in suite)
        table(output, comparisons, record["phases"], operation_comparisons)
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
        print(f"Preserved {record['status']} whole attempt: {archive}", flush=True)


def main():
    """Select the opt-in manifest during CI planning or execute one fresh Windows attempt."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=".github/performance/paired-jvm.json")
    parser.add_argument("--select", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    path = relative_path(root, args.manifest)
    if args.select:
        selected = args.manifest if path.exists() else ""
        if selected:
            load_manifest(root, path)
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as target:
                target.write(f"paired_manifest={selected}\n")
        print(selected)
    else:
        require(args.output is not None, "Supply a fresh attempt output directory")
        run(root, path, args.output.resolve())


if __name__ == "__main__":
    main()
