"""Isolated Linux descendant ownership and wait4 process-tree CPU accounting.

The helper becomes a child subreaper before starting the command. Orphaned
descendants, including children that create another session, remain its owned
children. Cleanup signals only verified direct children through PID handles.
"""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time


def posix_process(command, cwd, log):
    """Reap only the dedicated helper, whose kernel usage includes its reaped descendants."""
    if sys.platform != "linux" or not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
        raise ValueError("Owned POSIX CPU accounting requires Linux subreaper, wait4 and PID handles")
    with tempfile.TemporaryDirectory(prefix="strata-cpu-owned-") as temporary:
        request, report = (Path(temporary) / name for name in ("request.json", "report.json"))
        request.write_text(json.dumps({"command": command, "cwd": str(cwd), "controller": os.getpid()}), encoding="utf-8")
        helper = subprocess.Popen([sys.executable, "-B", str(Path(__file__).resolve()), str(request), str(report)],
                                  stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            _, status, usage = os.wait4(helper.pid, 0)
            helper.returncode = os.waitstatus_to_exitcode(status)
        except BaseException:
            helper.terminate()
            _, status, _ = os.wait4(helper.pid, 0)
            helper.returncode = os.waitstatus_to_exitcode(status)
            raise
        result = json.loads(report.read_text(encoding="utf-8"))
        result.update(cpu_seconds=usage.ru_utime + usage.ru_stime, user_seconds=usage.ru_utime,
                      kernel_seconds=usage.ru_stime, accounting_helper_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                      source="Linux PR_SET_CHILD_SUBREAPER and wait4 dedicated owned tree")
        if helper.returncode != 0 or not result["complete"]:
            result["exit_code"] = helper.returncode or 1
        return result


def direct_children():
    """Read the kernel's current direct children of this isolated single-thread helper."""
    path = Path("/proc") / str(os.getpid()) / "task" / str(os.getpid()) / "children"
    return {int(value) for value in path.read_text().split()}


def cleanup_children():
    """Kill and reap only kernel-owned children, repeatedly adopting their surviving descendants."""
    deadline = time.monotonic() + 5
    reaped = {}
    while True:
        for child in direct_children():
            try:
                handle = os.pidfd_open(child)
            except ProcessLookupError:
                continue
            try:
                # Recheck ownership after opening the PID handle; PID reuse cannot target an unrelated process.
                if child in direct_children():
                    signal.pidfd_send_signal(handle, signal.SIGKILL)
            except ProcessLookupError:
                pass
            finally:
                os.close(handle)
        try:
            while True:
                child, status, _ = os.wait4(-1, os.WNOHANG)
                if child == 0:
                    break
                reaped[child] = os.waitstatus_to_exitcode(status)
        except ChildProcessError:
            return reaped
        if deadline <= time.monotonic():
            raise RuntimeError("Owned descendant cleanup did not complete; executor must remain quarantined")
        time.sleep(0.01)


def interrupted(number, frame):
    """Turn controller death or explicit cancellation into owned-tree cleanup."""
    raise KeyboardInterrupt("Owned process controller stopped")


def supervise(request):
    """Establish ownership before exec, reject orphaned/live children and retain terminal failures."""
    result = {"complete": False, "exit_code": 1}
    process = None
    try:
        signal.signal(signal.SIGTERM, interrupted)
        libc = ctypes.CDLL(None, use_errno=True)
        libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong]
        # Values are the Linux UAPI PR_SET_CHILD_SUBREAPER and PR_SET_PDEATHSIG operations.
        if libc.prctl(36, 1, 0, 0, 0) != 0 or libc.prctl(1, signal.SIGTERM, 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), "Could not establish Linux descendant ownership")
        if os.getppid() != request["controller"]:
            raise ValueError("Owned process controller changed before command start")
        process = subprocess.Popen(request["command"], cwd=request["cwd"], stdin=subprocess.DEVNULL, start_new_session=True)
        result["root_process_id"] = process.pid
        child, status, _ = os.wait4(-1, 0)
        if child != process.pid:
            raise ValueError("Collector orphaned a descendant before its root completed")
        process.returncode = os.waitstatus_to_exitcode(status)
        result["root_exit_code"] = result["exit_code"] = process.returncode
        if direct_children():
            raise ValueError("Collector left live or unreaped descendants outside a complete invocation")
        result["complete"] = True
    except BaseException as failure:
        result.update(complete=False, exit_code=1, failure=type(failure).__name__ + ": " + str(failure))
    finally:
        try:
            reaped = cleanup_children()
            if process is not None and process.pid in reaped:
                process.returncode = reaped[process.pid]
            result["cleanup_complete"] = True
        except BaseException as failure:
            result.update(complete=False, exit_code=1, cleanup_complete=False,
                          cleanup_failure=type(failure).__name__ + ": " + str(failure))
    return result


if __name__ == "__main__":
    source, destination = map(Path, sys.argv[1:])
    value = supervise(json.loads(source.read_text(encoding="utf-8")))
    destination.write_text(json.dumps(value), encoding="utf-8")
    raise SystemExit(0 if value["complete"] else 1)
