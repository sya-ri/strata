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
            ("history-budget-refusal", f'@"{sys.executable}" -B "{history}" --output descendants --batches 192\n', 4 * 1024**3, 120),
            ("failure", "@exit /b 7\n", 4 * 1024**3, 30),
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
            PAIRED.require((process.returncode == 0) == (name in ("normal", "completed-history")), f"Unexpected actual boundary result: {name}")
            PAIRED.require(terminal["handlesClosed"] is True, f"Owned handles or cleanup leaked: {name}")
            PAIRED.require(terminal.get("activeProcessesAfterCleanup", 0) == 0, f"Owned descendants remain: {name}")
            PAIRED.require(all(member["completed"] is True and member["handleClosed"] is True
                               and member["creationFileTime"] <= member["exitFileTime"]
                               for generations in terminal["members"].values() for member in generations),
                           f"Observed generation lost actual terminal history: {name}")
            if name == "completed-history":
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
                limit = 3 * 1024**2
                PAIRED.require("history exceeds the existing terminal report bound" in terminal.get("error", "")
                               and terminal["naturalRelease"] is False and terminal["timedOut"] is False,
                               "History budget did not refuse the owned phase before its deadline")
                PAIRED.require(limit < terminal["memberHistorySerializedByteBound"] <= limit + 1024 + 6 * 32768
                               and (directory / "terminal.json").stat().st_size <= 4 * 1024**2,
                               "Refused history escaped the unchanged terminal-reader capacity")
                PAIRED.require(256 < sum(len(generations) for generations in terminal["members"].values())
                               and terminal["maximumRetainedHandles"] <= 256 and terminal["maximumListedMembers"] <= 256,
                               "History refusal did not preserve complete bounded process generations")
            elif name == "ram-refusal":
                PAIRED.require(terminal["nativeInvoked"] is False and terminal["naturalRelease"] is False, "RAM refusal launched a child")
            elif name == "timeout":
                PAIRED.require(terminal["timedOut"] is True and terminal["naturalRelease"] is False, "Deadline did not reject the owned tree")
            elif name == "leaked-child":
                PAIRED.require(terminal["naturalRelease"] is False and 0 < terminal["activeProcessesAtRelease"], "Leaked child was silently accepted")
            elif name == "failure":
                PAIRED.require(terminal["exitCode"] == 7, "Native failure exit was lost")
        launcher = shutil.which("node")
        PAIRED.require(launcher is not None, "Node owner controls require the installed launcher")
        identity = json.loads(subprocess.check_output([launcher, "-p", "JSON.stringify({path:process.execPath,version:process.version})"], text=True))
        executable = Path(identity["path"])
        program = {"kind": "node", "path": str(executable), "sha256": PAIRED.digest(executable)}
        literal_arguments = ["path with spaces", 'quoted "value"', "C:\\trailing\\", "", 'backslash\\"quote', "$() & ; ` literal"]
        marker = "inherited environment with spaces"
        for name, body, work in (
            ("node-normal", "process.stdout.write(JSON.stringify({args:process.argv.slice(1),environment:process.env.STRATA_BOUNDARY_CONTROL,execPath:process.execPath}));", 30),
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
            PAIRED.require((process.returncode == 0) == (name == "node-normal") and terminal["handlesClosed"] is True
                           and terminal.get("activeProcessesAfterCleanup", 0) == 0, "Wrong or unclosed actual Node owner control")
            if name == "node-normal":
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
        PAIRED.write_new(output / "result.json", {"status": "passed", "java_invocations": 0, "node": {**program, "version": identity["version"]}, "cases": records})
    except BaseException as failure:
        PAIRED.write_new(output / "result.json", {"status": "failed", "error": str(failure), "cases": records})
        raise
    finally:
        print(f"Preserved Windows boundary verification: {PAIRED.pack(output)}")


if __name__ == "__main__":
    main()
