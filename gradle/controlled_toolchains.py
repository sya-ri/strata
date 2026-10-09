"""Bind exact JDK inputs to an existing Gradle launcher without executing a process."""
from enum import Enum
import hashlib
import json
import os
from pathlib import Path
import re
import tomllib


class Model(Enum):
    """The ordinary complete model or the existing JVM preparation dependency closure."""
    COMPLETE = "complete"
    JVM = "jvmOnly"


INSTALLATIONS = "org.gradle.java.installations."
JAVA_HOME_PROPERTY = "org.gradle.java.home"
MODEL_PROPERTIES = {"strata.jvmOnly", "strata.webOnly", "strata.completeIdeaModel", "strata.minecraftVersions"}


def digest(path):
    """Hash current regular-file bytes with bounded memory."""
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def absolute_home(value):
    """Refuse ambiguous Gradle comma-list values and missing installation directories."""
    if not isinstance(value, str) or not value or any(char in value for char in ",\n\r\0"):
        raise ValueError("Invalid JDK home")
    path = Path(value)
    if not path.is_absolute() or not path.is_dir():
        raise ValueError("JDK home must be an existing absolute directory")
    return path.resolve()


def installation(home):
    """Snapshot a complete modern JDK, including its library bytes; never launch Java."""
    home = absolute_home(str(home))
    suffix = ".exe" if (home / "bin/java.exe").is_file() else ""
    for name in ("release", f"bin/java{suffix}", f"bin/javac{suffix}", "lib/modules"):
        if not (home / name).is_file() or (home / name).stat().st_size == 0:
            raise ValueError("JDK is missing release, Java, compiler or modules")
    release = {}
    for line in (home / "release").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r'([A-Z0-9_]+)="([^"\r\n]*)"', line)
        if not match or match[1] in release:
            raise ValueError("Malformed or duplicate JDK release entry")
        release[match[1]] = match[2]
    version = release.get("JAVA_VERSION", "")
    match = re.fullmatch(r"([1-9][0-9]*)(?:[.\-+][A-Za-z0-9.+\-]+)?", version)
    if not match or not release.get("IMPLEMENTOR"):
        raise ValueError("JDK release must declare a modern version and implementor")
    entries = []
    total = 0
    for path in sorted(home.rglob("*")):
        if path.is_symlink() and (path.is_dir() or not path.resolve().is_relative_to(home)):
            raise ValueError("JDK links must target regular files within the bound installation")
        if path.is_file():
            total += path.stat().st_size
            entries.append((path.relative_to(home).as_posix(), digest(path)))
            if 50000 < len(entries) or 4 * 1024 ** 3 < total:
                raise ValueError("JDK identity exceeds the finite inventory bound")
    tree = hashlib.sha256(json.dumps(entries, separators=(",", ":")).encode()).hexdigest()
    return {"home": home.as_posix(), "major": int(match[1]), "release_version": version,
            "implementor": release["IMPLEMENTOR"], "tree_sha256": tree,
            "java": (home / f"bin/java{suffix}").as_posix(),
            "javac": (home / f"bin/javac{suffix}").as_posix()}


def properties(path):
    """Decode Java properties, reading only discovery and daemon keys into the result."""
    path = Path(path)
    if not path.is_file():
        return {}
    logical = []
    pending = ""
    for line in path.read_text(encoding="iso-8859-1").splitlines():
        text = pending + (line.lstrip() if pending else line)
        if len(text) - len(text.rstrip("\\")) & 1:
            pending = text[:-1]
        else:
            logical.append(text)
            pending = ""
    if pending:
        raise ValueError("Unterminated properties continuation")

    def unescape(text):
        def replace(match):
            token = match[1]
            if token.startswith("u"):
                return chr(int(token[1:], 16))
            return {"t": "\t", "r": "\r", "n": "\n", "f": "\f"}.get(token, token)
        return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", replace, text)

    result = {}
    for line in logical:
        line = line.lstrip()
        if not line or line.startswith(("#", "!")):
            continue
        match = re.fullmatch(r"((?:\\.|[^\s:=])+)\s*(?:[:=]\s*)?(.*)", line)
        if not match:
            raise ValueError("Malformed properties entry")
        key = unescape(match[1])
        if key.startswith(INSTALLATIONS) or key == JAVA_HOME_PROPERTY or key.startswith("toolchain") or key in MODEL_PROPERTIES:
            if key in result:
                raise ValueError("Duplicate controlled properties entry")
            result[key] = unescape(match[2])
    return result


