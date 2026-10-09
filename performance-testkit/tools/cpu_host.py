"""Actual host observations, exclusive local CPU leases and process-tree cost sources.

An OS lease fences this driver. The separately reviewed host qualification must
also establish exclusive occupancy by other users, services and CI jobs.
"""
from contextlib import contextmanager
import ctypes
import hashlib
import json
import os
from pathlib import Path
import platform
import argparse
import subprocess
import tempfile
import time
import uuid
import sys


def file_hash(path):
    """Hash a local launcher or OS source without exposing its contents."""
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def observe(java=None, process_id=None):
    """Read physical identity, CPU model, affinity, OS, power policy and actual occupancy sources."""
    if os.name == "nt":
        command = ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                   "$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); $product=Get-CimInstance Win32_ComputerSystemProduct; "
                   "$cpu=Get-CimInstance Win32_Processor; $os=Get-CimInstance Win32_OperatingSystem; "
                   "$system=Get-CimInstance Win32_ComputerSystem; "
                   "@{physical_id=$product.UUID;cpu_model=@($cpu.Name);os=$os.Caption;os_version=$os.Version;boot_id=$os.LastBootUpTime.ToUniversalTime().ToString('O');"
                   "hypervisor=$system.HypervisorPresent;power=@(powercfg /getactivescheme);"
                   "heavy_processes=@(Get-Process java,javaw -ErrorAction SilentlyContinue | Select-Object Id,ProcessName);"
                   "process_parents=@(Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId)} | ConvertTo-Json -Depth 4 -Compress"]
        raw = subprocess.check_output(command, text=True, encoding="utf-8", errors="strict", timeout=30)
        values = json.loads(raw)
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.GetCurrentProcess.restype = ctypes.c_void_p
        kernel.OpenProcess.restype = ctypes.c_void_p
        kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_bool, ctypes.c_ulong]
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        process_mask, system_mask = ctypes.c_size_t(), ctypes.c_size_t()
        process = kernel.OpenProcess(0x1000, False, process_id) if process_id is not None else kernel.GetCurrentProcess()
        if not process:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            if not kernel.GetProcessAffinityMask(ctypes.c_void_p(process), ctypes.byref(process_mask), ctypes.byref(system_mask)):
                raise ctypes.WinError(ctypes.get_last_error())
        finally:
            if process_id is not None:
                kernel.CloseHandle(ctypes.c_void_p(process))
        values["affinity"] = process_mask.value
        occupancy = values.pop("heavy_processes")
        parents = {entry["ProcessId"]: entry["ParentProcessId"] for entry in values.pop("process_parents")}
        if process_id is not None:
            allowed = set()
            current = process_id
            while current and current not in allowed:
                allowed.add(current)
                current = parents.get(current)
            occupancy = [entry for entry in occupancy if entry["Id"] not in allowed]
        sources = {"command": command, "stdout": raw, "process_affinity_mask": process_mask.value, "system_affinity_mask": system_mask.value}
    else:
        physical = Path("/sys/class/dmi/id/product_uuid").read_text().strip()
        cpu = Path("/proc/cpuinfo").read_text()
        models = sorted({line.split(":", 1)[1].strip() for line in cpu.splitlines() if line.startswith("model name")})
        power_paths = sorted(Path("/sys/devices/system/cpu").glob("cpu[0-9]*/cpufreq/scaling_governor"))
        if not power_paths:
            raise ValueError("No authoritative CPU power-policy source; executor qualification is incomplete")
        power = {str(path): path.read_text().strip() for path in power_paths}
        occupancy = []
        allowed = set()
        current = process_id
        while current and current not in allowed:
            allowed.add(current)
            status = Path("/proc") / str(current) / "status"
            parent = next(line.split(":", 1)[1].strip() for line in status.read_text().splitlines() if line.startswith("PPid:"))
            current = int(parent)
        for entry in Path("/proc").glob("[0-9]*/comm"):
            try:
                if entry.read_text().strip() in ("java", "javaw") and int(entry.parent.name) not in allowed:
                    occupancy.append(str(entry.parent))
            except FileNotFoundError:
                continue
        values = {"physical_id": physical, "cpu_model": models, "os": platform.system(), "os_version": platform.release(),
                  "affinity": sorted(os.sched_getaffinity(process_id or 0)), "power": power, "boot_id": Path("/proc/sys/kernel/random/boot_id").read_text().strip()}
        sources = {"product_uuid": physical, "cpuinfo": cpu, "governors": power, "heavy_processes": occupancy}
    if not values["physical_id"] or not values["cpu_model"] or occupancy:
        raise ValueError("Missing physical CPU identity or another JVM occupies the executor")
    result = {"contract": "strata-cpu-host-v1", "conditions": values, "sources": sources}
    sources["probe_sha256"] = file_hash(Path(__file__))
    values["python_sha256"] = file_hash(sys.executable)
    if java is not None:
        launcher = Path(java).resolve(strict=True)
        release = launcher.parent.parent / "release"
        values["java_sha256"] = file_hash(launcher)
        values["java_release_sha256"] = file_hash(release)
        result["java"] = str(launcher)
        sources["java_release"] = release.read_text()
    return result


