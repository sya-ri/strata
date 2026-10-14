"""Collect complete Web pairs through the shipped browser adapters and shared processor."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import tomllib


BUILD = "integration/web/build"
DRIVER = "performance-testkit/src/jsMain/resources/browser-performance.mjs"
PROCESSOR = "performance-testkit/src/jsMain/resources/browser-performance-reports.mjs"
ENGINES = ["chromium", "firefox", "webkit"]
TOKENS = {"build", "site", "collector", "output", "inputs", "workspace", "side"}


def load_manifest(shared, root, data):
    """Decode immutable existing adapters, complete matrices and fixed profiles; no timing thresholds."""
    require = shared.require
    fields = {"schema", "sources", "runtime_files", "inputs", "collections", "compiled_pins", "java_pins", "collector_path", "correctness"}
    require(set(data) == fields, "Invalid browser manifest fields")
    require(set(data["sources"]) == {"fixture", "baseline", "candidate"}, "Supply all three browser source bindings")
    for source in data["sources"].values():
        require(set(source) == {"ref", "tree", "patches"}
                and all(re.fullmatch(r"[0-9a-f]{40}", source[key]) for key in ("ref", "tree"))
                and isinstance(source["patches"], list), "Invalid browser source binding")
        for patch in source["patches"]:
            shared.pin(root, patch)
    runtime = data["runtime_files"]
    require(isinstance(runtime, list) and len(runtime) == len(set(runtime)), "Duplicate browser runtime file")
    for path in runtime:
        shared.relative_path(root, path)
        require(path.startswith("runtime/web/src/jsMain/") and path.endswith(".kt"), "Only declared Web production files may differ")
    shared.relative_path(root, data["collector_path"])
    require(data["collector_path"].startswith("performance-testkit/build/libs/") and data["collector_path"].endswith(".jar"), "Use the linked testkit JS artifact")
    labels = set()
    require(isinstance(data["inputs"], list) and data["inputs"], "Declare immutable browser inputs")
    for item in data["inputs"]:
        require(set(item) == {"label", "pin"} and re.fullmatch(r"[A-Za-z0-9_.-]+", item["label"])
                and item["label"] not in labels, "Invalid or duplicate browser input")
        labels.add(item["label"])
        shared.pin(root, item["pin"])
    require(set(data["compiled_pins"]) == {"baseline", "candidate"}, "Supply both prepared Web inventories")
    for pins in data["compiled_pins"].values():
        require(isinstance(pins, list) and pins, "Declare actual compiled Web pins")
        paths = [item["path"] for item in pins]
        require(len(paths) == len(set(paths)) and {data["collector_path"], BUILD + "/dist/js/productionExecutable/application.js"} <= set(paths), "Pin the actual linked collector and application")
        for item in pins:
            require(set(item) == {"path", "sha256"} and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]), "Invalid compiled Web pin")
            shared.relative_path(root, item["path"])
    require(isinstance(data["java_pins"], list) and {item["role"] for item in data["java_pins"]} == {"toolchain", "launcher"}
            and len(data["java_pins"]) == 2, "Pin both catalog Java roles")
    for item in data["java_pins"]:
        require(set(item) == {"role", "executable_sha256", "release_sha256"}
                and all(re.fullmatch(r"[0-9a-f]{64}", item[key]) for key in ("executable_sha256", "release_sha256")), "Invalid Java byte pin")
    correctness = data["correctness"]
    require(set(correctness) == {"required_tests", "files"}, "Declare full correctness evidence")
    tests = correctness["required_tests"]
    require(isinstance(tests, list) and tests and len(tests) == len(set(tests))
            and all(re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_.$]*#[^#\r\n\x00]+", item) for item in tests), "Declare exact JVM parity tests")
    require(isinstance(correctness["files"], list) and correctness["files"], "Declare complete correctness file counts")
    patterns = set()
    for item in correctness["files"]:
        require(set(item) == {"glob", "count"} and re.fullmatch(r"parity/[A-Za-z0-9_.*?/-]+", item["glob"])
                and ".." not in item["glob"] and item["glob"] not in patterns
                and isinstance(item["count"], int) and not isinstance(item["count"], bool) and 0 < item["count"], "Invalid correctness file inventory")
        patterns.add(item["glob"])
    require(isinstance(data["collections"], list) and data["collections"], "Declare the whole browser collection union")
    names, rows = set(), set()
    for collection in data["collections"]:
        required = {"name", "adapter", "arguments", "input_paths", "scenarios", "conditions", "expected_cases"}
        require(set(collection) == required and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", collection["name"])
                and collection["name"] not in names, "Invalid or duplicate browser collection")
        names.add(collection["name"])
        shared.pin(root, collection["adapter"])
        require(collection["adapter"]["path"].startswith("tools/web/") and collection["adapter"]["path"].endswith(".mjs"), "Use an existing source-pinned Web adapter")
        arguments = collection["arguments"]
        require(isinstance(arguments, list) and arguments and len(arguments) <= 32, "Invalid browser adapter arguments")
        for value in arguments:
            require(isinstance(value, str) and 0 < len(value) <= 4096 and "\x00" not in value, "Invalid browser argument")
            require(all(token in TOKENS for token in re.findall(r"\{([^{}]+)\}", value))
                    and re.sub(r"\{[a-z]+\}", "", value).find("{") == -1 and re.sub(r"\{[a-z]+\}", "", value).find("}") == -1, "Unknown browser path role")
        require("{output}" in arguments and "{collector}" in arguments and "{inputs}" in arguments, "Bind actual output, collector and immutable inputs")
        inputs = collection["input_paths"]
        require(isinstance(inputs, dict) and inputs and labels <= set(inputs), "Retain every declared browser input label")
        for label, binding in inputs.items():
            require(re.fullmatch(r"[A-Za-z0-9_.-]+", label) and set(binding) == {"scope", "path"}
                    and binding["scope"] in ("workspace", "evidence"), "Invalid browser comparison input")
            shared.relative_path(root, binding["path"])
        conditions = collection["conditions"]
        require(isinstance(conditions, dict) and conditions.get("viewport") == {"width": 640, "height": 480}
                and conditions.get("warmup") == 30 and conditions.get("samples") == 60, "Keep the original full browser profile")
        scenarios = collection["scenarios"]
        require(isinstance(scenarios, list) and scenarios, "Missing browser scenario inventory")
        cases = 0
        ids = set()
        for scenario in scenarios:
            require(set(scenario) == {"id", "phases"} and isinstance(scenario["id"], str) and re.fullmatch(r"[A-Za-z0-9_.-]+", scenario["id"])
                    and scenario["id"] not in ids, "Duplicate or invalid browser scenario")
            ids.add(scenario["id"])
            phases = scenario["phases"]
            require(isinstance(phases, list) and phases and len(phases) == len(set(phases))
                    and all(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.-]+", value) for value in phases), "Invalid browser phase inventory")
            for phase in phases:
                require((scenario["id"], phase) not in rows, "Duplicated case across browser collections")
                rows.add((scenario["id"], phase))
            cases += len(phases) * len(ENGINES)
        require(type(collection["expected_cases"]) is int and collection["expected_cases"] == cases, "Incomplete browser case count")
    return data


def expand_arguments(collection, roles):
    """Resolve structured file roles without a shell or a private workload registry."""
    return [value.format_map(roles) for value in collection["arguments"]]


def validate_terminal(shared, exit_code, terminal, admission):
    """A successful command requires natural descendant release and a fresh in-Job admission."""
    shared.require(exit_code == 0 and terminal["exitCode"] == 0 and terminal["naturalRelease"] is True
                   and terminal["handlesClosed"] is True and terminal["activeProcessesAfterCleanup"] == 0
                   and "error" not in terminal, "Owned browser phase failed")
    shared.require(admission["inOwnedJob"] is True and admission["jobName"] == terminal["jobName"]
                   and 4 * 1024**3 <= admission["availablePhysicalBytes"], "Missing actual fresh owned admission")


def common_sources(shared, workspaces, data):
    """Both runtime variants retain the exact common fixture, adapter and build source closure."""
    inventories = []
    for side in ("fixture", "baseline", "candidate"):
        files = shared.source_files(workspaces[side], data["sources"][side]["tree"])
        inventories.append({path: value for path, value in files.items() if path not in data["runtime_files"]})
    shared.require(inventories[0] == inventories[1] == inventories[2], "Common browser fixture or tooling source changed")


def validate_node_generation(shared, terminal, program):
    """Bind the pinned Node launcher to its actual owned OS generation and successful release."""
    members = [member for generations in terminal["members"].values() for member in generations]
    shared.require(not any(Path(member["image"]).name.casefold() in ("java.exe", "javaw.exe") for member in members), "Java started inside a browser collection phase")
    nodes = [member for member in members if Path(member["image"]).name.casefold() == "node.exe"]
    shared.require(len(nodes) == 1, "Missing or ambiguous actual Node generation")
    member = nodes[0]
    shared.require(os.path.normcase(member["image"]) == os.path.normcase(program["path"])
                   and member.get("primaryBeforeResume") is True and member["pid"] == terminal["callerPid"]
                   and member["inOwnedJob"] is True and member["completed"] is True and member["handleClosed"] is True
                   and member["exitCode"] == 0 and member["creationFileTime"] <= member["exitFileTime"]
                   and member["affinityMask"] == terminal["inheritedAffinityMask"] and member["systemAffinityMask"] == terminal["systemAffinityMask"], "Wrong, changed or unclosed actual Node generation")
    admission = terminal["primaryAdmission"]
    shared.require(admission["beforeResume"] is True and admission["pid"] == member["pid"]
                   and admission["creationFileTime"] == member["creationFileTime"] and admission["image"] == member["image"]
                   and admission["jobName"] == terminal["jobName"] and admission["inOwnedJob"] is True
                   and 4 * 1024**3 <= admission["availablePhysicalBytes"], "Node primary lacked actual admission before resume")


def run(shared, root, selection, output):
    """Own one fresh complete attempt; preserve raw inputs, phases, failures and full parity trees."""
    shared.require(os.name == "nt", "Browser CI requires the existing Windows owned-process boundary")
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    record = {"schema": "strata-paired-browser-attempt-v1", "status": "failed", "run_id": os.environ.get("GITHUB_RUN_ID"),
              "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"), "sources": {}, "phases": [], "cpu_ns": None, "allocated_bytes": None,
              "gpu_loaded_game": "N/A"}
    workspaces = {}
    try:
        shared.require(shared.git(root, "rev-parse", "HEAD") == selection["execution_head"], "Browser driver head changed")
        data = shared.load_manifest(selection["root"], selection["path"])
        record.update(driver_head=selection["execution_head"], input_producer=selection["producer"], manifest_sha256=shared.digest(selection["path"]))
        record["driver_sources"] = shared.preserve_driver_sources(root, output, ("paired-browser.py",))
        shutil.copyfile(selection["path"], output / "manifest.json")
        homes = {"toolchain": Path(os.environ["STRATA_PAIRED_TOOLCHAIN"]), "launcher": Path(os.environ["JAVA_HOME"])}
        versions = tomllib.loads((root / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
        allowed_java = {}
        for item in data["java_pins"]:
            home = homes[item["role"]]
            release = dict(line.split("=", 1) for line in (home / "release").read_text(encoding="utf-8").splitlines() if "=" in line)
            provider, version = (("Eclipse Adoptium", versions["performance-toolchain"].split("+", 1)[0]) if item["role"] == "toolchain"
                                 else ("Oracle Corporation", versions["performance-launcher"]))
            shared.require(release["IMPLEMENTOR"].strip('"') == provider and release["JAVA_VERSION"].strip('"') == version
                           and shared.digest(home / "bin/java.exe") == item["executable_sha256"]
                           and shared.digest(home / "release") == item["release_sha256"], "Actual Java differs from the pinned catalog profile")
            allowed_java[os.path.normcase(str(home / "bin/java.exe"))] = item["executable_sha256"]
        node_launcher = shutil.which("node")
        shared.require(node_launcher is not None, "Locate the installed Node launcher")
        node_identity = json.loads(subprocess.check_output([node_launcher, "-p", "JSON.stringify({path:process.execPath,version:process.version})"], text=True))
        node = Path(node_identity["path"])
        shared.require(node.is_absolute() and node.name.lower() == "node.exe", "Locate the actual Node executable")
        program = {"kind": "node", "path": str(node), "sha256": shared.digest(node)}
        record["node"] = {**program, "version": node_identity["version"], "launcher": node_launcher}
        record["executables"] = [{"path": str(path), "sha256": shared.digest(path)}
                                 for path in (Path(sys.executable), Path(shutil.which("pwsh")), node, *[home / "bin/java.exe" for home in homes.values()])]
        shared.write_new(output / "java-installations.json", {role: {"home": str(home), "release": (home / "release").read_text(encoding="utf-8")} for role, home in homes.items()})
        hardware = subprocess.check_output(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "@{processors=@(Get-CimInstance Win32_Processor | Select-Object Name,Manufacturer,NumberOfCores,NumberOfLogicalProcessors); os=(Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,BuildNumber,TotalVisibleMemorySize); power=(powercfg /getactivescheme)} | ConvertTo-Json -Depth 4"], text=True, encoding="utf-8-sig")
        shared.write_new(output / "host.json", json.loads(hardware))
        for side in ("baseline", "candidate"):
            workspaces[side], record["sources"][side] = shared.materialize(root, output, side, data["sources"][side], selection["root"])
        if data["sources"]["fixture"] == data["sources"]["candidate"]:
            workspaces["fixture"], record["sources"]["fixture"] = workspaces["candidate"], record["sources"]["candidate"]
        else:
            workspaces["fixture"], record["sources"]["fixture"] = shared.materialize(root, output, "fixture", data["sources"]["fixture"], selection["root"])
        common_sources(shared, workspaces, data)
        if selection["producer"]:
            shared.require(shared.source_files(workspaces["candidate"], data["sources"]["candidate"]["tree"]) == shared.source_files(root, record["driver_head"]), "Browser candidate differs from execution head")
        inputs = {}
        for index, item in enumerate(data["inputs"]):
            preserved = shared.preserve_file(shared.pin(selection["root"], item["pin"]), output / "inputs" / f"input-{index}.bin")
            inputs[item["label"]] = preserved["path"]
        shared.write_new(output / "fixture-inputs.json", inputs)
        vms = output / "vms"
        vms.mkdir()
        class_staging = output / "class-loads"
        class_staging.mkdir()
        captured_class_loads = {}
        prepared = set()

        def phase(name, workspace, arguments, node_program=None, test_gate=None):
            """Reuse the existing owning helper and fixed limits for Gradle or a pinned Node adapter."""
            nonlocal captured_class_loads
            shared.require(time.monotonic() - started + 1830 + 180 < 6 * 60 * 60, "Insufficient whole-attempt time and upload reserve")
            shared.verify_driver_sources(record["driver_sources"])
            for side in workspaces:
                shared.source_guard(workspaces[side], data["sources"][side]["tree"])
            for side in prepared:
                for item in data["compiled_pins"][side]:
                    shared.pin(workspaces[side], item)
            for item in data["java_pins"]:
                home = homes[item["role"]]
                shared.require(shared.digest(home / "bin/java.exe") == item["executable_sha256"] and shared.digest(home / "release") == item["release_sha256"], "Java bytes changed")
            shared.require(shared.digest(node) == program["sha256"], "Node bytes changed")
            directory = output / "phases" / name
            directory.mkdir(parents=True, exist_ok=False)
            (directory / "class-loads").mkdir()
            flags = f"-XX:+UnlockDiagnosticVMOptions -XX:+PrintCommandLineFlags -XX:-DisplayVMOutput -XX:+LogVMOutput -XX:LogFile={vms.as_posix()}/vm-%p-%t.log"
            environment = {"JAVA_OPTS": "-Xmx64m -XX:MaxMetaspaceSize=256m", "GRADLE_OPTS": "", "JAVA_TOOL_OPTIONS": f"-Xmx32m -XX:MaxMetaspaceSize=256m {flags}", "JDK_JAVA_OPTIONS": "", "_JAVA_OPTIONS": "", "STRATA_PAIRED_WORKSPACE": workspace.as_posix(), "STRATA_PAIRED_LOGS": vms.as_posix(), "STRATA_PAIRED_CLASS_LOGS": class_staging.as_posix()}
            if node_program is None:
                arguments = arguments + ["--no-daemon", "--no-parallel", "--max-workers=1", "--no-build-cache", "--no-configuration-cache", "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process", "-Pdetekt.use.worker.api=false", "-Dorg.gradle.java.installations.auto-detect=false", "-Dorg.gradle.java.installations.auto-download=false", "-Dorg.gradle.java.installations.fromEnv=", "-Dorg.gradle.java.installations.paths=" + ",".join(str(home) for home in homes.values()), "-I", str(root / "gradle/performance/bounded-jvm.init.gradle.kts"), f"-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 {flags}"]
            request = {"workspace": str(workspace), "arguments": arguments, "environment": environment, "minimumFreePhysicalBytes": 4 * 1024**3, "jobCommittedBytes": 3 * 1024**3, "workSeconds": 1800, "cleanupSeconds": 30}
            if node_program is not None:
                request["program"] = node_program
            shared.write_new(directory / "request.json", request)
            before = set(vms.glob("*.log"))
            previous_classes = shared.class_load_inventory(class_staging, captured_class_loads)
            phase_started = time.monotonic()
            try:
                exit_code = shared.invoke(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(root / "gradle/performance/invoke-windows.ps1"), "-Request", str(directory / "request.json"), "-Report", str(directory / "terminal.json")], root, directory / "console.log")
            finally:
                try:
                    captured_class_loads = shared.preserve_class_loads(class_staging, directory, previous_classes)
                finally:
                    if test_gate is not None:
                        shared.preserve_test_reports(workspace, directory, test_gate)
            terminal = shared.read_json(directory / "terminal.json")
            record["phases"].append({"name": name, "elapsed_seconds": time.monotonic() - phase_started, "exit_code": exit_code, "terminal": terminal})
            validate_terminal(shared, exit_code, terminal, shared.read_json(directory / "child-admission.json"))
            if len(record["phases"]) != 1:
                first = record["phases"][0]["terminal"]
                shared.require(terminal["inheritedAffinityMask"] == first["inheritedAffinityMask"] and terminal["systemAffinityMask"] == first["systemAffinityMask"], "Host affinity changed")
            if node_program is None:
                shared.verify_vms(directory, sorted(set(vms.glob("*.log")) - before), terminal, allowed_java)
            else:
                validate_node_generation(shared, terminal, node_program)
                shared.require(shared.read_json(directory / "child-admission.json") == terminal["primaryAdmission"], "Node admission record differs from its suspended primary")
            if test_gate is not None:
                shared.verify_test_gate(directory, test_gate)
                shared.require(shared.read_json(directory / "junit-verified.json")["counts"]["skipped"] == 0, "JVM parity tests were skipped")
            shared.verify_driver_sources(record["driver_sources"])
            for side in workspaces:
                shared.source_guard(workspaces[side], data["sources"][side]["tree"])
            for side in prepared:
                for item in data["compiled_pins"][side]:
                    shared.pin(workspaces[side], item)
            return directory

        collectors = {}
        for side in ("baseline", "candidate"):
            workspace = workspaces[side]
            gate = {"task": ":integration:web:jvmTest", "required_tests": data["correctness"]["required_tests"]}
            phase("prepare-" + side, workspace, [":integration:web:jsBrowserDistribution", ":integration:web:jvmTest", ":integration:web:installWebBrowsers", ":performance-testkit:jsJar", ":performance-testkit:verifyBrowserPerformanceDriver"], test_gate=gate)
            for item in data["compiled_pins"][side]:
                shared.pin(workspace, item)
            prepared.add(side)
            collectors[side] = shared.relative_path(workspace, data["collector_path"])
            phase("build-" + side, workspace, [str(workspace / "tools/web/build.mjs"), "build", str(workspace / BUILD)], program)
            shared.require(shared.digest(workspace / BUILD / "site/application.js") == shared.digest(workspace / BUILD / "dist/js/productionExecutable/application.js"), "Built application differs from its actual linked bundle")
            phase("verify-" + side, workspace, [str(workspace / "tools/web/build.mjs"), "verify", str(workspace / BUILD), side], program)
            for item in data["correctness"]["files"]:
                matches = list((workspace / BUILD).glob(item["glob"]))
                shared.require(len(matches) == item["count"] and all(path.is_file() and not path.is_symlink() for path in matches), "Incomplete actual correctness file inventory")
        shared.require(shared.digest(collectors["baseline"]) == shared.digest(collectors["candidate"]), "Linked browser collector changed across variants")
        comparisons = []
        for collection in data["collections"]:
            raw, paths = {}, {}
            for side in ("baseline", "candidate"):
                workspace = workspaces[side]
                shared.pin(workspace, collection["adapter"])
                raw[side] = output / (collection["name"] + "-" + side + ".json")
                roles = {"workspace": str(workspace), "side": side, "build": str(workspace / BUILD), "site": str(workspace / BUILD / "site"), "collector": str(collectors[side]), "output": str(raw[side]), "inputs": str(output / "fixture-inputs.json")}
                phase("collect-" + collection["name"] + "-" + side, workspace, [str(workspace / collection["adapter"]["path"]), *expand_arguments(collection, roles)], program)
                paths[side] = {name: str(shared.relative_path(workspace if binding["scope"] == "workspace" else output, binding["path"])) for name, binding in collection["input_paths"].items()}
            for label in paths["baseline"]:
                shared.require(shared.digest(Path(paths["baseline"][label])) == shared.digest(Path(paths["candidate"][label])), "Controlled browser input differs across variants")
            for label, path in inputs.items():
                shared.require(shared.digest(Path(paths["candidate"][label])) == shared.digest(Path(path)), "Declared immutable browser input was replaced")
            candidate = workspaces["candidate"]
            request = {"baselinePath": str(raw["baseline"]), "candidatePath": str(raw["candidate"]), "collectorPath": str(collectors["candidate"]), "driverPath": str(candidate / DRIVER), "baselineTargetPath": str(workspaces["baseline"] / BUILD / "site/application.js"), "candidateTargetPath": str(candidate / BUILD / "site/application.js"), "inputPaths": paths["candidate"], "engines": ENGINES, "scenarios": collection["scenarios"], "conditions": collection["conditions"], "outputPath": str(output / (collection["name"] + "-comparison.json"))}
            request_path = output / (collection["name"] + "-comparison-request.json")
            shared.write_new(request_path, request)
            phase("compare-" + collection["name"], candidate, [str(candidate / PROCESSOR), str(request_path)], program)
            comparison = shared.read_json(Path(request["outputPath"]))
            shared.require(comparison["status"] == "passed" and len(comparison["rows"]) == collection["expected_cases"], "Incomplete shared browser comparison")
            comparisons.append({"name": collection["name"], "rows": len(comparison["rows"]), **shared.preserve_file(Path(request["outputPath"]), output / "comparisons" / (collection["name"] + ".json"))})
        table(output, comparisons)
        record.update(status="passed", comparisons=comparisons, expected_cases=sum(item["expected_cases"] for item in data["collections"]), measured_browser_invocations=len(data["collections"]) * len(ENGINES) * 3 * 2)
    except BaseException as failure:
        record["error"] = f"{type(failure).__name__}: {failure}"
        raise
    finally:
        execution_failed = "error" in record
        preservation_errors = []
        for side in ("baseline", "candidate"):
            if side in workspaces:
                workspace = workspaces[side]
                for directory in (workspace / BUILD / "parity", workspace / BUILD / "site", workspace / "performance-testkit/build/libs"):
                    try:
                        paths = sorted(directory.rglob("*")) if directory.is_dir() else []
                    except OSError as failure:
                        preservation_errors.append({"source": str(directory), "error": f"{type(failure).__name__}: {failure}"})
                        continue
                    for path in paths:
                        target = output / "prepared" / side / path.relative_to(workspace)
                        try:
                            if path.is_file():
                                shared.preserve_file(path, target)
                        except Exception as failure:
                            preservation_errors.append({"source": str(path), "target": str(target), "error": f"{type(failure).__name__}: {failure}"})
        if preservation_errors:
            record.update(status="failed", preservation_errors=preservation_errors)
            if execution_failed is False:
                record["error"] = "Prepared evidence preservation failed"
        record["elapsed_seconds"] = time.monotonic() - started
        shared.write_new(output / "attempt.json", record)
        shared.write_new(output / "file-inventory.json", {path.relative_to(output).as_posix(): {"sha256": shared.digest(path), "bytes": path.stat().st_size}
                                                         for path in sorted(output.rglob("*")) if path.is_file() and path.relative_to(output).parts[0] != "workspaces"})
        archive = shared.pack(output)
        print(f"Preserved {record['status']} browser attempt: {archive}", flush=True)
        if preservation_errors and execution_failed is False:
            raise ValueError(record["error"])


def table(output, comparisons):
    """Render all existing processor metrics and adverse absolute deltas without new aggregation."""
    with (output / "report.md").open("x", encoding="utf-8") as target:
        target.write("# Complete paired Web comparison\n\nAll values retain the existing collector's operation-wall or action-to-animation-frame scope, 30 warmups, 60 samples and three independent runs per side. Positive elapsed deltas remain visible. CPU/allocation/GPU/FPS and loaded-game gains are unavailable. Native work counts remain separate untimed observations.\n\n")
        target.write("| Collection | Engine | Scenario | Phase | Metric | Unit | Baseline runs | Candidate runs | Baseline median | Candidate median | Absolute delta | Candidate/baseline |\n| --- | --- | --- | --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: |\n")
        for item in comparisons:
            report = json.loads(Path(item["path"]).read_text(encoding="utf-8"))
            for row in report["rows"]:
                metrics = [(f"{distribution}.{field}", "ns", value) for distribution in ("operation_wall", "action_to_animation_frame") for field, value in row[distribution].items()]
                metrics.extend(("work_counts." + name, "count", value) for name, value in (row["work_counts"] or {}).items())
                for name, unit, value in metrics:
                    cells = [item["name"], row["engine"], row["scenario"], row["phase"], name, unit, json.dumps(value["baseline_runs"]), json.dumps(value["candidate_runs"]), str(value["baseline"]), str(value["candidate"]), str(value["delta"]), str(value["candidate_to_baseline"])]
                    target.write("| " + " | ".join(cell.replace("|", "&#124;").replace("\n", " ") for cell in cells) + " |\n")