def required_versions(root, model):
    """Read the existing catalog instead of duplicating its full Java requirement inventory."""
    versions = tomllib.loads((Path(root) / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
    if model is Model.COMPLETE:
        return {int(value) for name, value in versions.items() if name.startswith("java-")}
    return {int(versions[name]) for name in ("java-baseline", "java-minecraft")}


def project_properties(arguments):
    """Decode native Gradle project-property argument forms without interpreting task selectors."""
    result = {}
    values = iter(arguments)
    for value in values:
        if value in ("-P", "--project-prop"):
            raw = next(values, "")
        elif value.startswith("--project-prop="):
            raw = value.removeprefix("--project-prop=")
        elif value.startswith("-P"):
            raw = value[2:]
        else:
            continue
        key, separator, supplied = raw.partition("=")
        if key in MODEL_PROPERTIES:
            if not separator or key in result:
                raise ValueError("Malformed or duplicate model property")
            result[key] = supplied
    return result


def load_profile(path, root, environment, arguments):
    """Validate frozen inputs and inherited discovery before handing arguments to the caller."""
    profile = json.loads(Path(path).read_text(encoding="utf-8"))
    if profile.get("schema") != 1:
        raise ValueError("Unsupported toolchain binding schema")
    if not isinstance(profile.get("gradle_version"), str) or not profile["gradle_version"]:
        raise ValueError("Actual Gradle version is required")
    model = Model(profile["model"])
    request = project_properties(arguments)
    scoped = request.get("strata.jvmOnly", "false") == "true"
    if scoped != (model is Model.JVM):
        raise ValueError("Toolchain binding model differs from the actual Gradle request")
    if any("strata.webOnly" in value or "strata.minecraftVersions" in value for value in arguments):
        raise ValueError("Use a complete or JVM model for controlled preparation")
    jdks = profile["jdks"]
    majors = {entry["major"] for entry in jdks}
    if len(majors) != len(jdks) or majors != required_versions(root, model) | {profile["daemon_major"]}:
        raise ValueError("Missing, duplicate or extra toolchain bindings for the selected model")
    homes = set()
    for entry in jdks:
        observed = installation(entry["home"])
        if any(entry.get(key) != value for key, value in observed.items()):
            raise ValueError("JDK installation identity changed or differs from its binding")
        for key in ("vendor", "runtime_version", "jvm_version"):
            if not isinstance(entry.get(key), str) or not entry[key].strip():
                raise ValueError("Actual Gradle vendor/runtime/JVM metadata is required")
        homes.add(observed["home"])
    if len(homes) != len(jdks):
        raise ValueError("Different Java versions cannot share one installation")
    daemon = next(entry for entry in jdks if entry["major"] == profile["daemon_major"])
    if absolute_home(environment.get("JAVA_HOME")).as_posix() != daemon["home"]:
        raise ValueError("JAVA_HOME differs from the frozen wrapper/daemon JDK")
    for value in arguments:
        if INSTALLATIONS in value or JAVA_HOME_PROPERTY in value or value in ("-g", "--gradle-user-home") or value.startswith("--gradle-user-home="):
            raise ValueError("Discovery and Gradle user home must be supplied through the binding")
    if any("org.gradle.java." in environment.get(key, "") for key in ("GRADLE_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")):
        raise ValueError("Inherited JVM options may not override Java installation bindings")
    gradle_home = Path(profile["gradle_user_home"])
    if not gradle_home.is_absolute() or Path(environment.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))).resolve() != gradle_home.resolve():
        raise ValueError("Gradle user home differs from the binding")
    inherited = {}
    from_environment = {}
    distribution = absolute_home(profile["gradle_installation_home"])
    for owner, file in (("user", gradle_home / "gradle.properties"), ("project", Path(root) / "gradle.properties"),
                        ("installation", distribution / "gradle.properties")):
        values = properties(file)
        inherited[owner] = values
        for raw in values.get(INSTALLATIONS + "paths", "").split(","):
            if raw.strip() and absolute_home(raw.strip()).as_posix() not in homes:
                raise ValueError("Inherited installation paths expand the bound JDK set")
        for name in values.get(INSTALLATIONS + "fromEnv", "").split(","):
            if name.strip():
                home = absolute_home(environment.get(name.strip())).as_posix()
                if home not in homes:
                    raise ValueError("Inherited fromEnv expands the bound JDK set")
                from_environment[name.strip()] = home
        if JAVA_HOME_PROPERTY in values and absolute_home(values[JAVA_HOME_PROPERTY]).as_posix() != daemon["home"]:
            raise ValueError("Inherited daemon home differs from the binding")
    if inherited != profile["inherited_properties"]:
        raise ValueError("Inherited discovery properties changed")
    effective = dict(inherited["installation"], **inherited["project"])
    effective.update(inherited["user"])
    effective.update({key: environment["ORG_GRADLE_PROJECT_" + key] for key in MODEL_PROPERTIES if "ORG_GRADLE_PROJECT_" + key in environment})
    effective.update(request)
    if effective.get("strata.jvmOnly", "false") not in ("true", "false") or (effective.get("strata.jvmOnly", "false") == "true") != (model is Model.JVM):
        raise ValueError("Inherited effective Gradle model differs from the binding")
    if from_environment != profile["inherited_environment"]:
        raise ValueError("Inherited fromEnv values changed")
    criteria = Path(root) / "gradle/gradle-daemon-jvm.properties"
    actual_criteria = {"sha256": digest(criteria), "properties": properties(criteria)} if criteria.exists() else None
    if actual_criteria != profile["daemon_criteria"]:
        raise ValueError("Daemon criteria changed or were not explicitly bound")
    if actual_criteria and actual_criteria["properties"].get("toolchainVersion") != str(daemon["major"]):
        raise ValueError("Daemon criteria version differs from the bound daemon")
    return profile


