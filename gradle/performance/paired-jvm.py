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
import zipfile


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
        require(set(suite) in (fields, fields | {"profilers"}), "Invalid suite fields")
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
    labels = set()
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


def verify_vms(directory, files, terminal, allowed, expected_forks=None):
    """Bind actual last-effective JVM caps to closed diagnostics and captured OS fork generations."""
    profiles = {"wrapper": (64, 256), "daemon": (768, 1024), "probe": (32, 256), "generator": (512, 256), "worker": (512, 256), "java_exec": (768, 384), "fork": (768, 384)}
    console = (directory / "console.log").read_text(encoding="utf-8", errors="replace")
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
        evidence = {"pid": pid, "role": role, "command": command, "caps": caps, "classpath": html.unescape(classpaths[0]), "home": html.unescape(homes[0]), "diagnostic_sha256": digest(path)}
        if version_probe:
            evidence["version_probe_arguments"] = html.unescape(arguments[0].strip())
            version_probes.append(evidence)
        if role == "fork":
            require(member is not None and member["inOwnedJob"] is True and member["completed"] is True and member["exitCode"] == 0,
                    "Actual JMH fork generation or zero-exit proof missing")
            birth_ms = (member["creationFileTime"] - 116444736000000000) // 10000
            require(0 <= vm_start - birth_ms <= 2000 and os.path.normcase(member["image"]) == os.path.normcase(str(actual_executable)), "JMH fork OS generation and diagnostic disagree")
            require(member["affinityMask"] == terminal["inheritedAffinityMask"] and member["systemAffinityMask"] == terminal["systemAffinityMask"], "JMH fork changed inherited affinity")
            loaded = re.findall(r"(?m)^\s*\[" + str(pid) + r"\]\[info *\]\[class,load *\]\s+org\.openjdk\.jmh\.runner\.ForkedMain source: ([^\r\n]+)", console)
            require(len(loaded) == 1, "PID-qualified JMH fork main origin missing or ambiguous")
            evidence["actual_loaded_main"] = loaded[0].strip()
            evidence["os_generation"] = member
            forks.append(pid)
        records.append(evidence)
    for probe in version_probes:
        authorities = [record["pid"] for record in records if record["role"] == "java_exec" and record["home"] == probe["home"]]
        require(any(re.search(r"(?m)^\s*\[" + str(pid) + r"\]\[info *\]\[class,load *\]\s+org\.openjdk\.jmh\.runner\.CompilerHints source: [^\r\n]+", console)
                    for pid in authorities), "JMH version probe lacks a bound JavaExec compiler-hints origin")
    require(files and len(forks) == len(set(forks)), "Missing JVM diagnostics or reused JMH fork")
    if expected_forks is not None:
        require(len(forks) == expected_forks, "Incomplete actual JMH fork ownership/caps/affinity matrix")
    write_new(directory / "vms.json", records)


def properties(path, values):
    """Write Java Properties without losing project colons or Windows path separators."""
    def escape(value):
        return value.replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=").replace(" ", "\\ ")
    with path.open("x", encoding="utf-8") as target:
        for key, value in sorted(values.items()):
            target.write(f"{escape(key)}={escape(value)}\n")


