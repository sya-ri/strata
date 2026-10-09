"""Freeze generated CPU inventories and validate complete paired executor attempts.

This opt-in outer driver delegates every application measurement and comparison to
the existing JMH collector. It has no worker pool or remote execution scheduler.
"""
import argparse
from collections import defaultdict
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid
import zipfile

from cpu_host import exclusive_lease, observe, run_process
from cpu_phase import Phase
from cpu_mode import Mode
from cpu_scheduling import Scheduling
from cpu_variant import Variant
from cpu_outcome import Outcome
from cpu_artifact_kind import ArtifactKind
from cpu_iteration_kind import IterationKind


def require(condition, reason):
    """Reject an invalid boundary without creating a success result."""
    if not condition:
        raise ValueError(reason)


def unique_object(pairs):
    """Reject duplicate JSON keys instead of silently adopting the last value."""
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key: " + key)
        result[key] = value
    return result


def document(path):
    """Read bounded strict JSON without accepting NaN or duplicate identities."""
    path = Path(path)
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 64 * 1024 * 1024,
            "Missing, linked or oversized document: " + str(path))
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_object,
                      parse_constant=lambda value: require(False, "Non-finite JSON: " + value))


def encoded(value):
    """Canonical bytes for identities; preserved source hashes use original bytes."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def digest(path):
    """Hash an actual regular file without trusting a supplied filename."""
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), "Missing or linked source: " + str(path))
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def identity(value):
    """Hash a detached structured identity."""
    return hashlib.sha256(encoded(value)).hexdigest()


def measure_phase(spec_path, output):
    """Preserve actual command, process-tree cost, transferred/prepared bytes and failures once."""
    spec = document(spec_path)
    phase = Phase(spec["phase"])
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    write_new(output / "spec.json", spec)
    started = time.monotonic_ns()
    result = {"contract": "strata-cpu-phase-v1", "phase": phase.value, "phase_id": str(uuid.uuid4()),
              "spec_sha256": digest(output / "spec.json"), "started_ns": started, "binding": spec["binding"]}
    try:
        with (output / "command.log").open("xb") as log:
            cost = run_process(spec["command"], Path(spec["workspace"]), log)
        result["cost"] = cost
        require(cost["exit_code"] == 0, "Outer preparation/transfer command failed")
        result["artifacts"] = {str(Path(path).resolve()): {"sha256": digest(path), "bytes": Path(path).stat().st_size} for path in spec["artifacts"]}
        require(result["artifacts"], "No actual phase artifact sources")
        result["status"] = Outcome.PASSED.value
    except Exception as failure:
        result.update(status=Outcome.FAILED.value, failure=type(failure).__name__ + ": " + str(failure))
    result["elapsed_seconds"] = (time.monotonic_ns() - started) / 1e9
    result["log_sha256"] = digest(output / "command.log")
    write_new(output / "phase.json", result)
    return result


def write_new(path, value):
    """Publish once and never overwrite an earlier run, failure, permit or plan."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as output:
        output.write(encoded(value) + b"\n")


def direct(root, name):
    """Resolve a direct bundle filename and reject traversal on either platform."""
    require(isinstance(name, str) and name and name not in (".", "..")
            and not any(c in name for c in "/\\:") and not any(ord(c) < 32 for c in name),
            "Unsafe bundle name")
    return Path(root) / name


def identifier(value):
    """Keep user-visible plan IDs safe for bundle paths and exact lookup."""
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", value), "Invalid plan identifier")
    return value


def rows(values):
    """Decode the collector's actual generated row identities without another registry."""
    result = []
    for value in values:
        row = json.loads(value, object_pairs_hook=unique_object) if isinstance(value, str) else value
        require(isinstance(row, list) and len(row) == 3 and isinstance(row[0], str)
                and isinstance(row[2], dict) and all(isinstance(v, str) for v in row[2].values()),
                "Malformed generated row")
        Mode(row[1])
        result.append(encoded(row).decode())
    require(result and len(result) <= 16384 and len(result) == len(set(result)), "Empty, duplicate or oversized inventory")
    return sorted(result)


def tree_hash(archive):
    """Recheck preserved directory bytes using the collector's canonical entry framing."""
    result = hashlib.sha256()
    total = 0
    with zipfile.ZipFile(archive) as source:
        names = [entry.filename for entry in source.infolist() if not entry.is_dir()]
        require(names and len(names) == len(set(names)) and len(names) <= 16384, "Duplicate or oversized source tree")
        for name in sorted(names):
            require(not name.startswith(("/", "\\")) and ".." not in Path(name).parts and "\\" not in name, "Unsafe source tree")
            entry = source.getinfo(name)
            total += entry.file_size
            require(total <= 64 * 1024 * 1024, "Oversized source tree")
            name_bytes = name.encode()
            result.update(str(len(name_bytes)).encode() + b":" + name_bytes + b"\0")
            result.update(source.read(name))
            result.update(b"\xff")
    return result.hexdigest()


def verify_artifacts(root, entries):
    """Revalidate every preserved input, complete fixture source and runtime archive."""
    for entry in entries.values():
        archive = direct(root, entry["archive"])
        require(digest(archive) == entry["archive_sha256"], "Changed preserved source")
        actual = tree_hash(archive) if ArtifactKind(entry["kind"]) == ArtifactKind.TREE else digest(archive)
        require(actual == entry["sha256"], "Changed source tree or archive")


def admission(path):
    """Load only an untimed complete corpus produced by the actual collector."""
    path = Path(path)
    value = document(path)
    require(value["contract"] == "strata-jmh-cpu-admission-v1" and Outcome(value["status"]) == Outcome.PASSED,
            "Missing complete compiled admission")
    value["registered_workloads"] = rows(value["registered_workloads"])
    for group in ("sources", "inputs", "targets"):
        verify_artifacts(path.parent, value[group])
    require(value["sources"]["collector"]["sha256"] == value["collector_identity"]["code_source_sha256"], "Admission collector differs")
    return value


def common(value):
    """Path-independent common byte identities that must match across variants and executors."""
    return {group: {key: (entry["kind"], entry["sha256"]) for key, entry in value[group].items()}
            for group in ("sources", "inputs")}


def runtime(value):
    """Keep actual module, representative and complete loaded tree identities, excluding locations."""
    result = {}
    for module in value["identity"]["runtime"]["modules"]:
        name = module["module"]
        require(name not in result and module["status"] == "resolved", "Duplicate or unresolved runtime")
        result[name] = {"representative": module["representativeClass"], "archive": module["codeSource"]["sha256"],
                        "resource": module["classResource"]["sha256"], "classTree": module["classTree"]}
        require(value["targets"][name]["sha256"] == result[name]["archive"], "Runtime admission archive differs")
    require(result and set(result) == set(value["targets"]), "Incomplete runtime admission")
    return result