def bind(profile_path, root, environment, arguments, evidence, *, bounded=True):
    """Return standard Gradle arguments and a fresh verification manifest; execute nothing."""
    profile = load_profile(profile_path, root, environment, arguments)
    evidence = Path(evidence).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    receipts = evidence / "selected-toolchains"
    manifest = evidence / "toolchain-binding.json"
    if receipts.exists() or manifest.exists():
        raise ValueError("Controlled invocation evidence must be fresh")
    controlled = list(arguments)
    expected = {JAVA_HOME_PROPERTY: next(entry["home"] for entry in profile["jdks"] if entry["major"] == profile["daemon_major"])}
    if bounded:
        expected.update({INSTALLATIONS + "auto-detect": "false", INSTALLATIONS + "auto-download": "false",
                         INSTALLATIONS + "fromEnv": "", INSTALLATIONS + "paths": ",".join(entry["home"] for entry in profile["jdks"])})
    controlled += [f"-D{key}={value}" for key, value in expected.items()]
    controlled += ["--no-configuration-cache", "--console=plain", "--info", "-I", str(Path(root) / "gradle/controlled-toolchains.init.gradle")]
    frozen = {"root": str(Path(root).resolve()), "jvm_only": Model(profile["model"]) is Model.JVM,
              "profile": profile, "profile_sha256": digest(profile_path), "profile_path": str(Path(profile_path).resolve()),
              "properties": expected, "receipts": str(receipts), "arguments": controlled}
    with manifest.open("x", encoding="utf-8") as stream:
        json.dump(frozen, stream, indent=2)
    env = dict(environment, STRATA_TOOLCHAIN_BINDING=str(manifest))
    return controlled, env, manifest


