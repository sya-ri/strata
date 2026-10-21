#!/usr/bin/env python3
"""Verify the owned Windows boundary's real refusal, failure, timeout and terminal release without Java."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys


SPEC = importlib.util.spec_from_file_location("paired_jvm", Path(__file__).with_name("paired-jvm.py"))
PAIRED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAIRED)


def validate_history_refusal(terminal, report_bytes):
    """Keep an actual history refusal fatal while requiring independently completed cleanup and accounting."""
    limit = 3 * 1024**2
    PAIRED.require("history exceeds the existing terminal report bound" in terminal.get("error", "")
                   and terminal.get("naturalRelease") is False and terminal.get("timedOut") is False,
                   "History budget did not refuse the owned phase before its deadline")
    PAIRED.require(terminal.get("handlesClosed") is True and terminal.get("activeProcessesAfterCleanup") == 0
                   and type(terminal.get("activeProcessesAfterCleanup")) is int and terminal.get("notificationReaderJoined") is True
                   and "cleanupError" not in terminal, "Refused history did not complete terminal cleanup")
    members = [member for histories in terminal["members"].values() for member in histories]
    identities = {(member["pid"], member["creationFileTime"]) for member in members}
    PAIRED.require(256 < len(members) == len(identities)
                   and all(member.get("completed") is True and member.get("handleClosed") is True
                           and member["creationFileTime"] <= member["exitFileTime"] for member in members)
                   and terminal["maximumRetainedHandles"] <= 256 and terminal["maximumListedMembers"] <= 256,
                   "History refusal did not preserve complete bounded process generations")
    accounting = terminal.get("generationAccounting", {})
    PAIRED.require(accounting.get("schema") == "strata-owned-generation-accounting-v1"
                   and type(accounting.get("complete")) is bool and type(accounting.get("totalProcesses")) is int
                   and len(members) <= accounting["totalProcesses"] == terminal.get("totalProcesses")
                   and (accounting["complete"] is False or accounting["totalProcesses"] == len(members))
                   and accounting.get("capturedGenerations") == accounting.get("closedGenerations") == len(members),
                   "Refused history lost actual final Job accounting")
    acquisition = terminal.get("processAcquisition", {})
    PAIRED.require(all(type(acquisition.get(key)) is int and acquisition[key] == 0
                       for key in ("pending", "metadataInFlight", "physicalHandles", "unpublishedByteBound")),
                   "Refused history retained acquired handles or metadata")
    PAIRED.require(limit < terminal["memberHistorySerializedByteBound"] <= limit + 1024 + 6 * 32768
                   and report_bytes <= 4 * 1024**2, "Refused history escaped the unchanged terminal-reader capacity")


def validate_immediate_history(terminal, actual, held_count=0):
    """Match every independently held short descendant to complete physical Job generations."""
    PAIRED.verify_generation_accounting(terminal)
    acquisition = terminal.get("processAcquisition", {})
    PAIRED.require(0 < acquisition.get("maximumPending", 0) and 0 < terminal.get("notificationMetadataSuccesses", 0),
                   "Immediate controls did not exercise bounded pending metadata acquisition")
    PAIRED.require(actual.get("immediate") is True and actual.get("concurrentChildren") == 8 and len(actual["generations"]) == 32,
                   "Incomplete independent immediate-descendant inventory")
    held = actual.get("held_generations", [])
    PAIRED.require(isinstance(held, list) and len(held) == held_count, "Incomplete independently held metadata inventory")
    if held_count:
        PAIRED.require(held_count <= terminal["maximumRetainedHandles"] <= 256, "Concurrent metadata control did not retain its live inventory")
    observed = {(member["pid"], member["creationFileTime"]): member
                for histories in terminal["members"].values() for member in histories}
    identities = set()
    lifetimes = []
    for index, child in enumerate(actual["generations"] + held):
        identity = (child["pid"], child["creationFileTime"])
        PAIRED.require(identity not in identities and identity in observed, "Missing or duplicate independent short generation")
        identities.add(identity)
        member = observed[identity]
        PAIRED.require(child["exitCode"] == member["exitCode"] == 0 and child["exitFileTime"] == member["exitFileTime"]
                       and child["creationFileTime"] <= child["exitFileTime"]
                       and os.path.normcase(child["image"]) == os.path.normcase(member["image"]), "Independent short generation changed image or lifetime")
        if index < len(actual["generations"]):
            lifetimes.append(child["exitFileTime"] - child["creationFileTime"])
    PAIRED.require(any(lifetime < 100 * 10000 for lifetime in lifetimes), "Regression control did not observe a lifetime below the former 100ms poll interval")
    return {"independentGenerations": len(actual["generations"]), "heldMetadataGenerations": len(held), "totalIndependentGenerations": len(identities), "observedBelowFormerPollInterval": sum(lifetime < 100 * 10000 for lifetime in lifetimes),
            "minimumLifetime100ns": min(lifetimes), "formerPollInterval100ns": 100 * 10000, "processAcquisition": acquisition,
            "imageAcquisitions": terminal["notificationImageSuccesses"], "metadataAcquisitions": terminal["notificationMetadataSuccesses"]}


def validate_node_leak(terminal, program, console):
    """Bind the child-ready IPC identity to an owned generation that survives its successful primary."""
    lines = console.splitlines()
    PAIRED.require(bool(lines), "Missing child-ready IPC acknowledgement")
    observed = json.loads(lines[0])
    PAIRED.require(isinstance(observed, dict) and set(observed) == {"readyChildPid"} and type(observed["readyChildPid"]) is int,
                   "Malformed child-ready IPC acknowledgement")
    members = [member for generations in terminal["members"].values() for member in generations]
    primaries = [member for member in members if member["pid"] == terminal["callerPid"] and member.get("primaryBeforeResume") is True]
    children = [member for member in members if member["pid"] == observed.get("readyChildPid") and member["pid"] != terminal["callerPid"]]
    PAIRED.require(len(primaries) == 1 and len(children) == 1, "Child-ready IPC identity lost its actual owned generation")
    primary, child = primaries[0], children[0]
    PAIRED.require(terminal["naturalRelease"] is False and terminal["timedOut"] is False and 0 < terminal["activeProcessesAtRelease"]
                   and terminal["handlesClosed"] is True and terminal["activeProcessesAfterCleanup"] == 0 and terminal["exitCode"] == 0
                   and primary["exitCode"] == 0 and child["inOwnedJob"] is True and child["completed"] is True and child["handleClosed"] is True
                   and child["creationFileTime"] <= primary["exitFileTime"] < child["exitFileTime"]
                   and os.path.normcase(child["image"]) == os.path.normcase(program["path"])
                   and child["affinityMask"] == terminal["inheritedAffinityMask"] and child["systemAffinityMask"] == terminal["systemAffinityMask"]
                   and terminal["observedJobLimitBytes"] == 3 * 1024**3 and terminal["observedJobFlags"] & 0x2000
                   and terminal["observedJobFlags"] & (0x800 | 0x1000) == 0, "Ready Node child did not survive its primary inside the unchanged owned Job")


def main():
    """Launch only temporary owned boundary controls and retain every negative record."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    output = parser.parse_args().output.resolve()
    PAIRED.require(os.name == "nt", "Windows boundary verification requires Windows")
    output.mkdir(parents=True, exist_ok=False)
    records = []
    try:
        helper = Path(__file__).with_name("invoke-windows.ps1")
        history = Path(__file__).with_name("windows-process-history.py").resolve()
        for name, body, minimum, work in (
            ("normal", "@exit /b 0\n", 4 * 1024**3, 30),
            ("completed-history", f'@"{sys.executable}" -B "{history}" --output descendants\n', 4 * 1024**3, 120),
            ("immediate-history", f'@"{sys.executable}" -B "{history}" --output descendants --batches 4 --immediate\n', 4 * 1024**3, 120),
            ("contended-immediate-history", f'@"{sys.executable}" -B "{history}" --output descendants --batches 4 --immediate --held 64\n', 4 * 1024**3, 120),
            ("history-budget-refusal", f'@"{sys.executable}" -B "{history}" --output descendants --batches 512 --immediate\n', 4 * 1024**3, 120),
            ("failure", "@exit /b 7\n", 4 * 1024**3, 30),
            ("invalid-handle-error-result", f'@"{sys.executable}" -c "import ctypes,json; k=ctypes.WinDLL(\'kernel32\',use_last_error=True); k.CloseHandle.argtypes=[ctypes.c_void_p]; k.CloseHandle.restype=ctypes.c_int; ctypes.set_last_error(0); r=k.CloseHandle(ctypes.c_void_p(0x12345678)); e=ctypes.get_last_error(); print(json.dumps(dict(result=r,error=e)))"\n', 4 * 1024**3, 30),
            ("unhandled-native-exception", f'@"{sys.executable}" -c "import ctypes; ctypes.WinDLL(\'kernel32\').RaiseException(ctypes.c_uint32(0xe0424242),0,0,None)"\n', 4 * 1024**3, 30),
            ("ram-refusal", "@exit /b 0\n", 2**50, 30),
            ("timeout", '@python -c "import time; time.sleep(30)"\n', 4 * 1024**3, 2),
            ("leaked-child", '@start "" /b python -c "import time; time.sleep(30)"\n@exit /b 0\n', 4 * 1024**3, 30),
        ):
            directory = output / name
            workspace = directory / "workspace"
            workspace.mkdir(parents=True)
            (workspace / "gradlew.bat").write_text(body, encoding="ascii")
            request = directory / "request.json"
            PAIRED.write_new(request, {"workspace": str(workspace), "arguments": [], "environment": {}, "minimumFreePhysicalBytes": minimum,
                                      "jobCommittedBytes": 3 * 1024**3, "workSeconds": work, "cleanupSeconds": 2})
            with (directory / "console.log").open("xb") as log:
                process = subprocess.run(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(helper), "-Request", str(request), "-Report", str(directory / "terminal.json")], stdout=log, stderr=subprocess.STDOUT)
            terminal = PAIRED.read_json(directory / "terminal.json")
            records.append({"case": name, "exit": process.returncode, "terminal": terminal})
            PAIRED.require((process.returncode == 0) == (name in ("normal", "completed-history", "immediate-history", "contended-immediate-history", "invalid-handle-error-result")), f"Unexpected actual boundary result: {name}")
            PAIRED.require(terminal["handlesClosed"] is True, f"Owned handles or cleanup leaked: {name}")
            PAIRED.require(terminal.get("activeProcessesAfterCleanup", 0) == 0, f"Owned descendants remain: {name}")
            PAIRED.require(all(member["completed"] is True and member["handleClosed"] is True
                               and member["creationFileTime"] <= member["exitFileTime"]
                               for generations in terminal["members"].values() for member in generations),
                           f"Observed generation lost actual terminal history: {name}")
            if name in ("immediate-history", "contended-immediate-history"):
                records[-1]["independentCapture"] = validate_immediate_history(terminal, PAIRED.read_json(workspace / "descendants/completed.json"), 64 if name == "contended-immediate-history" else 0)
            elif name == "completed-history":
                PAIRED.verify_generation_accounting(terminal)
                actual = PAIRED.read_json(workspace / "descendants/completed.json")
                observed = {(member["pid"], member["creationFileTime"]): member
                            for generations in terminal["members"].values() for member in generations}
                PAIRED.require(len(actual["generations"]) == 288 and actual["concurrentChildren"] == 8,
                               "Incomplete independent completed-descendant inventory")
                identities = {(member["pid"], member["creationFileTime"]) for member in actual["generations"]}
                PAIRED.require(len(identities) == 288 and identities <= set(observed), "Boundary missed an actual completed descendant generation")
                PAIRED.require(all(observed[identity]["completed"] is True and observed[identity]["exitCode"] == 0
                                   and observed[identity]["handleClosed"] is True and observed[identity]["creationFileTime"] <= observed[identity]["exitFileTime"]
                                   for identity in identities), "Completed descendant lost exit, birth or handle release")
                PAIRED.require(terminal["maximumRetainedHandles"] <= 256 and terminal["maximumListedMembers"] <= 256
                               and terminal["naturalRelease"] is True, "History exceeded the unchanged live inventory bound")
            elif name == "history-budget-refusal":
                validate_history_refusal(terminal, (directory / "terminal.json").stat().st_size)
            elif name == "ram-refusal":
                PAIRED.require(terminal["nativeInvoked"] is False and terminal["naturalRelease"] is False, "RAM refusal launched a child")
            elif name == "timeout":
                PAIRED.require(terminal["timedOut"] is True and terminal["naturalRelease"] is False, "Deadline did not reject the owned tree")
            elif name == "leaked-child":
                PAIRED.require(terminal["naturalRelease"] is False and 0 < terminal["activeProcessesAtRelease"], "Leaked child was silently accepted")
            elif name == "failure":
                PAIRED.require(terminal["exitCode"] == 7, "Native failure exit was lost")
            elif name == "invalid-handle-error-result":
                actual = json.loads((directory / "console.log").read_text(encoding="utf-8-sig"))
                PAIRED.require(actual == {"result": 0, "error": 6} and 0 < terminal["debuggerInvalidHandleNotifications"]
                               and terminal["naturalRelease"] is True, "Debugger changed the ordinary invalid-handle API result")
                PAIRED.verify_generation_accounting(terminal)
            elif name == "unhandled-native-exception":
                PAIRED.require(terminal["exitCode"] == 1 and terminal["naturalRelease"] is True, "Debugger swallowed an unrelated native exception")
                PAIRED.verify_generation_accounting(terminal)
        launcher = shutil.which("node")
        PAIRED.require(launcher is not None, "Node owner controls require the installed launcher")
        identity = json.loads(subprocess.check_output([launcher, "-p", "JSON.stringify({path:process.execPath,version:process.version})"], text=True))
        executable = Path(identity["path"])
        program = {"kind": "node", "path": str(executable), "sha256": PAIRED.digest(executable)}
        literal_arguments = ["path with spaces", 'quoted "value"', "C:\\trailing\\", "", 'backslash\\"quote', "$() & ; ` literal"]
        marker = "inherited environment with spaces"
        node_normal = "process.stdout.write(JSON.stringify({args:process.argv.slice(1),environment:process.env.STRATA_BOUNDARY_CONTROL,execPath:process.execPath}));"
        for name, body, work in (
            ("node-normal", node_normal, 30),
            *((f"node-immediate-{index}", node_normal, 30) for index in range(8)),
            ("node-failure", "process.exit(7);", 30),
            ("node-timeout", "setTimeout(() => process.exit(0), 30000);", 2),
            # Non-detached Windows children enter libuv's kill-on-parent-close Job, so acknowledge a hidden detached child before primary exit.
            ("node-leaked-child", "const {spawn} = require('node:child_process'); "
             "const child = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 30000); process.send({readyChildPid:process.pid});'], "
             "{detached:true,windowsHide:true,stdio:['ignore','ignore','ignore','ipc']}); "
             "child.once('error', error => {throw error;}); "
             "child.once('exit', () => {throw new Error('Child exited before leak acknowledgement');}); "
             "child.once('message', ready => {if (ready.readyChildPid !== child.pid) throw new Error('Wrong child-ready identity'); "
             "child.disconnect(); child.unref(); process.stdout.write(JSON.stringify(ready) + '\\n', () => process.exit(0));});", 30),
            ("node-pin-refusal", "process.stdout.write('NODE_PIN_REFUSAL_EXECUTED');", 30),
        ):
            directory = output / name
            workspace = directory / "workspace"
            workspace.mkdir(parents=True)
            request = directory / "request.json"
            selected = {**program, "sha256": "0" * 64} if name == "node-pin-refusal" else program
            PAIRED.write_new(request, {"workspace": str(workspace), "arguments": ["-e", body, "--", *literal_arguments], "program": selected, "environment": {"STRATA_BOUNDARY_CONTROL": marker},
                                      "minimumFreePhysicalBytes": 4 * 1024**3, "jobCommittedBytes": 3 * 1024**3, "workSeconds": work, "cleanupSeconds": 2})
            with (directory / "console.log").open("xb") as log:
                process = subprocess.run(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(helper), "-Request", str(request), "-Report", str(directory / "terminal.json")], stdout=log, stderr=subprocess.STDOUT)
            terminal = PAIRED.read_json(directory / "terminal.json")
            records.append({"case": name, "exit": process.returncode, "terminal": terminal})
            normal = name == "node-normal" or name.startswith("node-immediate-")
            PAIRED.require((process.returncode == 0) == normal and terminal["handlesClosed"] is True
                           and terminal.get("activeProcessesAfterCleanup", 0) == 0, "Wrong or unclosed actual Node owner control")
            if normal:
                PAIRED.verify_generation_accounting(terminal)
                PAIRED.browser_controller().validate_node_generation(PAIRED, terminal, program)
                observed = json.loads((directory / "console.log").read_text(encoding="utf-8-sig"))
                PAIRED.require(observed["args"] == literal_arguments and observed["environment"] == marker
                               and os.path.normcase(observed["execPath"]) == os.path.normcase(str(executable)), "Literal native arguments, inherited environment or stdout changed")
                admission = PAIRED.read_json(directory / "child-admission.json")
                PAIRED.require(admission == terminal["primaryAdmission"], "Suspended primary admission bytes changed")
            elif name == "node-failure":
                PAIRED.require(terminal["exitCode"] == 7 and terminal["naturalRelease"] is True, "Actual Node failure exit was lost")
            elif name == "node-timeout":
                PAIRED.require(terminal["timedOut"] is True and terminal["naturalRelease"] is False, "Actual Node deadline did not reject")
            elif name == "node-leaked-child":
                console = (directory / "console.log").read_text(encoding="utf-8-sig")
                validate_node_leak(terminal, program, console)
            else:
                nodes = [member for generations in terminal["members"].values() for member in generations if Path(member["image"]).name.casefold() == "node.exe"]
                PAIRED.require(not nodes and terminal["nativeInvoked"] is False and "differs from its actual pin" in terminal["error"]
                               and "NODE_PIN_REFUSAL_EXECUTED" not in (directory / "console.log").read_text(encoding="utf-8-sig"), "Changed Node pin reached execution")
        PAIRED.write_new(output / "result.json", {"status": "passed", "java_invocations": 0,
            "scope": "owned-process regression controls before Source or runtime collection", "source_task_metrics": None,
            "build_cache": "no Gradle task execution", "helper_sha256": PAIRED.digest(helper),
            "actual_case_names": [record["case"] for record in records], "node": {**program, "version": identity["version"]}, "cases": records})
    except BaseException as failure:
        PAIRED.write_new(output / "result.json", {"status": "failed", "error": str(failure), "cases": records})
        raise
    finally:
        print(f"Preserved Windows boundary verification: {PAIRED.pack(output)}")


if __name__ == "__main__":
    main()