def select(full, selector):
    """Apply existing generated-method and Cartesian parameter selectors to complete metadata."""
    matrix = [json.loads(row) for row in full]
    available = {row[0] for row in matrix}
    methods = selector.get("methods", sorted(available))
    require(methods and len(methods) == len(set(methods)) and set(methods) <= available, "Unknown or duplicate methods")
    parameters = selector.get("parameters", {})
    known = defaultdict(set)
    for method, _, values in matrix:
        if method in methods:
            for key, value in values.items():
                known[key].add(value)
    for key, values in parameters.items():
        require(values and len(values) == len(set(values)) and key in known and set(values) <= known[key], "Unknown or duplicate parameters")
        require(all(key in row[2] for row in matrix if row[0] in methods), "Parameter selector does not apply to every method")
    selected = [encoded(row).decode() for row in matrix if row[0] in methods
                and all(row[2][key] in values for key, values in parameters.items())]
    return rows(selected)


def freeze(config_path, destination):
    """Freeze all admissions, selectors, sources, executor bindings and retry/adoption policy."""
    config = document(config_path)
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    plan = {"contract": "strata-jmh-cpu-plan-v1", "plan_id": str(uuid.uuid4()), "corpora": {}, "shards": [], "executors": {},
            "interleaving": config["interleaving"], "max_attempts": config["max_attempts"],
            "schedule_order": [Scheduling.SERIAL.value, Scheduling.PARALLEL.value], "adoption": "first-complete-success"}
    probe = Path(__file__).with_name("cpu_host.py")
    shutil.copyfile(probe, destination / "cpu_host.py")
    plan["executor_probe_sha256"] = digest(destination / "cpu_host.py")
    plan["driver_sources"] = {}
    for source in sorted(Path(__file__).parent.glob("cpu_*.py")):
        plan["driver_sources"][source.name] = digest(source)
        if source.name != probe.name:
            shutil.copyfile(source, destination / source.name)
    require(type(plan["max_attempts"]) is int and 1 <= plan["max_attempts"] <= 16, "Invalid frozen retry limit")
    expected_slots = {(variant.value, repetition) for variant in Variant for repetition in range(3)}
    actual_slots = [tuple(slot) for slot in plan["interleaving"]]
    require(len(actual_slots) == 6 and set(actual_slots) == expected_slots and all(type(slot[1]) is int for slot in actual_slots), "Incomplete paired interleaving")
    for entry in config["executors"]:
        name = identifier(entry["id"])
        require(name not in plan["executors"], "Duplicate executor ID")
        profile = document(entry["profile"])
        require(profile["contract"] == "strata-cpu-host-v1" and profile["conditions"]["physical_id"]
                and profile["java"] and profile["conditions"]["java_sha256"], "Missing observed executor/JDK identity")
        qualification = Path(entry["qualification"])
        require(qualification.stat().st_size and qualification.stat().st_size <= 1024 * 1024, "Missing reviewed host qualification")
        target = destination / "executors" / name
        target.mkdir(parents=True, exist_ok=False)
        shutil.copyfile(qualification, target / "qualification.txt")
        write_new(target / "profile.json", profile)
        plan["executors"][name] = {"conditions": profile["conditions"], "profile_sha256": digest(target / "profile.json"),
                                   "qualification_sha256": digest(target / "qualification.txt"), "java": profile["java"]}
    physical = [entry["conditions"]["physical_id"] for entry in plan["executors"].values()]
    require(len(physical) == len(set(physical)) and 2 <= len(physical), "Executors must have independent observed physical identities")
    for corpus in config["corpora"]:
        name = identifier(corpus["id"])
        require(name not in plan["corpora"] and set(corpus["admissions"]) == {mode.value for mode in Mode}, "Missing or duplicate corpus/modes")
        suite = {"modes": {}, "commands": corpus["commands"], "source_archives": {}}
        require(set(suite["commands"]) == {side.value for side in Variant}, "Incomplete runtime commands")
        for command in suite["commands"].values():
            require(isinstance(command, list) and command and all(isinstance(token, str) and token for token in command), "Invalid frozen collection command")
            require(all("{" + name + "}" in " ".join(command) for name in ("parameters", "context", "output", "mode", "repetition", "methods")), "Command does not apply every frozen collector selection/context")
        for variant in Variant:
            source = Path(corpus["source_archives"][variant.value])
            target = destination / "sources" / name / (variant.value + ".zip")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            suite["source_archives"][variant.value] = digest(target)
        for mode in Mode:
            sides = {}
            for variant in Variant:
                source = Path(corpus["admissions"][mode.value][variant.value])
                value = admission(source)
                require(value["inputs"]["cpu-executor-probe"]["sha256"] == plan["executor_probe_sha256"], "Admission uses another actual executor observer")
                require(all(json.loads(row)[1] == mode.value for row in value["registered_workloads"]), "Wrong admission mode")
                target = destination / "admissions" / name / mode.value / variant.value
                shutil.copytree(source.parent, target)
                require(admission(target / source.name) == value, "Admission changed during freeze")
                sides[variant.value] = {"path": str((target / source.name).relative_to(destination)).replace("\\", "/"),
                                       "sha256": digest(target / source.name), "admission": value}
            before, after = (sides[variant.value]["admission"] for variant in Variant)
            require(common(before) == common(after) and before["identity"]["fixtures"] == after["identity"]["fixtures"]
                    and before["arguments"] == after["arguments"] and before["registered_workloads"] == after["registered_workloads"],
                    "Pair changed common bytes, settings or inventory")
            left, right = runtime(before), runtime(after)
            require(set(left) == set(right) and all(left[key]["representative"] == right[key]["representative"] for key in left), "Pair changed runtime representatives")
            suite["modes"][mode.value] = {"rows": before["registered_workloads"], "sides": sides}
        modes = list(suite["modes"].values())
        require(common(modes[0]["sides"][Variant.BASELINE.value]["admission"]) == common(modes[1]["sides"][Variant.BASELINE.value]["admission"]), "Modes changed common fixture/input bytes")
        require({encoded([json.loads(row)[0], json.loads(row)[2]]) for row in modes[0]["rows"]}
                == {encoded([json.loads(row)[0], json.loads(row)[2]]) for row in modes[1]["rows"]}, "Modes have different compiled inventories")
        for variant in Variant:
            require(runtime(modes[0]["sides"][variant.value]["admission"]) == runtime(modes[1]["sides"][variant.value]["admission"]), "Modes changed runtime bytes")
        plan["corpora"][name] = suite
    require(plan["corpora"], "Empty CPU plan")
    for shard in config["shards"]:
        identifier(shard["id"])
        require(shard["id"] not in {s["id"] for s in plan["shards"]} and shard["executor"] in plan["executors"]
                and set(shard["selectors"]) == set(plan["corpora"]), "Missing, duplicate or foreign shard binding")
        selections = {}
        for name, selector in shard["selectors"].items():
            first = next(iter(plan["corpora"][name]["modes"].values()))
            selector = {**selector, "methods": selector.get("methods", sorted({json.loads(row)[0] for row in first["rows"]}))}
            selections[name] = {"selector": selector, "selector_sha256": identity(selector),
                                "modes": {mode: select(value["rows"], selector) for mode, value in plan["corpora"][name]["modes"].items()}}
        plan["shards"].append({"id": shard["id"], "executor": shard["executor"], "selections": selections})
    require(len(plan["shards"]) == len(plan["executors"]) and {s["executor"] for s in plan["shards"]} == set(plan["executors"]), "Every shard requires one fixed independent executor")
    for name, suite in plan["corpora"].items():
        for mode, value in suite["modes"].items():
            union = [row for shard in plan["shards"] for row in shard["selections"][name]["modes"][mode]]
            require(len(union) == len(set(union)) and set(union) == set(value["rows"]), "Shards are not a disjoint exact compiled inventory union")
    comparator = config["comparator"]
    plan["comparator"] = {"java": comparator["java"], "classpath": []}
    for index, source in enumerate(comparator["classpath"]):
        target = destination / "processor" / (str(index) + ".jar")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        plan["comparator"]["classpath"].append({"path": str(target.relative_to(destination)).replace("\\", "/"), "sha256": digest(target)})
    collectors = {value["sides"][variant.value]["admission"]["sources"]["collector"]["sha256"]
                  for suite in plan["corpora"].values() for value in suite["modes"].values() for variant in Variant}
    require(len(collectors) == 1 and plan["comparator"]["classpath"][0]["sha256"] in collectors, "Processor must use the actual common collector")
    plan["preparation_costs"] = []
    prepared = set()
    for index, path in enumerate(config["preparation_receipts"]):
        phase = verify_phase(path)
        require(Phase(phase["phase"]) == Phase.PREPARATION, "Wrong preparation cost phase")
        prepared.update(entry["sha256"] for entry in phase["artifacts"].values())
        target = destination / "preparation" / str(index)
        shutil.copytree(Path(path).parent, target)
        plan["preparation_costs"].append({"path": str((target / "phase.json").relative_to(destination)).replace("\\", "/"), "sha256": digest(target / "phase.json")})
    required_admissions = {side["sha256"] for suite in plan["corpora"].values() for value in suite["modes"].values() for side in value["sides"].values()}
    require(required_admissions <= prepared, "Missing actual preparation costs for complete admitted pair/modes/suites")
    plan["inventory_id"] = identity({name: {mode: value["rows"] for mode, value in suite["modes"].items()} for name, suite in plan["corpora"].items()})
    write_new(destination / "plan.json", plan)
    return plan


