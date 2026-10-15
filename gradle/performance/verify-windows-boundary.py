#!/usr/bin/env python3
"""Verify the owned Windows boundary's real refusal, failure, timeout and terminal release without Java."""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import sys


SPEC = importlib.util.spec_from_file_location("paired_jvm", Path(__file__).with_name("paired-jvm.py"))
PAIRED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAIRED)


def main():
    """Launch only temporary owned PowerShell/cmd/Python descendants and retain every negative record."""
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
        PAIRED.write_new(output / "result.json", {"status": "passed", "java_invocations": 0, "cases": records})
    except BaseException as failure:
        PAIRED.write_new(output / "result.json", {"status": "failed", "error": str(failure), "cases": records})
        raise
    finally:
        print(f"Preserved Windows boundary verification: {PAIRED.pack(output)}")


if __name__ == "__main__":
    main()
