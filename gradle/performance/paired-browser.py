"""Collect complete Web pairs through the shipped browser adapters and shared processor."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import tomllib
from xml.etree import ElementTree


BUILD = "integration/web/build"
DRIVER = "performance-testkit/src/jsMain/resources/browser-performance.mjs"
PROCESSOR = "performance-testkit/src/jsMain/resources/browser-performance-reports.mjs"
ENGINES = ["chromium", "firefox", "webkit"]
TOKENS = {"build", "site", "collector", "output", "inputs", "workspace", "side"}


def load_manifest(shared, root, data):
    """Decode immutable existing adapters, complete matrices and fixed profiles; no timing thresholds."""
    if data["schema"] == "strata-source-preparation-ci-v1":
        return load_source_manifest(shared, root, data)
    require = shared.require
    fields = {"schema", "sources", "runtime_files", "inputs", "collections", "compiled_pins", "java_pins", "collector_path", "correctness"}
    require(fields <= set(data) <= fields | {"driver_source", "compiled_inputs"}, "Invalid browser manifest fields")
    if "driver_source" in data:
        driver = data["driver_source"]
        require(isinstance(driver, dict) and set(driver) == {"ref", "tree"}
                and all(isinstance(driver[key], str) and re.fullmatch(r"[0-9a-f]{40}", driver[key]) for key in ("ref", "tree")),
                "Pin the exact frozen driver commit and tree")
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
    require(data["collector_path"].startswith("performance-testkit/build/libs/") and data["collector_path"].endswith((".jar", ".klib")), "Use the linked testkit JS artifact")
    labels = set()
    require(isinstance(data["inputs"], list) and data["inputs"], "Declare immutable browser inputs")
    for item in data["inputs"]:
        require({"label", "pin"} <= set(item) <= {"label", "pin", "layout"} and re.fullmatch(r"[A-Za-z0-9_.-]+", item["label"])
                and item["label"] not in labels, "Invalid or duplicate browser input")
        labels.add(item["label"])
        shared.pin(root, item["pin"])
    shared.input_paths(data["inputs"])
    require(set(data["compiled_pins"]) == {"baseline", "candidate"}, "Supply both prepared Web inventories")
    for pins in data["compiled_pins"].values():
        require(isinstance(pins, list) and pins, "Declare actual compiled Web pins")
        paths = [item["path"] for item in pins]
        require(len(paths) == len(set(paths)) and {data["collector_path"], BUILD + "/dist/js/productionExecutable/application.js"} <= set(paths), "Pin the actual linked collector and application")
        for item in pins:
            require(set(item) == {"path", "sha256"} and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]), "Invalid compiled Web pin")
            shared.relative_path(root, item["path"])
    if "compiled_inputs" in data:
        bindings = data["compiled_inputs"]
        require(isinstance(bindings, dict) and set(bindings) == {"baseline", "candidate"}, "Bind both compiled input variants")
        input_pins = {item["label"]: item["pin"] for item in data["inputs"]}
        for side, entries in bindings.items():
            require(isinstance(entries, dict) and entries, "Declare finite compiled input bindings")
            compiled = {item["path"]: item["sha256"] for item in data["compiled_pins"][side]}
            for path, label in entries.items():
                require(path in compiled and "/build/" in "/" + path and isinstance(label, str) and label in input_pins
                        and input_pins[label]["sha256"] == compiled[path], "Compiled input must match its declared output pin")
                shared.relative_path(root, path)
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
        adapter = collection["adapter"]
        require(isinstance(adapter, dict) and set(adapter) == {"path", "sha256"}
                and isinstance(adapter["path"], str) and isinstance(adapter["sha256"], str)
                and re.fullmatch(r"[0-9a-f]{64}", adapter["sha256"]), "Invalid browser adapter pin")
        shared.relative_path(root, adapter["path"])
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


def stage_compiled_inputs(shared, workspace, directory, pins, bindings, inputs):
    """Consume exact frozen Source outputs after full preparation, preserving every replaced generated byte."""
    if not bindings:
        return
    expected = {item["path"]: item["sha256"] for item in pins}
    records = []
    for path, label in sorted(bindings.items()):
        target = shared.relative_path(workspace, path)
        source = Path(inputs[label])
        shared.require(target.is_file() and not target.is_symlink(), "Compiled input requires an actual prepared regular output")
        shared.require(source.is_file() and not source.is_symlink() and shared.digest(source) == expected[path], "Frozen compiled input bytes changed")
        generated = shared.preserve_file(target, directory / "generated-compiled" / path)
        shutil.copyfile(source, target)
        shared.require(shared.digest(target) == expected[path], "Consumed compiled input differs from its declared pin")
        records.append({"path": path, "input_label": label, "consumed_sha256": expected[path], "generated": generated})
    shared.write_new(directory / "compiled-inputs.json", records)


def load_source_manifest(shared, root, data):
    """Admit complete finite Source invocations; actual output pins are captured only after ownership closes."""
    require = shared.require
    fields = {"schema", "sources", "source_files", "inputs", "tasks", "arguments", "repetitions", "artifacts", "test_gate"}
    require(fields <= set(data) <= fields | {"provision_dependency_caches", "preparations", "toolchain_binding", "resource_profile", "capture_toolchain_inputs"}, "Invalid Source preparation fields")
    require(type(data.get("provision_dependency_caches", False)) is bool, "Dependency cache provisioning must be an explicit boolean")
    source_resources(shared, root, data)
    controlled = "toolchain_binding" in data
    capture = data.get("capture_toolchain_inputs", False)
    require(type(capture) is bool and not (capture and controlled), "Toolchain input acquisition cannot consume or rebind existing frozen toolchain inputs")
    require(not (controlled or capture) or "preparations" in data, "Bind complete preparations and toolchain inputs together")
    if not (controlled or capture) and "preparations" in data:
        require(data["repetitions"] == 1,
                "Partition only a single untimed Source qualification without controlled toolchain inputs")
    if capture:
        require(data["repetitions"] == 1 and not data.get("provision_dependency_caches", False)
                and data["arguments"]["baseline"] in ([], ["-Pstrata.jvmOnly=true"]), "Acquire toolchain inputs once outside complete preparation and timing")
    if controlled:
        require(set(data["toolchain_binding"]) == {"profile", "installations"}, "Pin the selected profile and complete observed discovery inventory")
        for item in data["toolchain_binding"].values():
            shared.pin(root, item)
    require(set(data["sources"]) == {"fixture", "baseline", "candidate"}, "Supply all three Source bindings")
    for source in data["sources"].values():
        require(set(source) == {"ref", "tree", "patches"} and all(re.fullmatch(r"[0-9a-f]{40}", source[key]) for key in ("ref", "tree"))
                and isinstance(source["patches"], list), "Invalid Source binding")
        for patch in source["patches"]:
            shared.pin(root, patch)
    files = data["source_files"]
    require(isinstance(files, list) and len(files) == len(set(files)), "Duplicate differing Source file")
    for path in files:
        shared.relative_path(root, path)
        require(path.endswith((".kt", ".kts", ".java", ".tsv", ".api")) and "/build/" not in path, "Declare only differing source files")
    labels = set()
    require(isinstance(data["inputs"], list), "Invalid Source input inventory")
    for item in data["inputs"]:
        require({"label", "pin"} <= set(item) <= {"label", "pin", "layout"} and re.fullmatch(r"[A-Za-z0-9_.-]+", item["label"]) and item["label"] not in labels, "Invalid Source input label")
        labels.add(item["label"])
        shared.pin(root, item["pin"])
    shared.input_paths(data["inputs"])
    tasks = data["tasks"]
    require(isinstance(tasks, list) and tasks and len(tasks) == len(set(tasks)) and len(tasks) <= 32, "Declare the complete unique Source task list")
    endings = {"compileKotlinJs", "compileKotlinJvm", "compileTestKotlinJs", "checkKotlinAbi", "lintKotlin", "detekt", "jsBrowserDistribution", "jvmTest", "installWebBrowsers", "jsJar", "verifyBrowserPerformanceDriver", "captureRuntimeSurfaceInventory", "captureRemoteInventory", "jvmJar", "jar", "jmhClasses", "jmhCompileGeneratedClasses", "lintKotlinJmh", "detektJmh", "test", "verifyHistoricalWorkloads", "verifyComponentRenderingWork", "verifyRemoteWorkloads"}
    for task in tasks:
        require(isinstance(task, str) and re.fullmatch(r"(?::[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)+:[A-Za-z0-9]+", task)
                and (controlled or capture or task.rsplit(":", 1)[1] in endings), "Use existing Source preparation tasks")
    require(isinstance(data["arguments"], dict) and set(data["arguments"]) == {"baseline", "candidate"}, "Supply both Source argument lists")
    for arguments in data["arguments"].values():
        require(isinstance(arguments, list), "Source arguments must be an explicit list")
        selector = "-Pstrata.performance.benchmarks="
        if arguments and isinstance(arguments[-1], str) and arguments[-1].startswith(selector):
            requested = arguments[-1][len(selector):]
            require(0 < len(requested) <= 8192, "Source fixture selection must be bounded")
            names = requested.split(',')
            require(len(names) <= 32 and len(names) == len(set(names))
                    and all(len(name) <= 256 and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", name) for name in names),
                    "Select complete unique qualified JVM fixture classes")
            require(any(task.endswith((":verifyHistoricalWorkloads", ":verifyComponentRenderingWork", ":verifyRemoteWorkloads")) for task in tasks),
                    "Source fixture selection requires complete workload verification")
            arguments = arguments[:-1]
            require(arguments in ([], ["-Pstrata.jvmOnly=true"]), "Source fixture selection is restricted to JVM verification")
        require(arguments in ([], ["-Pstrata.webOnly=true"], ["-Pstrata.jvmOnly=true"]), "Source arguments cannot change ownership, task or sampling bounds")
    require(type(data["repetitions"]) is int and data["repetitions"] in (1, 3), "Source preparation keeps one or three complete paired invocations")
    require(isinstance(data["artifacts"], list) and data["artifacts"] and len(data["artifacts"]) <= 32, "Declare actual Source outputs")
    patterns = set()
    for item in data["artifacts"]:
        require(set(item) == {"glob", "minimum"} and isinstance(item["glob"], str) and re.fullmatch(r"[A-Za-z0-9_$+./*-]+", item["glob"])
                and "/build/" in "/" + item["glob"] and ".." not in item["glob"] and not item["glob"].startswith("/")
                and item["glob"] not in patterns and type(item["minimum"]) is int and 0 < item["minimum"] <= 16_384, "Invalid bounded Source artifact pattern")
        patterns.add(item["glob"])
    gate = data["test_gate"]
    if gate is not None:
        require(set(gate) == {"task", "required_tests"} and gate["task"] in tasks and gate["task"].endswith((":test", ":jvmTest")), "Bind the existing full JVM test task")
        tests = gate["required_tests"]
        require(isinstance(tests, list) and tests and len(tests) == len(set(tests))
                and all(re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_.$]*#[^#\r\n\x00]+", item) for item in tests), "Declare exact Source JVM parity tests")
    require(sum(task.endswith((":test", ":jvmTest")) for task in tasks) == int(gate is not None), "Every Source JVM test invocation requires its full gate")
    if "preparations" in data:
        preparations = data["preparations"]
        require(isinstance(preparations, list) and 0 < len(preparations) <= 16, "Declare complete ordered preparations")
        require(all(isinstance(item, dict) and set(item) == {"name", "tasks", "artifacts", "test_gate"}
                    and isinstance(item["name"], str) and re.fullmatch(r"[A-Za-z0-9_-]+", item["name"])
                    and isinstance(item["tasks"], list) and item["tasks"] and isinstance(item["artifacts"], list) and item["artifacts"]
                    for item in preparations), "Invalid complete preparation")
        require(len({item["name"] for item in preparations}) == len(preparations)
                and [task for item in preparations for task in item["tasks"]] == tasks
                and [artifact for item in preparations for artifact in item["artifacts"]] == data["artifacts"], "Preparations must preserve the complete original ordered work and output inventory")
        require([item["test_gate"] for item in preparations if item["test_gate"] is not None] == ([] if gate is None else [gate])
                and all(item["test_gate"] is None or item["test_gate"]["task"] in item["tasks"] for item in preparations), "Preserve the full test gate in its actual preparation")
        require(data["arguments"]["baseline"] == data["arguments"]["candidate"], "Ordered preparations must preserve matched native requests")
    return data


def source_resources(shared, root, data):
    """Read immutable Source limits that only raise admission or tighten preparation JVM caps."""
    if "resource_profile" not in data:
        return {"minimumFreePhysicalBytes": 4 * 1024**3, "daemonMetaspaceMiB": 1024}
    limits = shared.read_json(shared.pin(root, data["resource_profile"]))
    fields = {"minimumFreePhysicalBytes", "daemonMetaspaceMiB"}
    execution = {"javaExecHeapMiB", "javaExecMetaspaceMiB"}
    heap = {"daemonHeapMiB"}
    shared.require(isinstance(limits, dict) and set(limits) in (fields, fields | heap, fields | execution, fields | execution | heap)
                   and type(limits["minimumFreePhysicalBytes"]) is int and 4 * 1024**3 <= limits["minimumFreePhysicalBytes"] <= 16 * 1024**3
                   and type(limits["daemonMetaspaceMiB"]) is int and 0 < limits["daemonMetaspaceMiB"] <= 1024,
                   "Source resources must retain finite admission and daemon bounds")
    if "daemonHeapMiB" in limits:
        shared.require(type(limits["daemonHeapMiB"]) is int and 64 <= limits["daemonHeapMiB"] <= 768,
                       "Source daemon heap must remain within its existing cap")
    if execution <= set(limits):
        shared.require(type(limits["javaExecHeapMiB"]) is int and 64 <= limits["javaExecHeapMiB"] <= 768
                       and type(limits["javaExecMetaspaceMiB"]) is int and 0 < limits["javaExecMetaspaceMiB"] <= 384,
                       "Source JavaExec caps must retain finite heap and metaspace bounds")
    return limits


def source_toolchain_tools(fixture):
    """Load only the frozen subject's existing pure installation/profile validator."""
    spec = importlib.util.spec_from_file_location("frozen_source_toolchains", fixture / "gradle/controlled_toolchains.py")
    tools = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tools)
    return tools