def load_plan(root):
    """Recheck all frozen sources rather than accepting a hash-only assembled receipt."""
    root = Path(root)
    plan = document(root / "plan.json")
    require(plan["contract"] == "strata-jmh-cpu-plan-v1" and plan["adoption"] == "first-complete-success", "Unsupported CPU plan")
    require(digest(root / "cpu_host.py") == plan["executor_probe_sha256"], "Changed frozen executor observer")
    for name, expected in plan["driver_sources"].items():
        require(digest(direct(root, name)) == expected and digest(direct(Path(__file__).parent, name)) == expected, "Changed frozen or executing CPU driver source")
    for name, suite in plan["corpora"].items():
        for variant in Variant:
            require(digest(root / "sources" / name / (variant.value + ".zip")) == suite["source_archives"][variant.value], "Changed source archive")
        for value in suite["modes"].values():
            for side in value["sides"].values():
                path = root / side["path"]
                require(digest(path) == side["sha256"] and admission(path) == side["admission"], "Changed frozen admission")
    for name, executor in plan["executors"].items():
        require(digest(root / "executors" / name / "profile.json") == executor["profile_sha256"]
                and digest(root / "executors" / name / "qualification.txt") == executor["qualification_sha256"], "Changed executor qualification")
    for source in plan["comparator"]["classpath"]:
        require(digest(root / source["path"]) == source["sha256"], "Changed comparator artifact")
    for phase in plan["preparation_costs"]:
        require(digest(root / phase["path"]) == phase["sha256"], "Changed preparation measurement source")
        verify_phase(root / phase["path"])
    for shard in plan["shards"]:
        for selection in shard["selections"].values():
            require(identity(selection["selector"]) == selection["selector_sha256"], "Changed frozen selector")
    return plan


def begin(plan_root, collection, scheduling):
    """Create a new complete attempt only after the prior attempt has a retained terminal failure."""
    plan = load_plan(plan_root)
    scheduling = Scheduling(scheduling)
    collection = Path(collection)
    collection.mkdir(parents=True, exist_ok=True)
    manifest = collection / "collection.json"
    conditions = observe()["conditions"]
    clock_owner = {key: conditions[key] for key in ("physical_id", "boot_id")}
    if not manifest.exists():
        write_new(manifest, {"plan_sha256": digest(Path(plan_root) / "plan.json"), "plan_id": plan["plan_id"],
                             "coordinator": clock_owner, "started_ns": time.monotonic_ns()})
    owner = document(manifest)
    require(owner["plan_sha256"] == digest(Path(plan_root) / "plan.json"), "Collection changed plan")
    require(owner["coordinator"] == clock_owner, "Coordinator migrated or rebooted")
    prior = sorted(collection.glob(scheduling.value + "-*"))
    require(len(prior) < plan["max_attempts"], "Frozen retry limit exhausted")
    for attempt in prior:
        outcome = document(attempt / "outcome.json")
        require(Outcome(outcome["status"]) == Outcome.FAILED, "Cannot retry an incomplete or successful attempt")
    if scheduling == Scheduling.PARALLEL:
        require(any(Outcome(document(p / "outcome.json")["status"]) == Outcome.PASSED for p in collection.glob("serial-*")), "Complete serial scheduling must precede parallel scheduling")
    target = collection / (scheduling.value + "-" + str(len(prior)))
    target.mkdir(exist_ok=False)
    attempt = {"plan_id": plan["plan_id"], "plan_sha256": owner["plan_sha256"], "inventory_id": plan["inventory_id"],
               "whole_attempt": str(uuid.uuid4()), "scheduling": scheduling.value, "started_ns": time.monotonic_ns(),
               "coordinator": owner["coordinator"], "previous_failures": {p.name: digest(p / "outcome.json") for p in prior},
               "shard_attempts": {shard["id"]: str(uuid.uuid4()) for shard in plan["shards"]}}
    write_new(target / "attempt.json", attempt)
    return target


def coordinator(attempt_root):
    """Require coordinator timings to come from the same observed host as attempt creation."""
    value = document(Path(attempt_root) / "attempt.json")
    conditions = observe()["conditions"]
    require({key: conditions[key] for key in ("physical_id", "boot_id")} == value["coordinator"], "Coordinator migrated or rebooted")
    return value


