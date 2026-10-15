#!/usr/bin/env python3
"""Create bounded real owned descendants and retain their independently read PID/birth identities."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def main():
    """Run eight concurrent children per batch; each remains observable for five capture periods."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--child", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--batches", type=int, default=36)
    arguments = parser.parse_args()
    if arguments.child is not None:
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.GetCurrentProcess.restype = ctypes.c_void_p
        kernel.GetProcessTimes.argtypes = [ctypes.c_void_p] + [ctypes.POINTER(ctypes.c_ulonglong)] * 4
        created, exited, system, user = (ctypes.c_ulonglong() for _ in range(4))
        if not kernel.GetProcessTimes(kernel.GetCurrentProcess(), ctypes.byref(created), ctypes.byref(exited), ctypes.byref(system), ctypes.byref(user)):
            raise ctypes.WinError(ctypes.get_last_error())
        with arguments.child.open("x", encoding="utf-8") as target:
            json.dump({"pid": os.getpid(), "creationFileTime": created.value}, target)
        time.sleep(0.5)
        return
    if arguments.output is None:
        parser.error("Supply an output for the owned descendant history")
    arguments.output.mkdir(parents=True, exist_ok=False)
    records = []
    if arguments.batches < 1:
        parser.error("Supply a positive batch count")
    for batch in range(arguments.batches):
        children = []
        for index in range(8):
            receipt = arguments.output / f"child-{batch * 8 + index}.json"
            process = subprocess.Popen([sys.executable, "-B", str(Path(__file__).resolve()), "--child", str(receipt)], creationflags=subprocess.CREATE_NO_WINDOW)
            children.append((process, receipt))
        for process, receipt in children:
            code = process.wait()
            if code != 0:
                raise ValueError("Owned history child failed")
            record = json.loads(receipt.read_text(encoding="utf-8"))
            if record["pid"] != process.pid:
                raise ValueError("Owned child receipt identity changed")
            records.append({**record, "exitCode": code})
    with (arguments.output / "completed.json").open("x", encoding="utf-8") as target:
        json.dump({"concurrentChildren": 8, "generations": records}, target, indent=2)


if __name__ == "__main__":
    main()