SOURCE_TOOLCHAIN_INPUT_INIT = """import groovy.json.JsonOutput
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.gradle.api.JavaVersion
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

gradle.projectsEvaluated {
    def root = gradle.rootProject
    if (root.projectDir.canonicalFile != new File(System.getenv('STRATA_PAIRED_WORKSPACE')).canonicalFile) return
    def majors = System.getenv('STRATA_PAIRED_CAPTURE_MAJORS').split(',').collect { it.toInteger() }
    def service = root.allprojects.sort { it.path }.collect { it.extensions.findByType(JavaToolchainService) }.find { it != null }
    if (service == null) throw new GradleException('No ordinary Java toolchain service in the declared model')
    def selected = majors.collect { major ->
        def launcher = service.launcherFor { languageVersion = JavaLanguageVersion.of(major) }.get()
        def metadata = launcher.metadata
        [home: metadata.installationPath.asFile.canonicalPath, major: metadata.languageVersion.asInt(),
         vendor: metadata.vendor, runtime_version: metadata.javaRuntimeVersion, jvm_version: metadata.jvmVersion]
    }
    def daemon = [home: new File(System.getProperty('java.home')).canonicalPath, major: JavaVersion.current().majorVersion.toInteger(),
                  vm_vendor: System.getProperty('java.vm.vendor'), runtime_version: System.getProperty('java.runtime.version'),
                  jvm_version: System.getProperty('java.vm.version')]
    def result = [schema: 1, gradle_version: gradle.gradleVersion, gradle_installation_home: gradle.gradleHomeDir.canonicalPath,
                  gradle_user_home: gradle.gradleUserHomeDir.canonicalPath, selected: selected, daemon: daemon,
                  jvm_only: root.providers.gradleProperty('strata.jvmOnly').getOrElse('false') == 'true']
    def output = new File(System.getenv('STRATA_PAIRED_CAPTURE_METADATA'))
    Files.writeString(output.toPath(), JsonOutput.prettyPrint(JsonOutput.toJson(result)), StandardOpenOption.CREATE_NEW)
}
"""