def grant(plan_root, attempt_root, shard_id):
    """Issue an explicit permit; serial permits wait for the preceding complete acknowledgement."""
    plan = load_plan(plan_root)
    attempt = coordinator(attempt_root)
    require(attempt["plan_sha256"] == digest(Path(plan_root) / "plan.json"), "Attempt changed plan")
    require(not (Path(attempt_root) / "outcome.json").exists(), "Attempt is already terminal")
    ids = [s["id"] for s in plan["shards"]]
    require(shard_id in ids, "Foreign shard")
    if Scheduling(attempt["scheduling"]) == Scheduling.SERIAL:
        require(all((Path(attempt_root) / "acks" / (name + ".json")).is_file() for name in ids[:ids.index(shard_id)]), "Serial shard overlaps an unacknowledged predecessor")
    permit = {"context": {key: attempt[key] for key in ("plan_id", "plan_sha256", "inventory_id", "whole_attempt", "scheduling")},
              "shard_id": shard_id, "shard_attempt": attempt["shard_attempts"][shard_id], "granted_ns": time.monotonic_ns()}
    write_new(Path(attempt_root) / "permits" / (shard_id + ".json"), permit)
    return permit


def parameters_bytes(selector):
    """Produce frozen UTF-8 JDK Properties selectors through the existing selection entry point."""
    def escaped(text):
        return text.replace("\\", "\\\\").replace("=", "\\=").replace(":", "\\:").replace(" ", "\\ ")
    return "".join(escaped(key) + "=" + ",".join(escaped(value) for value in sorted(values)) + "\n"
                   for key, values in sorted(selector.get("parameters", {}).items())).encode()


def execution_settings(arguments):
    """Remove only method includes and parameter selectors, retaining every original JMH setting."""
    index = next((i for i, argument in enumerate(arguments) if argument.startswith("-")), len(arguments))
    result = []
    while index < len(arguments):
        if arguments[index] == "-p":
            require(index + 1 < len(arguments), "Incomplete JMH parameter option")
            index += 2
        else:
            result.append(arguments[index])
            index += 1
    return result


def run_shard(plan_root, attempt_root, shard_id, workspace):
    """Collect every frozen paired slot serially under one actual host lease, retaining failures."""
    plan_root, attempt_root, workspace = map(Path, (plan_root, attempt_root, workspace))
    plan = load_plan(plan_root)
    attempt = document(attempt_root / "attempt.json")
    permit = document(attempt_root / "permits" / (shard_id + ".json"))
    require(permit["context"] == {key: attempt[key] for key in ("plan_id", "plan_sha256", "inventory_id", "whole_attempt", "scheduling")}
            and permit["shard_id"] == shard_id and permit["context"]["plan_sha256"] == digest(plan_root / "plan.json")
            and permit["shard_attempt"] == attempt["shard_attempts"][shard_id], "Mixed whole/shard attempt")
    shard = next(s for s in plan["shards"] if s["id"] == shard_id)
    target = attempt_root / "shards" / shard_id
    target.mkdir(parents=True, exist_ok=False)
    result = {"permit_sha256": digest(attempt_root / "permits" / (shard_id + ".json")), "executor": shard["executor"],
              "whole_attempt": attempt["whole_attempt"], "shard_attempt": permit["shard_attempt"], "slots": [], "elapsed_seconds": 0}
    started = time.monotonic()
    try:
        with exclusive_lease() as lease:
            for name, selection in shard["selections"].items():
                parameter_path = target / (name + ".properties")
                parameter_path.write_bytes(parameters_bytes(selection["selector"]))
                for mode in Mode:
                    for variant_label, repetition in plan["interleaving"]:
                        variant = Variant(variant_label)
                        slot_id = "-".join((name, mode.value, variant.value, str(repetition)))
                        observed = observe(plan["executors"][shard["executor"]]["java"])
                        require(observed["conditions"] == plan["executors"][shard["executor"]]["conditions"], "Executor migrated or conditions changed")
                        context = {**permit["context"], "shard_id": shard_id, "shard_attempt": permit["shard_attempt"],
                                   "suite": name, "mode": mode.value, "variant": variant.value, "repetition": repetition,
                                   "executor": shard["executor"], "executor_conditions": identity(observed["conditions"]),
                                   "executor_profile": observed["conditions"], "executor_probe": str((plan_root / "cpu_host.py").resolve()),
                                   "executor_probe_sha256": plan["executor_probe_sha256"], "python": sys.executable,
                                   "lease_id": lease, "selector_sha256": selection["selector_sha256"]}
                        context_path = target / (slot_id + "-context.json")
                        write_new(context_path, context)
                        write_new(target / (slot_id + "-before.json"), observed)
                        output = target / slot_id
                        substitutions = {"workspace": str(workspace.resolve()), "parameters": str(parameter_path.resolve()),
                                         "context": str(context_path.resolve()), "output": str(output.resolve()),
                                         "repetition": str(repetition), "mode": mode.value,
                                         "methods": ",".join(selection["selector"].get("methods", []))}
                        command = [token.format_map(substitutions) for token in plan["corpora"][name]["commands"][variant.value]]
                        with (target / (slot_id + ".log")).open("xb") as log:
                            cost = run_process(command, workspace, log)
                        after = observe(plan["executors"][shard["executor"]]["java"])
                        write_new(target / (slot_id + "-after.json"), after)
                        slot = {"id": slot_id, "context": context, "output": slot_id, "command": command, "cost": cost,
                                "before": slot_id + "-before.json", "after": slot_id + "-after.json"}
                        result["slots"].append(slot)
                        require(cost["exit_code"] == 0 and observed["conditions"] == after["conditions"], "Collector failed or host conditions changed")
            result["status"] = Outcome.PASSED.value
    except BaseException as failure:
        result.update(status=Outcome.FAILED.value, failure=type(failure).__name__ + ": " + str(failure))
        raise
    finally:
        result["elapsed_seconds"] = time.monotonic() - started
        write_new(target / "shard.json", result)
    return result


def acknowledge(plan_root, attempt_root, shard_id):
    """Record coordinator occupancy including queue, remote transfer and acknowledgement costs."""
    plan = load_plan(plan_root)
    attempt = coordinator(attempt_root)
    require(shard_id in attempt["shard_attempts"] and attempt["plan_id"] == plan["plan_id"], "Foreign acknowledgement")
    permit_path = Path(attempt_root) / "permits" / (shard_id + ".json")
    result_path = Path(attempt_root) / "shards" / shard_id / "shard.json"
    result = document(result_path)
    require(result["whole_attempt"] == attempt["whole_attempt"] and result["shard_attempt"] == attempt["shard_attempts"][shard_id], "Mixed acknowledgement")
    write_new(Path(attempt_root) / "acks" / (shard_id + ".json"),
              {"permit_sha256": digest(permit_path), "shard_sha256": digest(result_path), "acknowledged_ns": time.monotonic_ns()})


