#!/usr/bin/env python3
"""Create bounded real owned descendants and retain their independently read PID/birth identities."""
import argparse
from contextlib import ExitStack
import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def immediate_child():
    """Hold the stdlib-created suspended child's actual process/thread handles and capture image/birth before resume."""
    import _winapi

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.GetProcessTimes.argtypes = [ctypes.c_void_p] + [ctypes.POINTER(ctypes.c_ulonglong)] * 4
    kernel.QueryFullProcessImageNameW.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_ulong)]
    kernel.ResumeThread.argtypes = [ctypes.c_void_p]
    kernel.ResumeThread.restype = ctypes.c_ulong
    command = subprocess.list2cmdline([os.environ["COMSPEC"], "/d", "/c", "exit /b 0"])
    process, thread, pid, _ = _winapi.CreateProcess(None, command, None, None, False, subprocess.CREATE_NO_WINDOW | 0x4, None, None, subprocess.STARTUPINFO())
    child = {"process": process, "thread": thread, "pid": pid, "kernel": kernel, "api": _winapi}
    try:
        created, exited, system, user = (ctypes.c_ulonglong() for _ in range(4))
        if not kernel.GetProcessTimes(ctypes.c_void_p(process), ctypes.byref(created), ctypes.byref(exited), ctypes.byref(system), ctypes.byref(user)):
            raise ctypes.WinError(ctypes.get_last_error())
        image = ctypes.create_unicode_buffer(32768)
        size = ctypes.c_ulong(len(image))
        if not kernel.QueryFullProcessImageNameW(ctypes.c_void_p(process), 0, image, ctypes.byref(size)):
            raise ctypes.WinError(ctypes.get_last_error())
        if created.value == 0 or exited.value != 0 or not image.value:
            raise ValueError("Independent suspended descendant lacks an actual live image/birth")
        child.update(creationFileTime=created.value, image=image.value)
        return child
    except BaseException:
        close_immediate(child)
        raise


def close_immediate(child):
    """Release only this created child's two held handles; unfinished fixture children are terminated before release."""
    api = child["api"]
    try:
        if api.GetExitCodeProcess(child["process"]) == 259:
            api.TerminateProcess(child["process"], 126)
            if api.WaitForSingleObject(child["process"], api.INFINITE) != 0:
                raise ValueError("Independent child cleanup did not reach a signalled process")
    finally:
        try:
            api.CloseHandle(child["thread"])
        finally:
            api.CloseHandle(child["process"])


def resume_immediate(child):
    """Resume exactly the independently captured initial suspended thread, without delaying the child's work."""
    if child["kernel"].ResumeThread(ctypes.c_void_p(child["thread"])) != 1:
        raise ValueError("Independent suspended child did not resume its initial thread")


def completed_identity(child):
    """Read exit/time from the same held process handle and preserve its independently observed pre-resume image/birth."""
    api = child["api"]
    if api.WaitForSingleObject(child["process"], api.INFINITE) != 0:
        raise ValueError("Independent child did not reach a signalled process")
    created, exited, system, user = (ctypes.c_ulonglong() for _ in range(4))
    if not child["kernel"].GetProcessTimes(ctypes.c_void_p(child["process"]), ctypes.byref(created), ctypes.byref(exited), ctypes.byref(system), ctypes.byref(user)):
        raise ctypes.WinError(ctypes.get_last_error())
    code = api.GetExitCodeProcess(child["process"])
    if created.value != child["creationFileTime"] or exited.value == 0 or exited.value < created.value or code != 0:
        raise ValueError("Independent immediate descendant lacks an actual closed lifetime")
    return {"pid": child["pid"], "creationFileTime": created.value, "exitFileTime": exited.value, "image": child["image"], "exitCode": code}


def main():
    """Retain the original eight-child batches, with an explicit no-sleep native regression mode."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--child", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--batches", type=int, default=36)
    parser.add_argument("--immediate", action="store_true")
    parser.add_argument("--held", type=int, choices=(0, 64), default=0)
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
    if arguments.held and not arguments.immediate:
        parser.error("Held metadata controls require the immediate descendant mode")
    with ExitStack() as held_release:
        held = []
        for _ in range(arguments.held):
            child = immediate_child()
            held_release.callback(close_immediate, child)
            held.append(child)
        for batch in range(arguments.batches):
            children = []
            with ExitStack() as release:
                for index in range(8):
                    receipt = arguments.output / f"child-{batch * 8 + index}.json"
                    if arguments.immediate:
                        child = immediate_child()
                        release.callback(close_immediate, child)
                        children.append((child, receipt))
                    else:
                        process = subprocess.Popen([sys.executable, "-B", str(Path(__file__).resolve()), "--child", str(receipt)], creationflags=subprocess.CREATE_NO_WINDOW)
                        children.append((process, receipt))
                if arguments.immediate:
                    for child, _ in children:
                        resume_immediate(child)
                for process, receipt in children:
                    if arguments.immediate:
                        records.append(completed_identity(process))
                    else:
                        code = process.wait()
                        if code != 0:
                            raise ValueError("Owned history child failed")
                        record = json.loads(receipt.read_text(encoding="utf-8"))
                        if record["pid"] != process.pid:
                            raise ValueError("Owned child receipt identity changed")
                        records.append({**record, "exitCode": code})
        for child in held:
            resume_immediate(child)
        held_records = [completed_identity(child) for child in held]
    with (arguments.output / "completed.json").open("x", encoding="utf-8") as target:
        json.dump({"concurrentChildren": 8, "immediate": arguments.immediate, "generations": records, "held_generations": held_records}, target, indent=2)


if __name__ == "__main__":
    main()