def require_default_toolchain_environment(shared, environment):
    """Refuse inherited Gradle Java project properties, including Windows-normalized names."""
    shared.require(not any(key.upper().startswith("ORG_GRADLE_PROJECT_ORG.GRADLE.JAVA.") for key in environment),
                   "Unreviewed environment installation properties")


def capture_source_toolchain_inputs(shared, tools, workspace, directory, environment, arguments):
    """Freeze observed hosted inputs after untimed acquisition; this certifies no preparation or timing."""
    require_default_toolchain_environment(shared, environment)
    metadata_path = directory / "actual-toolchain-metadata.json"
    metadata = shared.read_json(metadata_path)
    shared.require(metadata["schema"] == 1 and isinstance(metadata["selected"], list), "Missing actual Gradle toolchain metadata")
    model = tools.Model.JVM if metadata["jvm_only"] else tools.Model.COMPLETE
    required = tools.required_versions(workspace, model) | {metadata["daemon"]["major"]}
    shared.require({item["major"] for item in metadata["selected"]} == required
                   and len(metadata["selected"]) == len(required), "Acquired model toolchain assignments are incomplete")
    jdks = []
    for item in metadata["selected"]:
        actual = tools.installation(item["home"])
        shared.require(actual["major"] == item["major"] and all(isinstance(item.get(key), str) and item[key].strip()
                       for key in ("vendor", "runtime_version", "jvm_version")), "Missing actual selected toolchain identity")
        jdks.append({**actual, **{key: item[key] for key in ("vendor", "runtime_version", "jvm_version")}})
    user, distribution = Path(metadata["gradle_user_home"]), Path(metadata["gradle_installation_home"])
    inherited = {owner: tools.properties(path / "gradle.properties") for owner, path in (("user", user), ("project", workspace), ("installation", distribution))}
    shared.require(all(values.get("org.gradle.java.installations.auto-detect", "true") == "true" for values in inherited.values()), "Acquired default-discovery baseline was already bounded")
    inherited_environment = {}
    for values in inherited.values():
        for name in values.get("org.gradle.java.installations.fromEnv", "").split(","):
            if name.strip():
                inherited_environment[name.strip()] = tools.absolute_home(environment.get(name.strip())).as_posix()
    criteria = workspace / "gradle/gradle-daemon-jvm.properties"
    profile = {"schema": 1, "model": model.value, "gradle_version": metadata["gradle_version"], "jdks": jdks,
               "daemon_major": metadata["daemon"]["major"], "gradle_user_home": user.as_posix(),
               "gradle_installation_home": distribution.as_posix(), "inherited_properties": inherited,
               "inherited_environment": inherited_environment,
               "daemon_criteria": {"sha256": shared.digest(criteria), "properties": tools.properties(criteria)} if criteria.exists() else None}
    authorities = {os.path.normcase(str(Path(metadata["daemon"]["home"]))): dict(runtime_home=metadata["daemon"]["home"], **{key: metadata["daemon"][key] for key in ("runtime_version", "jvm_version", "vm_vendor")})}
    console = (directory / "console.log").read_text(encoding="utf-8-sig", errors="replace")
    for home, payload in re.findall(r"Received JVM installation metadata from '([^']+)': \{([^}\r\n]+)\}", console):
        pairs = [item.partition("=") for item in payload.split(", ")]
        shared.require(all(separator and key for key, separator, value in pairs) and len({key for key, _, _ in pairs}) == len(pairs), "Malformed acquired installation metadata")
        values = {key: value for key, _, value in pairs}
        shared.require(all(key in values for key in ("JAVA_HOME", "RUNTIME_VERSION", "VM_VERSION", "VM_VENDOR")), "Acquired runtime metadata is incomplete")
        authorities[os.path.normcase(str(Path(home)))] = {"runtime_home": values["JAVA_HOME"], "runtime_version": values["RUNTIME_VERSION"], "jvm_version": values["VM_VERSION"], "vm_vendor": values["VM_VENDOR"]}
    diagnostics = {shared.digest(path): path for path in (directory.parent.parent / "vms").glob("*.log")}
    inventory = {}
    for vm in shared.read_json(directory / "vms.json"):
        xml = ElementTree.parse(diagnostics[vm["diagnostic_sha256"]]).getroot()
        text = xml.findtext("vm_arguments/properties", "")
        properties = dict(line.partition("=")[::2] for line in text.splitlines() if "=" in line)
        home = tools.absolute_home(str(shared.java_installation_home(vm["os_generation"]["image"], vm["home"],
                                        re.findall(r"(?m)^java\.vm\.specification\.version=([^\r\n]+)", text))))
        key = os.path.normcase(str(home))
        shared.require(key in authorities, "Acquired physical VM lacks fresh same-installation runtime authority")
        actual = authorities[key]
        shared.require(os.path.normcase(str(Path(vm["home"]))) == os.path.normcase(str(Path(actual["runtime_home"])))
                       and properties.get("java.vm.vendor") == actual["vm_vendor"]
                       and properties.get("java.vm.version") == xml.findtext("vm_version/release", "").strip() == actual["jvm_version"], "Acquired actual VM metadata disagrees")
        release = home / "release"
        entry = {"home": str(home), "tree_sha256": tools.installation_tree_digest(home), "java_sha256": shared.digest(home / "bin/java.exe"),
                 "release_sha256": shared.digest(release) if release.is_file() else None, **actual}
        shared.require(key not in inventory or inventory[key] == entry, "Acquired installation changed between physical VM observations")
        inventory[key] = entry
    shared.require(0 < len(inventory) <= 32 and set(authorities) <= set(inventory)
                   and {os.path.normcase(str(Path(item["home"]))) for item in jdks} <= set(inventory), "Incomplete finite acquired installation inventory")
    shared.require(all(all(item[key] == inventory[os.path.normcase(str(Path(item["home"])))][key] for key in ("runtime_version", "jvm_version", "tree_sha256")) for item in jdks), "Selected Gradle metadata or bytes differ from the observed VM installation")
    destination = directory / "captured-inputs"
    destination.mkdir()
    profile_path = destination / "profile.json"
    shared.write_new(profile_path, profile)
    # Reuse the subject's complete byte, inheritance, model and daemon validation before exporting an activatable profile.
    tools.load_profile(profile_path, workspace, environment, arguments)
    installations = destination / "installations.json"
    shared.write_new(installations, {"schema": 1, "installations": list(inventory.values())})
    pins = {name: {"path": path.name, "sha256": shared.digest(path)} for name, path in (("profile", profile_path), ("installations", installations))}
    shared.write_new(destination / "pins.json", {"toolchain_binding": pins, "metadata_sha256": shared.digest(metadata_path),
                     "scope": "untimed observed input acquisition; installation bytes captured after closed acquisition and must be verified afresh before every formal phase"})
    return pins