def strict_compare(plan_root, plan, target, baseline, candidate):
    """Invoke the fixed existing JVM comparator on raw sources; assembled receipts are insufficient."""
    classpath = [str((Path(plan_root) / entry["path"]).resolve()) for entry in plan["comparator"]["classpath"]]
    request = {"command": "jmh-comparison", "collector": classpath[0], "output": str(target.resolve()),
               "repetitions": 3, "baseline": [str(p.resolve()) for p in baseline], "candidate": [str(p.resolve()) for p in candidate]}
    request_path = target.with_suffix(".request.json")
    write_new(request_path, request)
    command = [plan["comparator"]["java"], "-cp", os.pathsep.join(classpath),
               "dev.s7a.strata.performance.PerformanceEvidenceCli", str(request_path.resolve())]
    with target.with_suffix(".log").open("xb") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    comparison = document(target)
    require(comparison["contract"] == "strata-jmh-comparison-v1" and Outcome(comparison["status"]) == Outcome.PASSED, "Strict comparison did not complete")
    return comparison


def verify_forks(directory, receipt, raw, context, seen):
    """Require actual unique invocation-bound warm-up and measurement provenance for every row."""
    grouped = defaultdict(lambda: defaultdict(list))
    sources = sorted((directory / "cpu-forks").glob("*.json"))
    require(sources, "Missing actual fork identities")
    for path in sources:
        value = document(path)
        require(value["contract"] == "strata-jmh-cpu-fork-v1" and Outcome(value["status"]) == Outcome.PASSED
                and value["run_id"] == receipt["run_id"] and value["context"] == context, "Foreign fork/iteration context")
        require(value["java_sha256"] == seen["java_sha256"], "Fork actually loaded another JDK")
        require(value["executor_observation"]["conditions"] == value["executor_after"]["conditions"] == context["executor_profile"], "Actual fork CPU, power, affinity or host changed")
        require(value["executor_observation"]["sources"]["probe_sha256"] == value["executor_after"]["sources"]["probe_sha256"] == context["executor_probe_sha256"], "Actual fork probe source changed")
        require(value["iteration_id"] not in seen["iterations"], "Reused iteration")
        seen["iterations"].add(value["iteration_id"])
        loaded = value["loaded_identity"]
        require(loaded["fixtures"] == receipt["fixture_identity"] and loaded["collector"] == receipt["collector_identity"]
                and loaded["harness"] == receipt["harness_sha256"] and loaded["runtime"] == receipt["runtime_metadata"], "Changed actually loaded fork bytes")
        require(set(loaded["inputs"].values()) == {entry["sha256"] for entry in receipt["inputs"].values()}, "Actual fork external inputs changed")
        grouped[rows([value["workload"]])[0]][value["fork_id"]].append(value)
    require(set(grouped) == set(rows(receipt["registered_workloads"])), "Missing or extra fork rows")
    for row in raw:
        key = rows([[row["benchmark"], row["mode"], row.get("params", {})]])[0]
        forks = grouped[key]
        require(len(forks) == row["forks"], "Incomplete actual fork matrix")
        operations = 0
        for fork_id, iterations in forks.items():
            require(fork_id not in seen["forks"], "Reused fork")
            seen["forks"].add(fork_id)
            starts = {(it["process_start_unix_millis"], it["verified_ready_unix_millis"]) for it in iterations}
            require(len(starts) == 1, "Fork startup source changed between iterations")
            started, ready = starts.pop()
            require(type(started) is int and type(ready) is int and started <= ready, "Unavailable actual fork startup source")
            seen["startup_seconds"] += (ready - started) / 1000
            ordered = sorted(iterations, key=lambda it: it["iteration"])
            require([it["iteration"] for it in ordered] == list(range(row["warmupIterations"] + row["measurementIterations"])), "Missing or duplicate actual iteration")
            require([IterationKind(it["kind"]) for it in ordered] == [IterationKind.WARMUP] * row["warmupIterations"] + [IterationKind.MEASUREMENT] * row["measurementIterations"], "Changed actual iteration phases")
            operations += sum(it["all_operations"] for it in ordered if IterationKind(it["kind"]) == IterationKind.MEASUREMENT)
        require(operations == sum(sum(values) for values in row["secondaryMetrics"]["strata.all_operations"]["rawData"]), "Raw operations differ from actual fork provenance")


def verify_slot(plan, shard, name, mode, variant, repetition, slot, directory, seen):
    """Bind strict raw sources back to the independently admitted full corpus and frozen selector."""
    receipt = document(directory / "receipt.json")
    context = slot["context"]
    require(type(context["repetition"]) is int, "Ambiguous repetition identity")
    require(receipt["cpu_context"] == context and receipt["repetition"] == repetition, "Mixed invocation context/repetition")
    require(receipt["run_id"] not in seen["runs"], "Reused collector invocation")
    seen["runs"].add(receipt["run_id"])
    expected = plan["corpora"][name]["modes"][mode]["sides"][variant]["admission"]
    require(execution_settings(receipt["arguments"]) == execution_settings(expected["arguments"]), "Reduced or changed original JMH settings")
    require(rows(receipt["registered_workloads"]) == shard["selections"][name]["modes"][mode], "Missing, extra or foreign selected row")
    fixtures = expected["identity"]["fixtures"]
    require(receipt["fixture_identity"] and all(fixtures.get(key) == value for key, value in receipt["fixture_identity"].items()), "Changed fixture/generated trees")
    owners = {json.loads(row)[0].rsplit(".", 1)[0] for row in receipt["registered_workloads"]}
    require(owners <= {key.replace("$", ".") for key in receipt["fixture_identity"]}, "Missing actual selected fixture identity")
    require(receipt["harness_sha256"] == expected["sources"]["harness"]["sha256"]
            and receipt["collector_identity"] == expected["collector_identity"], "Changed collector or harness")
    require(digest(directory / "harness.jar") == receipt["harness_sha256"]
            and digest(directory / "collector.jar") == receipt["collector_identity"]["code_source_sha256"], "Changed preserved collector/harness")
    require({key: value["sha256"] for key, value in receipt["inputs"].items()}
            == {key: value["sha256"] for key, value in expected["inputs"].items()}, "Changed inputs/controls")
    for entry in receipt["inputs"].values():
        require(digest(direct(directory, entry["archive"])) == entry["sha256"], "Changed actual external input")
    for module in receipt["runtime_metadata"]["modules"]:
        require(digest(direct(directory, receipt["target_archives"][module["module"]])) == module["codeSource"]["sha256"], "Changed preserved runtime archive")
    normalized = {"identity": {"runtime": receipt["runtime_metadata"]},
                  "targets": {key: {"sha256": runtime(expected)[key]["archive"]} for key in expected["targets"]}}
    require(runtime(normalized) == runtime(expected), "Changed actual runtime archive/tree/origin")
    require(digest(directory / "results.json") == receipt["results_sha256"], "Changed raw results")
    costs = document(directory / "cpu-costs.json")
    require(costs["contract"] == "strata-jmh-cpu-costs-v1" and costs["run_id"] == receipt["run_id"] and costs["context"] == context, "Missing actual collector phase sources")
    for key in ("parent_preparation_seconds", "harness_seconds", "parent_processing_seconds"):
        require(isinstance(costs[key], (int, float)) and math.isfinite(costs[key]) and 0 <= costs[key], "Unavailable collector phase cost")
        seen[key] += costs[key]
    raw = document_array(directory / "results.json")
    require(rows([[row["benchmark"], row["mode"], row.get("params", {})] for row in raw]) == shard["selections"][name]["modes"][mode], "Raw matrix differs")
    verify_forks(directory, receipt, raw, context, seen)
    return raw