def verify(manifest, root, environment, log):
    """Require exact Gradle selection and unchanged JDK bytes even after a zero exit."""
    binding = json.loads(Path(manifest).read_text(encoding="utf-8"))
    profile = load_profile(binding["profile_path"], root, environment, [arg for arg in binding["arguments"] if not arg.startswith("-Dorg.gradle.java.")])
    if profile != binding["profile"] or digest(binding["profile_path"]) != binding["profile_sha256"]:
        raise ValueError("Toolchain profile changed during the invocation")
    directory = Path(binding["receipts"])
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError("Gradle did not produce fresh build-qualified toolchain receipts")
    files = list(directory.iterdir())
    if not files or 128 < len(files) or any(not file.is_file() or file.is_symlink() or file.suffix != ".json" or 16 * 1024 ** 2 < file.stat().st_size for file in files):
        raise ValueError("Missing, unsafe or oversized build-qualified receipts")
    builds = [json.loads(file.read_text(encoding="utf-8")) for file in files]
    if any(not Path(build["project_dir"]).is_absolute() for build in builds):
        raise ValueError("Build receipt must identify an absolute project directory")
    captured_directories = {Path(build["project_dir"]).resolve() for build in builds}
    declared_directories = {Path(child["project_dir"]).resolve() for build in builds for child in build["included_builds"]}
    if not declared_directories <= captured_directories:
        raise ValueError("Missing declared included-build toolchain receipt")
    root_builds = [build for build in builds if Path(build["project_dir"]).resolve() == Path(binding["root"]).resolve()]
    if len(root_builds) != 1:
        raise ValueError("Exactly one actual root-build receipt is required")
    expected_daemon = next(entry for entry in profile["jdks"] if entry["major"] == profile["daemon_major"])
    build_ids = set()
    tasks = {}
    displays = {}
    identities = set()
    for build in builds:
        build_id = build["build_path"]
        if not isinstance(build_id, str) or not build_id.startswith(":") or "::" in build_id or build_id[1:].endswith(":") or build_id in build_ids:
            raise ValueError("Missing or duplicate Gradle build identity")
        build_ids.add(build_id)
        if build.get("status") != "passed" or build.get("profile_sha256") != binding["profile_sha256"]:
            raise ValueError("Gradle did not certify all build toolchain selections")
        if build.get("gradle_version") != profile["gradle_version"]:
            raise ValueError("Actual Gradle version differs from the binding")
        daemon = build.get("daemon", {})
        if any(daemon.get(key) != expected_daemon[key] for key in ("home", "major", "runtime_version", "jvm_version")) or daemon.get("vendor") != expected_daemon["implementor"]:
            raise ValueError("Actual daemon receipt differs from the binding")
        for task in build["tasks"]:
            if not isinstance(task["path"], str) or not task["path"].startswith(":") or not task["path"][1:] or "::" in task["path"] or task["path"].endswith(":"):
                raise ValueError("Malformed build-qualified task path")
            key = (build_id, task["path"])
            display = build_id.rstrip(":") + task["path"]
            if key in tasks or display in displays:
                raise ValueError("Duplicate or ambiguous build-qualified task identity")
            tasks[key] = task
            displays[display] = key
        for tool in build["tools"]:
            key = (build_id, tool["task"], tool["role"])
            if key in identities or (build_id, tool["task"]) not in tasks:
                raise ValueError("Duplicate selected tool or tool outside its build task graph")
            identities.add(key)
            expected = next((entry for entry in profile["jdks"] if entry["major"] == tool["major"]), None)
            if expected is None or any(tool.get(name) != expected[name] for name in ("home", "vendor", "runtime_version", "jvm_version")):
                raise ValueError("Selected Gradle tool identity differs from its binding")
            executables = {"launcher": expected["java"], "compiler": expected["javac"],
                           "javadoc": str(Path(expected["home"]) / ("bin/javadoc.exe" if expected["java"].endswith(".exe") else "bin/javadoc"))}
            if tool["role"] not in executables or Path(tool["executable"]).resolve() != Path(executables[tool["role"]]).resolve():
                raise ValueError("Selected executable differs from its binding")
    affiliated = {root_builds[0]["build_path"]}
    while True:
        directories = {Path(child["project_dir"]).resolve() for build in builds if build["build_path"] in affiliated for child in build["included_builds"]}
        additions = {build["build_path"] for build in builds if build["parent_build_path"] in affiliated or Path(build["project_dir"]).resolve() in directories} - affiliated
        if not additions:
            break
        affiliated.update(additions)
    if affiliated != build_ids or not identities or not tasks:
        raise ValueError("Unrelated or incomplete build-qualified receipt set")
    homes = {entry["home"] for entry in profile["jdks"]}
    current = None
    kotlin = set()
    for line in Path(log).read_text(encoding="utf-8", errors="replace").splitlines():
        header = re.match(r"> Task (:[^\s]+)", line)
        if header:
            current = displays.get(header[1])
        match = re.search(r"\[KOTLIN\] Kotlin compilation 'jdkHome' argument: (.+)", line)
        if match:
            home = absolute_home(match[1].strip()).as_posix()
            if home not in homes or current is None:
                raise ValueError("Kotlin compiler selected an unbound JDK or task")
            major = tasks[current].get("kotlin_major")
            if next(entry["major"] for entry in profile["jdks"] if entry["home"] == home) != major:
                raise ValueError("Kotlin compiler home differs from its task toolchain version")
            kotlin.add(current)
    if any(task.get("kotlin_major") is not None and task.get("did_work") and path not in kotlin for path, task in tasks.items()):
        raise ValueError("Actual Kotlin compiler home is unavailable for executed work")
    return {"status": "passed", "builds": builds}