def controlled_source_inputs(shared, input_root, fixture, specification):
    """Reuse the frozen subject's binder and finite complete installation byte pins; resolve no new home."""
    binder = fixture / "gradle/controlled_toolchains.py"
    tools = source_toolchain_tools(fixture)
    profile_path = shared.pin(input_root, specification["profile"])
    profile = shared.read_json(profile_path)
    inventory = shared.read_json(shared.pin(input_root, specification["installations"]))
    shared.require(set(inventory) == {"schema", "installations"} and inventory["schema"] == 1
                   and isinstance(inventory["installations"], list) and 0 < len(inventory["installations"]) <= 32, "Invalid finite observed installation inventory")
    homes = set()
    for item in inventory["installations"]:
        shared.require(set(item) - {"runtime_home"} == {"home", "tree_sha256", "java_sha256", "release_sha256", "vm_vendor", "jvm_version", "runtime_version"}
                       and all(isinstance(item[key], str) and re.fullmatch(r"[0-9a-f]{64}", item[key]) for key in ("tree_sha256", "java_sha256"))
                       and (item["release_sha256"] is None or isinstance(item["release_sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", item["release_sha256"]))
                       and all(isinstance(item[key], str) and item[key].strip() for key in ("vm_vendor", "jvm_version", "runtime_version")), "Pin complete observed bytes and independently observed VM metadata")
        home = os.path.normcase(str(tools.absolute_home(item["home"])))
        runtime_home = os.path.normcase(str(tools.absolute_home(item.get("runtime_home", item["home"]))))
        shared.require(runtime_home in (home, os.path.normcase(str(Path(item["home"]) / "jre"))), "Invalid pinned runtime home")
        shared.require(home not in homes, "Duplicate observed Java installation")
        homes.add(home)
    shared.require({os.path.normcase(str(Path(item["home"]))) for item in profile["jdks"]} <= homes, "Selected homes are absent from the observed discovery inventory")
    shared.require(all(properties.get("org.gradle.java.installations.auto-detect", "true") == "true"
                       for properties in profile["inherited_properties"].values()), "Default-discovery baseline was already bounded")
    inherited = dict(profile["inherited_properties"]["installation"], **profile["inherited_properties"]["project"])
    inherited.update(profile["inherited_properties"]["user"])
    shared.require(inherited.get("org.gradle.java.installations.auto-download") == "false", "Downloads must already be disabled on the baseline")
    shared.require(not any(key.startswith("ORG_GRADLE_PROJECT_org.gradle.java.") for key in os.environ), "Unreviewed environment installation properties")
    return {"tools": tools, "profile_path": profile_path, "profile": profile, "installations": inventory["installations"],
            "binder_sha256": shared.digest(binder), "init_sha256": shared.digest(fixture / "gradle/controlled-toolchains.init.gradle")}


def verify_source_installations(shared, binding):
    """Refuse changed selected or unused installation bytes before and after every owned phase."""
    allowed = {}
    for item in binding["installations"]:
        home = binding["tools"].absolute_home(item["home"])
        release = home / "release"
        shared.require(binding["tools"].installation_tree_digest(home) == item["tree_sha256"]
                       and shared.digest(home / "bin/java.exe") == item["java_sha256"]
                       and (shared.digest(release) if release.is_file() else None) == item["release_sha256"], "Observed Java installation bytes changed")
        allowed[os.path.normcase(str(home / "bin/java.exe"))] = item["java_sha256"]
    return allowed


def bind_source_request(shared, binding, workspace, side, arguments, environment, directory):
    """Keep default discovery on the control and bind only the candidate through the existing launcher adapter."""
    shared.require(side in ("baseline", "candidate"), "Unknown controlled Source side")
    shared.require(not any("org.gradle.java.installations." in argument for argument in arguments)
                   and not any(key.startswith("ORG_GRADLE_PROJECT_org.gradle.java.") for key in environment), "Shared launcher already overrides the controlled discovery contrast")
    effective = dict(os.environ, **environment)
    arguments, effective, manifest = binding["tools"].bind(binding["profile_path"], workspace, effective, arguments, directory, bounded=side == "candidate")
    environment = dict(environment, STRATA_TOOLCHAIN_BINDING=effective["STRATA_TOOLCHAIN_BINDING"])
    return arguments, environment, manifest, effective


def matched_source_wrappers(shared, workspaces):
    """Pin the same checked-in wrapper on both controlled sides before any bootstrap executes."""
    files = ("gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")
    pins = {}
    for name in files:
        paths = [shared.relative_path(workspaces[side], name) for side in ("baseline", "candidate")]
        shared.require(all(path.is_file() and not path.is_symlink() for path in paths), "Missing or linked controlled Source wrapper")
        values = [shared.digest(path) for path in paths]
        shared.require(values[0] == values[1], "Controlled Source wrappers differ")
        pins[name] = values[0]
    return pins


def verify_source_vm_metadata(shared, directory, binding, selections):
    """Join every closed physical VM's actual vendor/version with fresh same-home runtime metadata."""
    expected = {os.path.normcase(str(Path(item["home"]))): item for item in binding["installations"]}
    runtime = {}
    for build in selections["builds"]:
        daemon = build["daemon"]
        runtime[os.path.normcase(str(Path(daemon["home"])))] = {"runtime_home": daemon["home"], "runtime_version": daemon["runtime_version"], "jvm_version": daemon["jvm_version"], "authority": "verified_actual_daemon_receipt"}
    console = (directory / "console.log").read_text(encoding="utf-8-sig", errors="replace")
    for home, payload in re.findall(r"Received JVM installation metadata from '([^']+)': \{([^}\r\n]+)\}", console):
        pairs = [item.partition("=") for item in payload.split(", ")]
        shared.require(all(separator and key for key, separator, value in pairs) and len({key for key, _, _ in pairs}) == len(pairs), "Malformed actual installation metadata")
        values = {key: value for key, _, value in pairs}
        key = os.path.normcase(str(Path(home)))
        shared.require(key in expected and all(name in values for name in ("JAVA_HOME", "RUNTIME_VERSION", "VM_VERSION", "VM_VENDOR", "JAVA_VENDOR")), "Unknown or incomplete actual installation metadata")
        shared.require(values["VM_VENDOR"] == expected[key]["vm_vendor"], "Actual metadata vendor changed")
        runtime[key] = {"runtime_home": values["JAVA_HOME"], "runtime_version": values["RUNTIME_VERSION"], "jvm_version": values["VM_VERSION"], "java_vendor": values["JAVA_VENDOR"], "authority": "fresh_Gradle_metadata_diagnostic"}
    diagnostics = {shared.digest(path): path for path in (directory.parent.parent / "vms").glob("*.log")}
    records = []
    for vm in shared.read_json(directory / "vms.json"):
        xml = ElementTree.parse(diagnostics[vm["diagnostic_sha256"]]).getroot()
        text = xml.findtext("vm_arguments/properties", "")
        key = os.path.normcase(str(shared.java_installation_home(vm["os_generation"]["image"], vm["home"],
                                 re.findall(r"(?m)^java\.vm\.specification\.version=([^\r\n]+)", text))))
        shared.require(key in expected and key in runtime, "Actual physical VM runtime metadata is unavailable")
        item, actual = expected[key], runtime[key]
        properties = dict(line.partition("=")[::2] for line in text.splitlines() if "=" in line)
        shared.require(os.path.normcase(str(Path(vm["home"]))) == os.path.normcase(str(Path(actual["runtime_home"])))
                       == os.path.normcase(str(Path(item.get("runtime_home", item["home"]))))
                       and properties.get("java.vm.vendor") == item["vm_vendor"]
                       and properties.get("java.vm.version") == xml.findtext("vm_version/release", "").strip() == item["jvm_version"]
                       and actual["jvm_version"] == item["jvm_version"] and actual["runtime_version"] == item["runtime_version"], "Actual physical VM vendor/runtime/JVM identity changed")
        records.append({"pid": vm["pid"], "home": vm["home"], "installation_home": item["home"], "role": vm["role"], "vm_vendor": properties["java.vm.vendor"],
                        "jvm_version": properties["java.vm.version"], "runtime_metadata": actual, "diagnostic_sha256": vm["diagnostic_sha256"]})
    shared.write_new(directory / "vm-metadata-verified.json", records)


def preserve_source_outputs(shared, workspace, directory, patterns, *, partial=False):
    """Keep bounded actual outputs; partial failure evidence cannot supply compiled pins."""
    pins = {}
    missing = []
    for item in patterns:
        paths = sorted(path for path in workspace.glob(item["glob"]) if path.is_file())
        if partial:
            if len(paths) < item["minimum"]:
                missing.append({"glob": item["glob"], "minimum": item["minimum"], "observed": len(paths)})
        else:
            shared.require(item["minimum"] <= len(paths), "Incomplete actual Source artifact inventory: " + item["glob"])
        for path in paths:
            relative = path.relative_to(workspace).as_posix()
            shared.relative_path(workspace, relative)
            shared.require(not path.is_symlink() and path.stat().st_size <= 64 * 1024**2, "Unsafe Source artifact")
            pins[relative] = {"path": relative, "sha256": shared.digest(path)}
    shared.require(len(pins) <= 16_384 and sum((workspace / path).stat().st_size for path in pins) <= 512 * 1024**2, "Oversized Source artifact inventory")
    for path, pin in sorted(pins.items()):
        shared.require(shared.preserve_file(workspace / path, directory / "artifacts" / path)["sha256"] == pin["sha256"], "Source artifact changed while preserving")
        shared.pin(workspace, pin)
    result = list(pins.values())
    if partial:
        shared.write_new(directory / "partial-source-pins.json", {"status": "partial", "qualified": False, "missing": missing, "artifacts": result})
    else:
        shared.write_new(directory / "compiled-pins.json", result)
    return result


def verify_source_tasks(shared, directory, tasks):
    """Require each original requested task to execute successfully, without cached or skipped substitutes."""
    console = (directory / "console.log").read_text(encoding="utf-8-sig", errors="replace")
    observed = {}
    for task, status in re.findall(r"(?m)^> Task ((?::[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)*:[A-Za-z0-9_-]+)(?: ([A-Z-]+))?\s*$", console):
        if task in tasks:
            current = status or "executed"
            # Gradle repeats plain headings when worker output interrupts a task's report.
            shared.require(task not in observed or observed[task] == current, "Conflicting Source task status")
            observed[task] = current
    shared.require(set(observed) == set(tasks) and all(value == "executed" for value in observed.values()), "Requested Source task was missing, cached, skipped or failed")
    shared.write_new(directory / "source-tasks-verified.json", observed)
    return observed


def verify_source_classpath(shared, workspace, directory, retained):
    """Distinguish recorded empty task outputs from missing resolved dependencies without dropping either silently."""
    present = shared.prepared_classpath(retained / "classpath.json")
    original = shared.read_json(retained / "original-classpath.json")
    shared.require(set(original) == {"sources", "absent_outputs"} and isinstance(original["sources"], list)
                   and len(original["sources"]) == len(set(original["sources"])) <= 256 and isinstance(original["absent_outputs"], list), "Invalid original Test classpath")
    absent = {}
    console = (directory / "console.log").read_text(encoding="utf-8-sig", errors="replace")
    for item in original["absent_outputs"]:
        shared.require(set(item) == {"source", "task", "reason"} and item["reason"] == "NO-SOURCE" and item["source"] not in absent, "Invalid absent Test output")
        path = Path(item["source"])
        shared.require(path.is_absolute() and path.resolve().is_relative_to(workspace.resolve()) and "build" in path.relative_to(workspace).parts
                       and path.suffix != ".jar" and not path.exists() and not path.is_symlink(), "Missing Test dependency is not an empty owned output")
        shared.require(isinstance(item["task"], str) and re.fullmatch(r"(?::[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)*:[A-Za-z0-9_-]+", item["task"])
                       and re.search(r"(?m)^> Task " + re.escape(item["task"]) + r" NO-SOURCE\s*$", console), "Missing output lacks actual NO-SOURCE task evidence")
        absent[item["source"]] = item
    sources = [item["source"] for item in present["entries"]]
    shared.require(set(original["sources"]) == set(sources) | set(absent)
                   and [source for source in original["sources"] if source not in absent] == sources, "Original Test classpath entries were dropped or reordered")
    return original["sources"], {"present_entries": len(sources), "absent_outputs": list(absent.values())}


def verify_source_test_classpath(shared, workspace, directory):
    """Retain the existing full JVM test classpath verification receipt."""
    _, record = verify_source_classpath(shared, workspace, directory, directory / "test-classpath")
    shared.write_new(directory / "test-classpath-verified.json", record)


def expand_arguments(collection, roles):
    """Resolve structured file roles without a shell or a private workload registry."""
    return [value.format_map(roles) for value in collection["arguments"]]


def verify_source_classpaths(shared, workspace, directory):
    """Match every actual JavaExec origin to its unchanged complete preserved classpath, in order."""
    console = (directory / "console.log").read_text(encoding="utf-8-sig", errors="replace")
    origins = [json.loads(row) for row in re.findall(r"(?m)^paired\.jvm\.origin (.+)$", console)]
    captures = [json.loads(row) for row in re.findall(r"(?m)^paired\.jvm\.sourceClasspath (.+)$", console)]
    shared.require(len(origins) == len(captures) <= 32, "Source JavaExec classpath capture is missing or oversized")
    root = directory / "source-classpaths"
    inventories, verified = set(), []
    for origin, capture in zip(origins, captures):
        shared.require(set(origin) == {"task", "main", "executable", "classpath"} and set(capture) == {"task", "inventory"}
                       and origin["task"] == capture["task"], "Source classpath task identity changed")
        path = shared.relative_path(root, capture["inventory"])
        shared.require(path.name == "classpath.json" and path not in inventories, "Duplicate or wrong Source classpath inventory")
        inventories.add(path)
        original, record = verify_source_classpath(shared, workspace, directory, path.parent)
        sources = [os.path.normcase(str(Path(source).resolve())) for source in original]
        actual = [os.path.normcase(str(Path(value).resolve())) for value in origin["classpath"].split(os.pathsep)]
        shared.require(sources == actual, "Original Source JavaExec classpath entries were dropped or reordered")
        verified.append({"task": capture["task"], "inventory": capture["inventory"], "sha256": shared.digest(path), **record})
    shared.require(set(root.glob("**/classpath.json")) == inventories, "Source classpath inventory lacks an actual JavaExec origin")
    shared.write_new(directory / "source-classpaths-verified.json", {"tasks": verified})
    return verified


def validate_terminal(shared, exit_code, terminal, admission, minimum_free_physical_bytes=4 * 1024**3):
    """A successful command requires natural descendant release and a fresh in-Job admission."""
    shared.require(exit_code == 0 and terminal["exitCode"] == 0 and terminal["naturalRelease"] is True
                   and terminal["handlesClosed"] is True and terminal["activeProcessesAfterCleanup"] == 0
                   and "error" not in terminal, "Owned browser phase failed")
    shared.require(admission["inOwnedJob"] is True and admission["jobName"] == terminal["jobName"]
                   and minimum_free_physical_bytes <= admission["availablePhysicalBytes"], "Missing actual fresh owned admission")


def verify_adapters(shared, workspace, data):
    """Bind every declared adapter to the immutable subject source before preparation or collection."""
    for collection in data["collections"]:
        shared.pin(workspace, collection["adapter"])


def common_sources(shared, workspaces, data):
    """Both runtime variants retain the exact common fixture, adapter and build source closure."""
    inventories = []
    for side in ("fixture", "baseline", "candidate"):
        files = shared.source_files(workspaces[side], data["sources"][side]["tree"])
        inventories.append({path: value for path, value in files.items() if path not in data.get("runtime_files", data.get("source_files", []))})
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
    source_mode = False
    try:
        shared.require(shared.git(root, "rev-parse", "HEAD") == selection["execution_head"], "Browser driver head changed")
        data = shared.load_manifest(selection["root"], selection["path"])
        source_mode = data["schema"] == "strata-source-preparation-ci-v1"
        processor = output / "comparison-processor.mjs"
        if source_mode is False:
            record["comparison_processor"] = shared.preserve_file(shared.relative_path(root, PROCESSOR), processor)
        capture = source_mode and data.get("capture_toolchain_inputs", False)
        if capture:
            require_default_toolchain_environment(shared, os.environ)
        resources = source_resources(shared, selection["root"], data) if source_mode else {"minimumFreePhysicalBytes": 4 * 1024**3, "daemonMetaspaceMiB": 1024}
        if source_mode:
            record["schema"] = "strata-source-preparation-attempt-v1"
            record["source_resources"] = resources
            if "resource_profile" in data:
                shared.preserve_file(shared.pin(selection["root"], data["resource_profile"]), output / "source-resource-profile.json")
        compiled_pins = data.get("compiled_pins", {side: [] for side in ("baseline", "candidate")})
        record.update(driver_head=selection["execution_head"], input_producer=selection["producer"], manifest_sha256=shared.digest(selection["path"]))
        if "bundle" in selection:
            record["dispatch_inputs"] = shared.preserve_file(selection["bundle"], output / "dispatch-inputs.zip")
            shared.require(record["dispatch_inputs"]["sha256"] == selection["producer"], "Dispatch input bytes changed")
            record["dispatch_artifacts"] = selection.get("artifacts", [])
        driver = shared.verify_driver_source(root, record["driver_head"], data, selection.get("explicit_manual", False))
        if driver is not None:
            shared.verify_input_producer(root, record["driver_head"], selection["root"], selection["path"], selection["producer"], selection["sha256"], selection.get("bundle"))
            record["driver_source"] = driver
            record["scope"] = {"consumer_ci_establishes_subject_acceptance": False, "prior_rows_adopted": False}
        record["driver_sources"] = shared.preserve_driver_sources(root, output, ("paired-browser.py",))
        shutil.copyfile(selection["path"], output / "manifest.json")
        homes = {"toolchain": Path(os.environ["STRATA_PAIRED_TOOLCHAIN"]), "launcher": Path(os.environ["JAVA_HOME"])}
        versions = tomllib.loads((root / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
        allowed_java = {}
        java_pins = ([{"role": role, "executable_sha256": shared.digest(home / "bin/java.exe"), "release_sha256": shared.digest(home / "release")} for role, home in homes.items()]
                     if source_mode else data["java_pins"])
        for item in java_pins:
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
        shared.write_new(output / "java-pins.json", java_pins)
        hardware = subprocess.check_output(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "@{processors=@(Get-CimInstance Win32_Processor | Select-Object Name,Manufacturer,NumberOfCores,NumberOfLogicalProcessors); os=(Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,BuildNumber,TotalVisibleMemorySize); power=(powercfg /getactivescheme)} | ConvertTo-Json -Depth 4"], text=True, encoding="utf-8-sig")
        shared.write_new(output / "host.json", json.loads(hardware))
        for side in ("baseline", "candidate"):
            workspaces[side], record["sources"][side] = shared.materialize(root, output, side, data["sources"][side], selection["root"])
        if data["sources"]["fixture"] == data["sources"]["candidate"]:
            workspaces["fixture"], record["sources"]["fixture"] = workspaces["candidate"], record["sources"]["candidate"]
        else:
            workspaces["fixture"], record["sources"]["fixture"] = shared.materialize(root, output, "fixture", data["sources"]["fixture"], selection["root"])
        common_sources(shared, workspaces, data)
        if not source_mode:
            for workspace in workspaces.values():
                verify_adapters(shared, workspace, data)
        toolchains = None
        capture_tools = None
        if capture:
            capture_tools = source_toolchain_tools(workspaces["fixture"])
        if source_mode and "toolchain_binding" in data:
            toolchains = controlled_source_inputs(shared, selection["root"], workspaces["fixture"], data["toolchain_binding"])
            allowed_java = verify_source_installations(shared, toolchains)
            shared.write_new(output / "controlled-source-inputs.json", {"profile": data["toolchain_binding"]["profile"],
                             "installations": toolchains["installations"], "binder_sha256": toolchains["binder_sha256"], "init_sha256": toolchains["init_sha256"]})
        if selection["producer"] and source_mode is False and driver is None:
            shared.require(shared.source_files(workspaces["candidate"], data["sources"]["candidate"]["tree"]) == shared.source_files(root, record["driver_head"]), "Browser candidate differs from execution head")
        preserved_inputs = shared.preserve_inputs(selection["root"], output / "inputs", data["inputs"])
        inputs = {label: item["path"] for label, item in preserved_inputs.items()}
        shared.write_new(output / "fixture-inputs.json", inputs)
        vms = output / "vms"
        vms.mkdir()
        class_staging = output / "class-loads"
        class_staging.mkdir()
        captured_class_loads = {}
        prepared = set()

        def phase(name, workspace, arguments, node_program=None, test_gate=None, source_metric_scope=None, acquisition_environment=None, wrapper_bootstrap=False):
            """Reuse the existing owning helper and fixed limits for Gradle or a pinned Node adapter."""
            nonlocal captured_class_loads, allowed_java
            shared.require(type(wrapper_bootstrap) is bool and (not wrapper_bootstrap or source_mode and toolchains is not None
                           and not capture and node_program is None and test_gate is None and acquisition_environment is None
                           and arguments == ["--version", *data["arguments"]["baseline"]]), "Invalid controlled wrapper bootstrap")
            shared.require(time.monotonic() - started + 1830 + 180 < 6 * 60 * 60, "Insufficient whole-attempt time and upload reserve")
            shared.verify_driver_sources(record["driver_sources"])
            shared.verify_driver_source(root, record["driver_head"], data, selection.get("explicit_manual", False))
            if source_mode:
                shared.require(source_resources(shared, selection["root"], data) == resources, "Source resource profile changed")
            for side in workspaces:
                shared.source_guard(workspaces[side], data["sources"][side]["tree"])
            for side in prepared:
                for item in compiled_pins[side]:
                    shared.pin(workspaces[side], item)
            for item in java_pins:
                home = homes[item["role"]]
                shared.require(shared.digest(home / "bin/java.exe") == item["executable_sha256"] and shared.digest(home / "release") == item["release_sha256"], "Java bytes changed")
            if toolchains is not None:
                for item in data["toolchain_binding"].values():
                    shared.pin(selection["root"], item)
                shared.require(verify_source_installations(shared, toolchains) == allowed_java, "Observed installation inventory changed")
                shared.require(shared.digest(workspace / "gradle/controlled_toolchains.py") == toolchains["binder_sha256"]
                               and shared.digest(workspace / "gradle/controlled-toolchains.init.gradle") == toolchains["init_sha256"], "Frozen binding source changed")
            shared.require(shared.digest(node) == program["sha256"], "Node bytes changed")
            directory = output / "phases" / name
            directory.mkdir(parents=True, exist_ok=False)
            (directory / "class-loads").mkdir()
            flags = f"-XX:+UnlockDiagnosticVMOptions -XX:+PrintCommandLineFlags -XX:-DisplayVMOutput -XX:+LogVMOutput -XX:LogFile={vms.as_posix()}/vm-%p-%t.log"
            environment = {"JAVA_OPTS": "-Xmx64m -XX:MaxMetaspaceSize=256m", "GRADLE_OPTS": "", "JAVA_TOOL_OPTIONS": f"-Xmx32m -XX:MaxMetaspaceSize=256m {flags}", "JDK_JAVA_OPTIONS": "", "_JAVA_OPTIONS": "", "STRATA_PAIRED_WORKSPACE": workspace.as_posix(), "STRATA_PAIRED_LOGS": vms.as_posix(), "STRATA_PAIRED_CLASS_LOGS": class_staging.as_posix()}
            environment["STRATA_PAIRED_DAEMON_METASPACE_MIB"] = str(resources["daemonMetaspaceMiB"])
            environment.update(acquisition_environment or {})
            if source_mode:
                environment.update({"JAVA_HOME": str(homes["launcher"]), "ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy": "in-process"})
                if toolchains is None and not capture:
                    environment.update({"ORG_GRADLE_PROJECT_org.gradle.java.installations.auto-detect": "false",
                                        "ORG_GRADLE_PROJECT_org.gradle.java.installations.auto-download": "false", "ORG_GRADLE_PROJECT_org.gradle.java.installations.fromEnv": "",
                                        "ORG_GRADLE_PROJECT_org.gradle.java.installations.paths": ",".join(str(home) for home in homes.values())})
            binding = None
            if node_program is None:
                daemon_heap = resources.get("daemonHeapMiB", 768)
                metaspace = "1g" if resources["daemonMetaspaceMiB"] == 1024 else str(resources["daemonMetaspaceMiB"]) + "m"
                arguments = arguments + ["--no-daemon", "--no-parallel", "--max-workers=1", "--no-build-cache", "--no-configuration-cache", "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process", "-Pdetekt.use.worker.api=false", "-I", str(root / "gradle/performance/bounded-jvm.init.gradle.kts"), f"-Dorg.gradle.jvmargs=-Xmx{daemon_heap}m -XX:MaxMetaspaceSize={metaspace} -Dfile.encoding=UTF-8 {flags}"]
                if toolchains is None and not capture:
                    arguments += ["-Dorg.gradle.java.installations.auto-detect=false", "-Dorg.gradle.java.installations.auto-download=false", "-Dorg.gradle.java.installations.fromEnv=", "-Dorg.gradle.java.installations.paths=" + ",".join(str(home) for home in homes.values())]
                if source_mode:
                    arguments += ["--rerun-tasks"]
                    arguments += ["-Pstrata.paired.sourceClasspaths=" + str(directory / "source-classpaths")]
                    if "javaExecHeapMiB" in resources:
                        arguments += ["-Pstrata.paired.sourceJavaExecHeapMiB=" + str(resources["javaExecHeapMiB"]),
                                      "-Pstrata.paired.sourceJavaExecMetaspaceMiB=" + str(resources["javaExecMetaspaceMiB"])]
                    if test_gate is not None:
                        arguments += ["-Pstrata.paired.testClasspathTask=" + test_gate["task"], "-Pstrata.paired.preserveTestClasspath=" + str(directory / "test-classpath")]
                if toolchains is not None and not wrapper_bootstrap:
                    side = next(side for side in ("baseline", "candidate") if workspaces[side] == workspace)
                    arguments, environment, binding, binding_environment = bind_source_request(shared, toolchains, workspace, side, arguments, environment, directory / "toolchains")
            request = {"workspace": str(workspace), "arguments": arguments, "environment": environment, "minimumFreePhysicalBytes": resources["minimumFreePhysicalBytes"], "jobCommittedBytes": 3 * 1024**3, "workSeconds": 1800, "cleanupSeconds": 30}
            if node_program is not None:
                request["program"] = node_program
            shared.write_new(directory / "request.json", request)
            before = set(vms.glob("*.log"))
            previous_classes = shared.class_load_inventory(class_staging, captured_class_loads)
            if capture:
                shared.require(shared.digest(init) == capture_init_sha256, "Acquisition metadata init bytes changed")
            phase_started = time.monotonic()
            invocation_started_ns = time.monotonic_ns()
            try:
                exit_code = shared.invoke(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(root / "gradle/performance/invoke-windows.ps1"), "-Request", str(directory / "request.json"), "-Report", str(directory / "terminal.json")], root, directory / "console.log")
            finally:
                invocation_closed_ns = time.monotonic_ns()
                owned_wall_seconds = time.monotonic() - phase_started
                try:
                    captured_class_loads = shared.preserve_class_loads(class_staging, directory, previous_classes)
                finally:
                    if test_gate is not None:
                        shared.preserve_test_reports(workspace, directory, test_gate)
            terminal = shared.read_json(directory / "terminal.json")
            record["phases"].append({"name": name, "elapsed_seconds": time.monotonic() - phase_started, "exit_code": exit_code, "terminal": terminal,
                                     **({"source_metric_scope": source_metric_scope, "invocation_started_ns": invocation_started_ns,
                                         "invocation_closed_ns": invocation_closed_ns} if source_metric_scope is not None else {})})
            if exit_code != 0 and node_program is None:
                record["phases"][-1]["failed_jvm_diagnostics"] = shared.preserve_failed_jvm_diagnostics(directory, workspace, terminal, environment)
            validate_terminal(shared, exit_code, terminal, shared.read_json(directory / "child-admission.json"), resources["minimumFreePhysicalBytes"])
            if len(record["phases"]) != 1:
                first = record["phases"][0]["terminal"]
                shared.require(terminal["inheritedAffinityMask"] == first["inheritedAffinityMask"] and terminal["systemAffinityMask"] == first["systemAffinityMask"], "Host affinity changed")
            if node_program is None:
                if capture:
                    # Acquisition observes actual owned images before freezing inputs; it grants no formal byte/timing credit.
                    images = {member["image"] for histories in terminal["members"].values() for member in histories if Path(member["image"]).name.lower() in ("java.exe", "javaw.exe")}
                    shared.require(0 < len(images) <= 32, "Missing or oversized acquired Java image inventory")
                    observed_java = {os.path.normcase(image): shared.digest(Path(image)) for image in images}
                    shared.write_new(directory / "observed-java-images.json", observed_java)
                    shared.require(all(observed_java[path] == expected for path, expected in allowed_java.items() if path in observed_java),
                                   "Known pinned Java bytes changed during input acquisition")
                    allowed_java = observed_java
                shared.verify_vms(directory, sorted(set(vms.glob("*.log")) - before), terminal, allowed_java,
                                  maximum_diagnostic_bytes=16 * 1024**2, require_all_java_generations=True,
                                  daemon_metaspace_mib=resources["daemonMetaspaceMiB"],
                                  daemon_heap_mib=resources.get("daemonHeapMiB", 768),
                                  java_exec_heap_mib=resources.get("javaExecHeapMiB", 768),
                                  java_exec_metaspace_mib=resources.get("javaExecMetaspaceMiB", 384))
                if binding is not None:
                    selections = toolchains["tools"].verify(binding, workspace, binding_environment, directory / "console.log")
                    verify_source_vm_metadata(shared, directory, toolchains, selections)
            else:
                validate_node_generation(shared, terminal, node_program)
                shared.require(shared.read_json(directory / "child-admission.json") == terminal["primaryAdmission"], "Node admission record differs from its suspended primary")
            if test_gate is not None:
                shared.verify_test_gate(directory, test_gate)
                shared.require(shared.read_json(directory / "junit-verified.json")["counts"]["skipped"] == 0, "JVM parity tests were skipped")
                if source_mode:
                    verify_source_test_classpath(shared, workspace, directory)
            if source_mode:
                verify_source_classpaths(shared, workspace, directory)
                counters = {key: terminal.get(key) for key in ("jobUserCpu100ns", "jobKernelCpu100ns", "peakJobCommittedBytes")}
                shared.require(all(type(value) is int and 0 <= value for value in counters.values()), "Actual Source Job counters are unavailable")
                record["phases"][-1].update(owned_wall_seconds=owned_wall_seconds, **counters, allocation_bytes=None, rss_bytes=None)
            shared.verify_driver_sources(record["driver_sources"])
            shared.verify_driver_source(root, record["driver_head"], data, selection.get("explicit_manual", False))
            if source_mode:
                shared.require(source_resources(shared, selection["root"], data) == resources, "Source resource profile changed during execution")
            if toolchains is not None:
                for item in data["toolchain_binding"].values():
                    shared.pin(selection["root"], item)
                shared.require(verify_source_installations(shared, toolchains) == allowed_java, "Observed installation inventory changed during execution")
            for side in workspaces:
                shared.source_guard(workspaces[side], data["sources"][side]["tree"])
            for side in prepared:
                for item in compiled_pins[side]:
                    shared.pin(workspaces[side], item)
            if capture:
                shared.require(shared.digest(init) == capture_init_sha256, "Acquisition metadata init bytes changed during execution")
            return (directory, request) if capture else directory

        if toolchains is not None:
            wrappers = matched_source_wrappers(shared, workspaces)
            directory = phase("wrapper-bootstrap", workspaces["baseline"], ["--version", *data["arguments"]["baseline"]],
                              source_metric_scope="untimed_wrapper_bootstrap", wrapper_bootstrap=True)
            shared.require(matched_source_wrappers(shared, workspaces) == wrappers, "Controlled Source wrapper changed during bootstrap")
            record["wrapper_bootstrap"] = {"phase": directory.name, "wrappers": wrappers, "source_timing_credit": False,
                                           "profile_validation": "Required by the original strict binder before the first preparation"}

        if capture:
            init = output / "capture-toolchain-inputs.init.gradle"
            init.write_text(SOURCE_TOOLCHAIN_INPUT_INIT, encoding="utf-8")
            capture_init_sha256 = shared.digest(init)
            record["toolchain_input_init"] = {"path": init.name, "sha256": capture_init_sha256}
            model = capture_tools.Model.JVM if data["arguments"]["baseline"] else capture_tools.Model.COMPLETE
            majors = sorted(capture_tools.required_versions(workspaces["baseline"], model) | {int(versions["performance-launcher"].split(".")[0])})
            directory = output / "phases/toolchain-input-acquisition"
            # The existing phase owns the only Gradle help invocation, caps, all physical generations and diagnostics.
            capture_environment = {"STRATA_PAIRED_CAPTURE_MAJORS": ",".join(map(str, majors)), "STRATA_PAIRED_CAPTURE_METADATA": str(directory / "actual-toolchain-metadata.json")}
            acquired, request = phase("toolchain-input-acquisition", workspaces["baseline"], ["help", *data["arguments"]["baseline"], "--info", "-I", str(init)],
                                      source_metric_scope="untimed_toolchain_input_acquisition", acquisition_environment=capture_environment)
            pins = capture_source_toolchain_inputs(shared, capture_tools, workspaces["baseline"], acquired, dict(os.environ, **request["environment"]), request["arguments"])
            record.update(status="toolchain-inputs-prepared", toolchain_input_pins=pins, original_source_preparations="UNRUN", runtime_collection="UNRUN", complete_measured_attempts=0)
            return

        if source_mode:
            latest = {}
            invocations = [(f"source-{side}-{repetition}", side, "measured_source_preparation") for repetition in range(data["repetitions"]) for side in ("baseline", "candidate")]
            provisioning = [("provision-" + side, side, "untimed_dependency_provisioning") for side in ("baseline", "candidate")] if data.get("provision_dependency_caches", False) else []
            for name, side, scope in provisioning + invocations:
                preparations = data.get("preparations", [{"name": "", "tasks": data["tasks"], "artifacts": data["artifacts"], "test_gate": data["test_gate"]}])
                latest[side] = []
                for preparation in preparations:
                    phase_name = name + ("-" + preparation["name"] if preparation["name"] else "")
                    directory = phase(phase_name, workspaces[side], preparation["tasks"] + data["arguments"][side], test_gate=preparation["test_gate"], source_metric_scope=scope)
                    verify_source_tasks(shared, directory, preparation["tasks"])
                    latest[side] += preserve_source_outputs(shared, workspaces[side], directory, preparation["artifacts"])
                for item in latest[side]:
                    shared.pin(workspaces[side], item)
            shared.write_new(output / "prepared-pins.json", {"schema": "strata-prepared-source-pins-v1", "driver_head": selection["execution_head"], "sources": data["sources"], "java_pins": java_pins, "compiled_pins": latest})
            record.update(status="prepared", completed_source_invocations=data["repetitions"] * 2 * len(preparations), completed_dependency_provisioning_invocations=len(provisioning) * len(preparations), runtime_collection="UNRUN")
            if "preparations" in data:
                record["tasks_per_attempt"] = len(data["tasks"])
                record["tasks_per_preparation"] = {item["name"]: len(item["tasks"]) for item in preparations}
                record["preparations_per_attempt"] = len(data["preparations"])
                if toolchains is not None:
                    record["complete_measured_attempts"] = data["repetitions"] * 2
                    record["complete_dependency_provisioning_attempts"] = len(provisioning)
            else:
                record["tasks_per_invocation"] = len(data["tasks"])
            return

        collectors = {}
        for side in ("baseline", "candidate"):
            workspace = workspaces[side]
            gate = {"task": ":integration:web:jvmTest", "required_tests": data["correctness"]["required_tests"]}
            phase("prepare-" + side, workspace, [":integration:web:jsBrowserDistribution", ":integration:web:jvmTest", ":integration:web:installWebBrowsers", ":performance-testkit:jsJar", ":performance-testkit:verifyBrowserPerformanceDriver", ":api:jsJar", ":runtime:core:jsJar", ":runtime:web:jsJar"], test_gate=gate)
            stage_compiled_inputs(shared, workspace, output / "phases" / ("prepare-" + side), data["compiled_pins"][side],
                                  data.get("compiled_inputs", {}).get(side, {}), inputs)
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
            phase("compare-" + collection["name"], candidate, [str(processor), str(request_path)], program)
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
        if source_mode and execution_failed:
            preparations = data.get("preparations", [{"artifacts": data["artifacts"]}])
            patterns = [item for preparation in preparations for item in preparation["artifacts"]]
            for side in ("baseline", "candidate"):
                if side not in workspaces:
                    continue
                workspace = workspaces[side]
                try:
                    preserve_source_outputs(shared, workspace, output / "failed-source" / side, patterns, partial=True)
                except Exception as failure:
                    preservation_errors.append({"source": str(workspace), "scope": "partial-source", "error": f"{type(failure).__name__}: {failure}"})
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