def document_array(path):
    """Decode a bounded raw JMH array using the same strict JSON boundary."""
    value = document(path)
    require(isinstance(value, list), "Expected raw JMH array")
    return value


def validate_attempt(plan_root, attempt_root, comparison_root):
    """Revalidate every shard/mode/suite/variant/repetition and invoke strict per-shard comparison."""
    plan_root, attempt_root, comparison_root = map(Path, (plan_root, attempt_root, comparison_root))
    plan = load_plan(plan_root)
    attempt = document(attempt_root / "attempt.json")
    require(attempt["plan_sha256"] == digest(plan_root / "plan.json") and attempt["plan_id"] == plan["plan_id"]
            and attempt["inventory_id"] == plan["inventory_id"], "Mixed plan/inventory/attempt")
    ids = {shard["id"] for shard in plan["shards"]}
    require(set(attempt["shard_attempts"]) == ids and len(set(attempt["shard_attempts"].values())) == len(ids), "Missing or duplicate shard attempts")
    require({p.stem for p in (attempt_root / "acks").glob("*.json")} == ids
            and {p.name for p in (attempt_root / "shards").iterdir()} == ids, "Missing or extra shard results/acks")
    seen = {key: set() for key in ("runs", "forks", "iterations")}
    seen.update({key: 0 for key in ("startup_seconds", "parent_preparation_seconds", "harness_seconds", "parent_processing_seconds")})
    comparisons = []
    intervals = []
    aggregate_cpu = 0
    aggregate_allocated = 0
    executor_seconds = 0
    for shard in plan["shards"]:
        name = shard["id"]
        root = attempt_root / "shards" / name
        result = document(root / "shard.json")
        permit = document(attempt_root / "permits" / (name + ".json"))
        ack = document(attempt_root / "acks" / (name + ".json"))
        require(Outcome(result["status"]) == Outcome.PASSED and result["whole_attempt"] == attempt["whole_attempt"]
                and result["shard_attempt"] == attempt["shard_attempts"][name] and result["executor"] == shard["executor"], "Failed or mixed shard")
        require(permit["context"] == {key: attempt[key] for key in ("plan_id", "plan_sha256", "inventory_id", "whole_attempt", "scheduling")}
                and permit["shard_id"] == name and permit["shard_attempt"] == attempt["shard_attempts"][name], "Mixed permit")
        require(result["permit_sha256"] == ack["permit_sha256"] == digest(attempt_root / "permits" / (name + ".json"))
                and ack["shard_sha256"] == digest(root / "shard.json"), "Changed permit or shard acknowledgement")
        intervals.append((permit["granted_ns"], ack["acknowledged_ns"]))
        executor_seconds += result["elapsed_seconds"]
        expected_slots = [(suite, mode.value, side, index) for suite in shard["selections"] for mode in Mode for side, index in plan["interleaving"]]
        require(len(result["slots"]) == len(expected_slots) and len({slot["id"] for slot in result["slots"]}) == len(expected_slots), "Incomplete or duplicate paired slots")
        require(len({slot["context"]["lease_id"] for slot in result["slots"]}) == 1, "Paired shard changed its exclusive lease")
        pairs = defaultdict(lambda: defaultdict(list))
        for slot, (suite, mode, side, repetition) in zip(result["slots"], expected_slots):
            context = slot["context"]
            expected_context = {**permit["context"], "shard_id": name, "shard_attempt": attempt["shard_attempts"][name],
                                "suite": suite, "mode": mode, "variant": side, "repetition": repetition, "executor": shard["executor"],
                                "executor_conditions": identity(plan["executors"][shard["executor"]]["conditions"]),
                                "executor_profile": plan["executors"][shard["executor"]]["conditions"], "executor_probe": context["executor_probe"],
                                "executor_probe_sha256": plan["executor_probe_sha256"], "python": context["python"],
                                "selector_sha256": shard["selections"][suite]["selector_sha256"], "lease_id": context["lease_id"]}
            require(context == expected_context, "Mixed plan/shard/attempt/mode/suite/variant slot")
            before, after = (document(direct(root, slot[key])) for key in ("before", "after"))
            require(before["conditions"] == after["conditions"] == plan["executors"][shard["executor"]]["conditions"], "Executor migrated or conditions changed")
            require(slot["cost"]["exit_code"] == 0 and slot["cost"]["cpu_seconds"] is not None, "Incomplete actual process CPU evidence")
            aggregate_cpu += slot["cost"]["cpu_seconds"]
            directory = direct(root, slot["output"])
            seen["java_sha256"] = plan["executors"][shard["executor"]]["conditions"]["java_sha256"]
            parameter_path = root / (suite + ".properties")
            require(parameter_path.read_bytes() == parameters_bytes(shard["selections"][suite]["selector"]), "Changed actual selector bytes")
            raw = verify_slot(plan, shard, suite, mode, side, repetition, slot, directory, seen)
            for row in raw:
                ops = row["secondaryMetrics"]["strata.all_operations"]["rawData"]
                allocation = row["secondaryMetrics"]["gc.alloc.rate.norm"]["rawData"]
                require(len(ops) == len(allocation) and all(len(a) == len(b) for a, b in zip(ops, allocation)), "Incomplete allocation accounting")
                require(all(isinstance(value, (int, float)) and math.isfinite(value) and 0 <= value for arrays in (ops, allocation) for values in arrays for value in values), "Unavailable allocation accounting")
                aggregate_allocated += sum(count * rate for counts, rates in zip(ops, allocation) for count, rate in zip(counts, rates))
            pairs[(suite, mode)][side].append(directory)
        for (suite, mode), pair in pairs.items():
            target = comparison_root / (name + "-" + suite + "-" + mode + ".json")
            target.parent.mkdir(parents=True, exist_ok=True)
            comparisons.append(strict_compare(plan_root, plan, target, pair[Variant.BASELINE.value], pair[Variant.CANDIDATE.value]))
    require(all(start <= end for start, end in intervals), "Invalid coordinator occupancy")
    if Scheduling(attempt["scheduling"]) == Scheduling.SERIAL:
        require(all(first[1] <= second[0] for first, second in zip(intervals, intervals[1:])), "Serial executor occupancy overlapped")
    return {"contract": "strata-jmh-cpu-whole-attempt-v1", "status": Outcome.PASSED.value,
            "context": attempt, "comparisons": comparisons, "collector_invocations": len(seen["runs"]),
            "forks": len(seen["forks"]), "iterations": len(seen["iterations"]), "aggregate_cpu_seconds": aggregate_cpu,
            "measured_jmh_allocated_bytes": aggregate_allocated, "aggregate_executor_seconds": executor_seconds,
            "longest_executor_seconds": max(document(attempt_root / "shards" / s["id"] / "shard.json")["elapsed_seconds"] for s in plan["shards"]),
            "coordinator_occupancy_seconds": sum((end - start) / 1e9 for start, end in intervals),
            "longest_coordinator_occupancy_seconds": max((end - start) / 1e9 for start, end in intervals),
            "independent_executors": len(plan["executors"]),
            "aggregate_fork_startup_seconds": seen["startup_seconds"],
            "collector_phase_seconds": {key: seen[key] for key in ("parent_preparation_seconds", "harness_seconds", "parent_processing_seconds")}}


