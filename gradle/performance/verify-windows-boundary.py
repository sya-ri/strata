#!/usr/bin/env python3
"""Verify the owned Windows boundary's real refusal, failure, timeout and terminal release without Java."""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess


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
        for name, body, minimum, work in (
            ("normal", "@exit /b 0\n", 4 * 1024**3, 30),
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
            PAIRED.require((process.returncode == 0) == (name == "normal"), f"Unexpected actual boundary result: {name}")
            PAIRED.require(terminal["handlesClosed"] is True, f"Owned handles or cleanup leaked: {name}")
            PAIRED.require(terminal.get("activeProcessesAfterCleanup", 0) == 0, f"Owned descendants remain: {name}")
            if name == "ram-refusal":
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