def table(output, comparisons, phases):
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
        target.write("\nThread CPU per operation is unavailable in the existing collector. Job CPU includes preparation and descendants. Additional declared JMH profiler metrics remain in raw results. CPU native font-library controls require their full work gate and actual library load/release; GPU/window/upload/input-latency/FPS and loaded-game acceptance is N/A for this CPU job. Cross-environment timing is not a runtime improvement percentage.\n")


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
        vm_directory = output / "vms"
        vm_directory.mkdir()
        common = ["-Pstrata.jvmOnly=true", "--no-daemon", "--no-parallel", "--max-workers=1", "--no-build-cache", "--no-configuration-cache", "--console=plain",
                  "-Pkotlin.compiler.execution.strategy=in-process", "-Pdetekt.use.worker.api=false", "-Dorg.gradle.java.installations.auto-detect=false",
                  "-Dorg.gradle.java.installations.auto-download=false", "-Dorg.gradle.java.installations.fromEnv=", "-Dorg.gradle.java.installations.paths=" + ",".join(homes), "-I", str(init)]

        def phase(name, workspace, arguments, expected_forks=None):
            """Admit a full bounded call with upload reserve, then require natural Job release."""
            require(time.monotonic() - started + 1830 + 180 < 6 * 60 * 60, "Insufficient job time for the full next invocation and upload reserve")
            directory = output / "phases" / name
            directory.mkdir(parents=True, exist_ok=False)
            logs = vm_directory.as_posix()
            flags = f"-XX:+UnlockDiagnosticVMOptions -XX:+PrintCommandLineFlags -XX:-DisplayVMOutput -XX:+LogVMOutput -XX:LogFile={logs}/vm-%p-%t.log"
            environment = {"JAVA_OPTS": "-Xmx64m -XX:MaxMetaspaceSize=256m", "GRADLE_OPTS": "", "JAVA_TOOL_OPTIONS": f"-Xmx32m -XX:MaxMetaspaceSize=256m {flags}", "JDK_JAVA_OPTIONS": "", "_JAVA_OPTIONS": "",
                           "STRATA_PAIRED_WORKSPACE": workspace.as_posix(), "STRATA_PAIRED_LOGS": logs}
            args = arguments + common + [f"-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 {flags}"]
            request = {"workspace": str(workspace), "arguments": args, "environment": environment, "minimumFreePhysicalBytes": 4 * 1024**3,
                       "jobCommittedBytes": 3 * 1024**3, "workSeconds": 1800, "cleanupSeconds": 30}
            write_new(directory / "request.json", request)
            phase_started = time.monotonic()
            previous_vms = set(vm_directory.glob("*.log"))
            exit_code = invoke(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(helper), "-Request", str(directory / "request.json"), "-Report", str(directory / "terminal.json")], root, directory / "console.log")
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
        runtime_manifests = {}
        record["archives"] = {}
        for side in ("baseline", "candidate"):
            mapping = {project: archive_file(workspaces[side], project).as_posix() for project in data["runtime_projects"]}
            runtime_manifests[side] = output / f"runtime-{side}.properties"
            properties(runtime_manifests[side], mapping)
            record["archives"][side] = {project: digest(Path(path)) for project, path in mapping.items()}
        inputs = output / "fixture-inputs.properties"
        properties(inputs, {item["label"]: pin(root, item["pin"]).as_posix() for item in data["inputs"]})
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
        comparisons, invocation_ids = [], set()
        for suite in data["suites"]:
            project, task, verifier = FixtureProject.decode(suite["project"]).value
            selectors = [f"-Pstrata.performance.{key}={','.join(suite[key])}" for key in ("benchmarks", "workloads") if suite[key]]
            parameters = output / (suite["name"] + "-parameters.properties")
            if suite["parameters"]:
                properties(parameters, {key: ",".join(values) for key, values in suite["parameters"].items()})
                selectors.append(f"-Pstrata.performance.parameters={parameters}")
            selectors += [f"-Pstrata.performance.fixtureInputs={inputs}"]
            for side in ("baseline", "candidate"):
                phase(f"admit-{suite['name']}-{side}", fixture, [project + ":" + verifier, f"-Pstrata.paired.runtime={runtime_manifests[side]}"] + selectors)
            for mode in ("avgt", "sample"):
                runs = {"baseline": [], "candidate": []}
                for repetition in range(3):
                    for side in ("baseline", "candidate"):
                        for key, workspace in workspaces.items():
                            source_guard(workspace, data["sources"][key]["tree"])
                        destination = output / "raw" / suite["name"] / mode / side / f"run-{repetition}"
                        flags = [f"-Pstrata.performance.mode={mode}", f"-Pstrata.performance.repetition={repetition}",
                                 f"-Pstrata.performance.suite={suite['name']}", f"-Pstrata.paired.runtime={runtime_manifests[side]}"]
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
                request_path = output / f"{suite['name']}-{mode}-request.json"
                comparison_path = output / f"{suite['name']}-{mode}-comparison.json"
                write_new(request_path, {"command": "jmh-comparison", "collector": str(collector), "baseline": runs["baseline"], "candidate": runs["candidate"],
                                         "output": str(comparison_path), "repetitions": 3})
                phase(f"compare-{suite['name']}-{mode}", fixture, [":performance-testkit:processEvidence", f"-Pstrata.performance.request={request_path}"])
                comparison = read_json(comparison_path)
                require(comparison["status"] == "passed" and len(comparison["cases"]) == suite["expected_cases"], "Incomplete paired comparison")
                comparisons.append((suite["name"], mode, comparison))
        for side, workspace in workspaces.items():
            source_guard(workspace, data["sources"][side]["tree"])
            for value in data["compiled_pins"][side]:
                pin(workspace, value)
        for item in data["dependency_pins"]:
            pin(gradle_cache, item)
        require(len(invocation_ids) == len(data["suites"]) * 12 and len(comparisons) == len(data["suites"]) * 2, "Whole attempt incomplete")
        record["score_cells"] = sum(suite["expected_cases"] * 12 for suite in data["suites"])
        table(output, comparisons, record["phases"])
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