def preserved_sources(root):
    """Bind terminal outcomes to every raw result, archive, provenance source, log and failure."""
    root = Path(root)
    return {path.relative_to(root).as_posix(): digest(path) for path in sorted(root.rglob("*"))
            if path.is_file() and path != root / "outcome.json"}


def phase_source(path):
    """Verify preserved successful or failed phase sources without discarding failed costs."""
    path = Path(path)
    value = document(path)
    require(value["contract"] == "strata-cpu-phase-v1", "Missing actual preparation/transfer source")
    Outcome(value["status"])
    require(digest(path.parent / "spec.json") == value["spec_sha256"] and digest(path.parent / "command.log") == value["log_sha256"], "Changed phase command/log source")
    spec = document(path.parent / "spec.json")
    require(Phase(spec["phase"]).value == value["phase"] and spec["binding"] == value["binding"], "Changed phase binding")
    require(type(value["elapsed_seconds"]) in (int, float) and math.isfinite(value["elapsed_seconds"]) and 0 <= value["elapsed_seconds"], "Invalid observed phase elapsed cost")
    if "cost" in value:
        require(type(value["cost"]["cpu_seconds"]) in (int, float) and math.isfinite(value["cost"]["cpu_seconds"]) and 0 <= value["cost"]["cpu_seconds"], "Invalid observed phase CPU cost")
    return value


def verify_phase(path):
    """Require complete successful phase sources when admitting preparation or adopted transfers."""
    value = phase_source(path)
    require(Outcome(value["status"]) == Outcome.PASSED, "Missing actual successful preparation/transfer source")
    require(value["cost"]["exit_code"] == 0 and value["cost"]["cpu_seconds"] is not None, "Missing actual phase CPU cost")
    require(value["artifacts"], "Missing actual phase artifacts")
    return value


def finish(plan_root, attempt_root):
    """Retain a terminal whole outcome, including rejected or interrupted collection fragments."""
    attempt_root = Path(attempt_root)
    attempt = coordinator(attempt_root)
    started = time.monotonic_ns()
    try:
        outcome = validate_attempt(plan_root, attempt_root, attempt_root / "comparisons")
    except Exception as failure:
        outcome = {"contract": "strata-jmh-cpu-whole-attempt-v1", "status": Outcome.FAILED.value,
                   "context": attempt, "failure": type(failure).__name__ + ": " + str(failure)}
    sources = preserved_sources(attempt_root)
    clock = {"contract": "strata-cpu-completion-clock-v1", "attempt_sha256": digest(attempt_root / "attempt.json"),
             "coordinator": attempt["coordinator"], "processing_started_ns": started, "completed_ns": time.monotonic_ns()}
    write_new(attempt_root / "completion-clock.json", clock)
    outcome["preserved_sources"] = {**sources, "completion-clock.json": digest(attempt_root / "completion-clock.json")}
    outcome.update(clock_costs(attempt_root, attempt))
    write_new(attempt_root / "outcome.json", outcome)
    return outcome


def clock_costs(attempt_root, attempt):
    """Derive outcome durations exclusively from preserved same-boot coordinator bookends."""
    root = Path(attempt_root)
    clock = document(root / "completion-clock.json")
    require(clock["contract"] == "strata-cpu-completion-clock-v1" and clock["attempt_sha256"] == digest(root / "attempt.json")
            and clock["coordinator"] == attempt["coordinator"], "Foreign completion clock")
    require(all(type(value) is int for value in (attempt["started_ns"], clock["processing_started_ns"], clock["completed_ns"]))
            and attempt["started_ns"] <= clock["processing_started_ns"] <= clock["completed_ns"], "Invalid coordinator clock")
    return {"processing_seconds": (clock["completed_ns"] - clock["processing_started_ns"]) / 1e9,
            "whole_wall_seconds": (clock["completed_ns"] - attempt["started_ns"]) / 1e9}