@contextmanager
def exclusive_lease():
    """Fence every opted-in CPU worker on this actual host for its complete paired shard."""
    host = observe()["conditions"]["physical_id"]
    key = hashlib.sha256(host.encode()).hexdigest()
    path = Path(tempfile.gettempdir()) / ("strata-cpu-lease-" + key + ".lock")
    with path.open("a+b") as lock:
        lock.seek(0)
        if os.name == "nt":
            import msvcrt
            if path.stat().st_size == 0:
                lock.write(b"0")
                lock.flush()
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield str(uuid.uuid4())
        finally:
            if os.name == "nt":
                lock.seek(0)
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def run_process(command, cwd, log):
    """Measure outer process-tree CPU and elapsed time; JMH owns all application samples."""
    started = time.monotonic()
    if os.name == "nt":
        usage = windows_process(command, cwd, log)
    else:
        from cpu_posix import posix_process
        usage = posix_process(command, cwd, log)
    usage["elapsed_seconds"] = time.monotonic() - started
    return usage


def windows_process(command, cwd, log):
    """Account all inherited children in a Windows Job, assigned before the root process resumes."""
    from ctypes import wintypes
    import _winapi
    import msvcrt

    class Accounting(ctypes.Structure):
        """JOBOBJECT_BASIC_ACCOUNTING_INFORMATION from the Windows SDK."""
        _fields_ = [(name, ctypes.c_longlong) for name in ("user", "kernel", "period_user", "period_kernel")] + [
            (name, wintypes.DWORD) for name in ("page_faults", "total_processes", "active_processes", "terminated_processes")]

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateJobObjectW.restype = wintypes.HANDLE
    kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
    kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
    kernel.QueryInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD, ctypes.c_void_p]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
    kernel.ResumeThread.argtypes = [wintypes.HANDLE]
    kernel.ResumeThread.restype = wintypes.DWORD
    job = kernel.CreateJobObjectW(None, None)
    if not job:
        raise ctypes.WinError(ctypes.get_last_error())
    process_handle = thread_handle = None
    try:
        # CREATE_SUSPENDED prevents any child from escaping accounting before Job assignment.
        with open(os.devnull, "rb") as stdin:
            startup = subprocess.STARTUPINFO()
            startup.dwFlags |= subprocess.STARTF_USESTDHANDLES
            startup.hStdInput = msvcrt.get_osfhandle(stdin.fileno())
            startup.hStdOutput = startup.hStdError = msvcrt.get_osfhandle(log.fileno())
            startup.lpAttributeList = {"handle_list": [startup.hStdInput, startup.hStdOutput]}
            for handle in startup.lpAttributeList["handle_list"]:
                os.set_handle_inheritable(handle, True)
            try:
                process_handle, thread_handle, _, _ = _winapi.CreateProcess(
                    None, subprocess.list2cmdline(command), None, None, True, 0x00000004, None, str(cwd), startup)
            finally:
                for handle in startup.lpAttributeList["handle_list"]:
                    os.set_handle_inheritable(handle, False)
        if not kernel.AssignProcessToJobObject(job, wintypes.HANDLE(process_handle)):
            raise ctypes.WinError(ctypes.get_last_error())
        if kernel.ResumeThread(wintypes.HANDLE(thread_handle)) == 0xFFFFFFFF:
            raise ctypes.WinError(ctypes.get_last_error())
        _winapi.WaitForSingleObject(process_handle, _winapi.INFINITE)
        exit_code = _winapi.GetExitCodeProcess(process_handle)
        accounting = Accounting()
        if not kernel.QueryInformationJobObject(job, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None):
            raise ctypes.WinError(ctypes.get_last_error())
        if accounting.active_processes:
            raise ValueError("Collector left live children outside a complete invocation")
        return {"exit_code": exit_code, "cpu_seconds": (accounting.user + accounting.kernel) / 10_000_000,
                "source": "QueryInformationJobObject(JobObjectBasicAccountingInformation)",
                "user_100ns": accounting.user, "kernel_100ns": accounting.kernel, "total_processes": accounting.total_processes}
    finally:
        # Failure owns the whole child tree, including descendants surviving the root process.
        kernel.TerminateJobObject(job, 1)
        if process_handle is not None:
            if _winapi.WaitForSingleObject(process_handle, 0) == _winapi.WAIT_TIMEOUT:
                _winapi.TerminateProcess(process_handle, 1)
                _winapi.WaitForSingleObject(process_handle, _winapi.INFINITE)
            _winapi.CloseHandle(process_handle)
        if thread_handle is not None:
            _winapi.CloseHandle(thread_handle)
        kernel.CloseHandle(job)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--pid", type=int)
    args = parser.parse_args()
    print(json.dumps(observe(args.java, args.pid), indent=2))