def compare_collection(plan_root, collection, output):
    """Adopt only the first complete success per schedule and revalidate all retained attempt sources."""
    plan_root, collection, output = map(Path, (plan_root, collection, output))
    plan = load_plan(plan_root)
    owner = document(collection / "collection.json")
    conditions = observe()["conditions"]
    require({key: conditions[key] for key in ("physical_id", "boot_id")} == owner["coordinator"], "Coordinator migrated or rebooted")
    require(owner["plan_sha256"] == digest(plan_root / "plan.json"), "Collection changed frozen plan")
    adopted = {}
    history = {}
    failures = []
    global_ids = {key: set() for key in ("whole_attempt", "shard_attempt", "run_id", "fork_id", "iteration_id")}
    for scheduling in Scheduling:
        attempts = sorted(collection.glob(scheduling.value + "-*"), key=lambda path: int(path.name.rsplit("-", 1)[1]))
        require(attempts and len(attempts) <= plan["max_attempts"], "Missing scheduling mode or excessive retries")
        require([path.name for path in attempts] == [scheduling.value + "-" + str(index) for index in range(len(attempts))], "Omitted earlier attempt")
        prior = {}
        for index, root in enumerate(attempts):
            value = document(root / "outcome.json")
            attempt = document(root / "attempt.json")
            require(value["context"] == attempt and attempt["previous_failures"] == prior
                    and Scheduling(attempt["scheduling"]) == scheduling, "Mixed or omitted failed-attempt history")
            require(value["preserved_sources"] == preserved_sources(root), "Earlier raw/failure sources changed or were removed")
            require(all(value[key] == cost for key, cost in clock_costs(root, attempt).items()), "Changed outcome clock costs")
            require(attempt["whole_attempt"] not in history, "Reused whole attempt")
            history[attempt["whole_attempt"]] = (root, value)
            for key, ids in (("whole_attempt", [attempt["whole_attempt"]]), ("shard_attempt", list(attempt["shard_attempts"].values()))):
                require(not global_ids[key].intersection(ids) and len(ids) == len(set(ids)), "Reused attempt identity")
                global_ids[key].update(ids)
            local_forks = set()
            for receipt_path in root.glob("shards/*/*/receipt.json"):
                receipt = document(receipt_path)
                require(receipt["cpu_context"]["whole_attempt"] == attempt["whole_attempt"] and receipt["run_id"] not in global_ids["run_id"], "Reused or foreign invocation from an earlier attempt")
                global_ids["run_id"].add(receipt["run_id"])
                for path in (receipt_path.parent / "cpu-forks").glob("*.json"):
                    fork = document(path)
                    require(fork["fork_id"] not in global_ids["fork_id"] and fork["iteration_id"] not in global_ids["iteration_id"], "Reused earlier fork/iteration")
                    local_forks.add(fork["fork_id"])
                    global_ids["iteration_id"].add(fork["iteration_id"])
            global_ids["fork_id"].update(local_forks)
            if Outcome(value["status"]) == Outcome.PASSED:
                require(index == len(attempts) - 1, "Cherry-picked a later or faster successful attempt")
                validated = validate_attempt(plan_root, root, output.parent / (output.stem + "-revalidation") / root.name)
                require(all(value[key] == derived for key, derived in validated.items()), "Changed derived whole-attempt result")
                adopted[scheduling.value] = {**validated, **clock_costs(root, attempt), "preserved_sources": value["preserved_sources"]}
            else:
                failures.append({"attempt": root.name, "outcome_sha256": digest(root / "outcome.json"), "failure": value["failure"]})
                prior[root.name] = digest(root / "outcome.json")
        require(scheduling.value in adopted, "No complete successful scheduling attempt")
    transfers = []
    prior_transfers = []
    transferred_shards = set()
    phase_roots = sorted((collection / "transfers").iterdir()) if (collection / "transfers").exists() else []
    require(all(path.is_dir() and (path / "phase.json").is_file() for path in phase_roots), "Incomplete retained transfer phase source")
    for phase_root in phase_roots:
        path = phase_root / "phase.json"
        phase = phase_source(path)
        binding = phase["binding"]
        require(Phase(phase["phase"]) == Phase.TRANSFER and binding["plan_sha256"] == owner["plan_sha256"], "Foreign transfer cost")
        scheduling = Scheduling(binding["scheduling"])
        require(binding["whole_attempt"] in history, "Transfer belongs to an omitted or foreign attempt")
        root, outcome = history[binding["whole_attempt"]]
        attempt = outcome["context"]
        require(Scheduling(attempt["scheduling"]) == scheduling and binding["shard_id"] in attempt["shard_attempts"], "Transfer belongs to another schedule/shard")
        if Outcome(phase["status"]) == Outcome.PASSED:
            verify_phase(path)
            shard_path = root / "shards" / binding["shard_id"] / "shard.json"
            require(digest(shard_path) in {entry["sha256"] for entry in phase["artifacts"].values()}, "Transfer does not bind the actual collected shard")
        record = {"phase": phase, "preserved_sources": preserved_sources(path.parent)}
        if binding["whole_attempt"] == adopted[scheduling.value]["context"]["whole_attempt"] and Outcome(phase["status"]) == Outcome.PASSED:
            transferred_shards.add((scheduling.value, binding["shard_id"]))
            transfers.append(record)
        else:
            prior_transfers.append(record)
    require(transferred_shards == {(scheduling.value, shard["id"]) for scheduling in Scheduling for shard in plan["shards"]}, "Missing observed transfer costs for complete scheduling attempts")
    serial, parallel = (adopted[value.value] for value in Scheduling)
    require(serial["context"]["started_ns"] <= parallel["context"]["started_ns"], "Frozen scheduling order changed")
    result = {"contract": "strata-jmh-cpu-scheduling-comparison-v1", "status": Outcome.PASSED.value,
              "plan_id": plan["plan_id"], "plan_sha256": digest(plan_root / "plan.json"), "inventory_id": plan["inventory_id"],
              "scheduling_attempts": adopted, "failures": failures,
              "preparation_costs": [verify_phase(plan_root / value["path"]) for value in plan["preparation_costs"]], "transfer_costs": transfers,
              "retained_transfer_diagnostics": prior_transfers,
              "whole_collection_wall_seconds": (time.monotonic_ns() - owner["started_ns"]) / 1e9,
              "observed_attempt_wall_delta_percent": 100 * (parallel["whole_wall_seconds"] / serial["whole_wall_seconds"] - 1),
              "cpu_scope": "JMH CPU collection only; original native/GPU/loaded-game gates remain independent"}
    write_new(output, result)
    return result


def main():
    """Use explicit coordinator/worker operations; caller-owned jobs schedule and transfer bundles."""
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    freeze_parser = sub.add_parser("freeze")
    freeze_parser.add_argument("config", type=Path)
    freeze_parser.add_argument("output", type=Path)
    phase_parser = sub.add_parser("measure-phase")
    phase_parser.add_argument("spec", type=Path)
    phase_parser.add_argument("output", type=Path)
    for command in ("begin", "grant", "run-shard", "ack", "finish", "compare"):
        entry = sub.add_parser(command)
        entry.add_argument("plan", type=Path)
        entry.add_argument("attempt", type=Path)
        if command == "begin":
            entry.add_argument("scheduling", choices=[value.value for value in Scheduling])
        if command in ("grant", "run-shard", "ack"):
            entry.add_argument("shard")
        if command == "run-shard":
            entry.add_argument("workspace", type=Path)
        if command == "compare":
            entry.add_argument("output", type=Path)
    args = parser.parse_args()
    handlers = {"freeze": lambda: freeze(args.config, args.output),
                "measure-phase": lambda: measure_phase(args.spec, args.output),
                "begin": lambda: str(begin(args.plan, args.attempt, args.scheduling)),
                "grant": lambda: grant(args.plan, args.attempt, args.shard),
                "run-shard": lambda: run_shard(args.plan, args.attempt, args.shard, args.workspace),
                "ack": lambda: acknowledge(args.plan, args.attempt, args.shard),
                "finish": lambda: finish(args.plan, args.attempt),
                "compare": lambda: compare_collection(args.plan, args.attempt, args.output)}
    result = handlers[args.command]()
    print(json.dumps(result, indent=2))
    if isinstance(result, dict) and result.get("status") == Outcome.FAILED.value:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
