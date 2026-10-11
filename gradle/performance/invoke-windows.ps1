param(
    [Parameter(Mandatory)][string]$Request,
    [string]$Report,
    [string]$JobName,
    [switch]$Child
)
$ErrorActionPreference = 'Stop'
$invocation = Get-Content -LiteralPath $Request -Raw | ConvertFrom-Json

# Assign the suspended caller before it can launch Gradle; all descendants inherit this job.
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;

/// <summary>Bounds one Gradle process tree without changing affinity or measuring application work.</summary>
public static class StrataPerformanceProcess {
    [StructLayout(LayoutKind.Sequential)] struct Memory {
        public uint length, load;
        public ulong physical, availablePhysical, pageFile, availablePageFile, virtualBytes, availableVirtual, availableExtended;
    }
    [StructLayout(LayoutKind.Sequential)] struct Limits {
        public long processTime, jobTime;
        public uint flags;
        public UIntPtr minimumWorkingSet, maximumWorkingSet;
        public uint activeProcessLimit;
        public UIntPtr affinity;
        public uint priority, scheduling;
    }
    [StructLayout(LayoutKind.Sequential)] struct ExtendedLimits {
        public Limits basic;
        public ulong readOperations, writeOperations, otherOperations, readBytes, writeBytes, otherBytes;
        public UIntPtr processMemory, jobMemory, peakProcessMemory, peakJobMemory;
    }
    [StructLayout(LayoutKind.Sequential)] struct Accounting {
        public long userTime, kernelTime, periodUserTime, periodKernelTime;
        public uint pageFaults, totalProcesses, activeProcesses, terminatedProcesses;
    }
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct Startup {
        public uint size;
        public string reserved, desktop, title;
        public uint x, y, width, height, charactersX, charactersY, fill, flags;
        public ushort showWindow, reservedBytes;
        public IntPtr reservedPointer, input, output, error;
    }
    [StructLayout(LayoutKind.Sequential)] struct ProcessInfo {
        public IntPtr process, thread;
        public uint processId, threadId;
    }
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GlobalMemoryStatusEx(ref Memory memory);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr CreateJobObjectW(IntPtr attributes, string name);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr OpenJobObjectW(uint access, bool inherit, string name);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool member);
    [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool QueryFullProcessImageNameW(IntPtr process, uint flags, System.Text.StringBuilder name, ref uint size);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetProcessTimes(IntPtr process, out long created, out long exited, out long kernel, out long user);
    [DllImport("kernel32.dll", EntryPoint="QueryInformationJobObject", SetLastError=true)] static extern bool QueryMembers(IntPtr job, int kind, IntPtr data, uint size, IntPtr returned);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job, int kind, ref ExtendedLimits limits, uint size);
    [DllImport("kernel32.dll", EntryPoint="QueryInformationJobObject", SetLastError=true)] static extern bool QueryAccounting(IntPtr job, int kind, out Accounting info, uint size, IntPtr returned);
    [DllImport("kernel32.dll", EntryPoint="QueryInformationJobObject", SetLastError=true)] static extern bool QueryLimits(IntPtr job, int kind, out ExtendedLimits info, uint size, IntPtr returned);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CreateProcessW(string application, string command, IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment, string directory, ref Startup startup, out ProcessInfo process);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetExitCodeProcess(IntPtr process, out uint exitCode);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateProcess(IntPtr process, uint exitCode);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateJobObject(IntPtr job, uint exitCode);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetProcessAffinityMask(IntPtr process, out UIntPtr processMask, out UIntPtr systemMask);
    [DllImport("kernel32.dll")] static extern IntPtr GetStdHandle(int kind);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);

    static void Require(bool success) {
        if (success == false) throw new Win32Exception(Marshal.GetLastWin32Error());
    }
    static Accounting Account(IntPtr job) {
        Accounting accounting;
        Require(QueryAccounting(job, 1, out accounting, (uint)Marshal.SizeOf<Accounting>(), IntPtr.Zero));
        return accounting;
    }

    /// <summary>Rechecks free RAM and actual Job membership immediately before the child launches Java.</summary>
    public static Dictionary<string, object> Admit(string name, ulong minimumFree) {
        IntPtr job = OpenJobObjectW(4, false, name);
        Require(job != IntPtr.Zero);
        try {
            bool member;
            Require(IsProcessInJob(GetCurrentProcess(), job, out member));
            if (member == false) throw new InvalidOperationException("Caller is outside the owning Job.");
            var memory = new Memory {length=(uint)Marshal.SizeOf<Memory>()};
            Require(GlobalMemoryStatusEx(ref memory));
            if (memory.availablePhysical < minimumFree) throw new InvalidOperationException("Insufficient free physical RAM inside the owning Job; no Java call started.");
            return new Dictionary<string, object> {{"jobName", name}, {"pid", Process.GetCurrentProcess().Id}, {"inOwnedJob", member}, {"availablePhysicalBytes", memory.availablePhysical}};
        } finally { Require(CloseHandle(job)); }
    }

    static void Capture(IntPtr job, Dictionary<uint, IntPtr> handles, Dictionary<uint, Dictionary<string, object>> records) {
        const int maximumMembers = 256;
        int bytes = 8 + maximumMembers * IntPtr.Size;
        IntPtr buffer = Marshal.AllocHGlobal(bytes);
        try {
            Require(QueryMembers(job, 3, buffer, (uint)bytes, IntPtr.Zero));
            int assigned = Marshal.ReadInt32(buffer), count = Marshal.ReadInt32(buffer, 4);
            if (assigned != count || count < 0 || maximumMembers < count) throw new InvalidOperationException("Owned member inventory exceeds its bound.");
            for (int index = 0; index < count; index++) {
                uint pid = (uint)Marshal.ReadIntPtr(buffer, 8 + index * IntPtr.Size).ToInt64();
                if (handles.ContainsKey(pid)) continue;
                IntPtr handle = OpenProcess(0x1000 | 0x100000, false, pid);
                if (handle == IntPtr.Zero) continue; // Short non-measured helpers may already have exited; forks require a captured generation.
                bool retained = false;
                try {
                    bool member;
                    Require(IsProcessInJob(handle, job, out member));
                    if (member == false) continue;
                    long created, exited, kernel, user;
                    if (GetProcessTimes(handle, out created, out exited, out kernel, out user) == false) continue;
                    var executable = new System.Text.StringBuilder(32768);
                    uint size = (uint)executable.Capacity;
                    if (QueryFullProcessImageNameW(handle, 0, executable, ref size) == false) continue;
                    UIntPtr affinity, system;
                    if (GetProcessAffinityMask(handle, out affinity, out system) == false) continue;
                    records[pid] = new Dictionary<string, object> {{"pid", pid}, {"creationFileTime", created}, {"image", executable.ToString()}, {"affinityMask", affinity.ToUInt64()}, {"systemAffinityMask", system.ToUInt64()}, {"inOwnedJob", true}};
                    handles[pid] = handle;
                    retained = true;
                } finally { if (retained == false) Require(CloseHandle(handle)); }
            }
        } finally { Marshal.FreeHGlobal(buffer); }
        foreach (var item in handles) {
            if (WaitForSingleObject(item.Value, 0) != 0x102) continue;
            bool member;
            UIntPtr affinity, system;
            Require(IsProcessInJob(item.Value, job, out member));
            Require(GetProcessAffinityMask(item.Value, out affinity, out system));
            if (member == false || (ulong)records[item.Key]["affinityMask"] != affinity.ToUInt64() || (ulong)records[item.Key]["systemAffinityMask"] != system.ToUInt64())
                throw new InvalidOperationException("Owned process membership or unchanged affinity changed.");
        }
    }

    /// <summary>Returns the actual exit, admission, job accounting and release; failure never admits a next call.</summary>
    public static Dictionary<string, object> Run(string executable, string command, string directory, ulong minimumFree, ulong memoryLimit, int workSeconds, int cleanupSeconds) {
        var result = new Dictionary<string, object> {
            {"startedUtc", DateTime.UtcNow.ToString("O")}, {"nativeInvoked", false}, {"exitCode", null},
            {"timedOut", false}, {"naturalRelease", false}, {"handlesClosed", false}, {"minimumFreePhysicalBytes", minimumFree},
            {"jobCommittedLimitBytes", memoryLimit}, {"workSeconds", workSeconds}, {"cleanupSeconds", cleanupSeconds}
        };
        IntPtr job = IntPtr.Zero;
        ProcessInfo process = new ProcessInfo();
        bool assigned = false;
        var memberHandles = new Dictionary<uint, IntPtr>();
        var members = new Dictionary<uint, Dictionary<string, object>>();
        Stopwatch cleanup = null;
        var serializedMembers = new Dictionary<string, Dictionary<string, object>>();
        result["members"] = serializedMembers;
        try {
            var memory = new Memory {length=(uint)Marshal.SizeOf<Memory>()};
            Require(GlobalMemoryStatusEx(ref memory));
            result["availablePhysicalBytes"] = memory.availablePhysical;
            if (memory.availablePhysical < minimumFree) throw new InvalidOperationException("Insufficient available physical RAM; no native call started.");
            string name = "StrataPairedJVM-" + Guid.NewGuid().ToString("N");
            result["jobName"] = name;
            job = CreateJobObjectW(IntPtr.Zero, name);
            Require(job != IntPtr.Zero);
            var limits = new ExtendedLimits {basic=new Limits {flags=0x200 | 0x2000}, jobMemory=(UIntPtr)memoryLimit};
            Require(SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf<ExtendedLimits>()));
            var startup = new Startup {size=(uint)Marshal.SizeOf<Startup>(), flags=0x100, input=GetStdHandle(-10), output=GetStdHandle(-11), error=GetStdHandle(-12)};
            command += " -JobName \"" + name + "\"";
            Require(CreateProcessW(executable, command, IntPtr.Zero, IntPtr.Zero, true, 0x4 | 0x08000000, IntPtr.Zero, directory, ref startup, out process));
            Require(AssignProcessToJobObject(job, process.process));
            assigned = true;
            UIntPtr affinity, systemAffinity;
            Require(GetProcessAffinityMask(process.process, out affinity, out systemAffinity));
            result["inheritedAffinityMask"] = affinity.ToUInt64();
            result["systemAffinityMask"] = systemAffinity.ToUInt64();
            result["affinityConfigured"] = false;
            var clock = Stopwatch.StartNew();
            Require(ResumeThread(process.thread) != uint.MaxValue);
            result["nativeInvoked"] = true;
            result["callerPid"] = process.processId;
            uint wait;
            do {
                Capture(job, memberHandles, members);
                wait = WaitForSingleObject(process.process, 100);
            } while (wait == 0x102 && clock.Elapsed.TotalSeconds < workSeconds);
            if (wait == 0x102) {
                result["timedOut"] = true;
                Require(TerminateJobObject(job, 124));
            } else Require(wait == 0);
            cleanup = Stopwatch.StartNew();
            var accounting = Account(job);
            double killReserve = Math.Min(1, cleanupSeconds / 2.0);
            while (0 < accounting.activeProcesses && cleanup.Elapsed.TotalSeconds + killReserve < cleanupSeconds) {
                Capture(job, memberHandles, members);
                System.Threading.Thread.Sleep(100);
                accounting = Account(job);
            }
            result["activeProcessesAtRelease"] = accounting.activeProcesses;
            result["totalProcesses"] = accounting.totalProcesses;
            result["jobUserCpu100ns"] = accounting.userTime;
            result["jobKernelCpu100ns"] = accounting.kernelTime;
            result["naturalRelease"] = accounting.activeProcesses == 0 && (bool)result["timedOut"] == false;
            ExtendedLimits observed;
            Require(QueryLimits(job, 9, out observed, (uint)Marshal.SizeOf<ExtendedLimits>(), IntPtr.Zero));
            result["peakJobCommittedBytes"] = observed.peakJobMemory.ToUInt64();
            result["observedJobLimitBytes"] = observed.jobMemory.ToUInt64();
            result["observedJobFlags"] = observed.basic.flags;
            if (observed.jobMemory.ToUInt64() != memoryLimit || (observed.basic.flags & 0x2000) == 0 || (observed.basic.flags & (0x800 | 0x1000)) != 0)
                throw new InvalidOperationException("Actual owning Job limits or no-breakaway contract changed.");
            uint exitCode;
            Require(GetExitCodeProcess(process.process, out exitCode));
            result["exitCode"] = exitCode;
            if (0 < accounting.activeProcesses) Require(TerminateJobObject(job, 125));
            foreach (var item in memberHandles) {
                uint code;
                Require(GetExitCodeProcess(item.Value, out code));
                members[item.Key]["exitCode"] = code;
                members[item.Key]["completed"] = WaitForSingleObject(item.Value, 0) == 0;
            }
        } catch (Exception failure) {
            result["error"] = failure.Message;
        } finally {
            bool closed = true;
            if (process.process != IntPtr.Zero && assigned == false) closed &= TerminateProcess(process.process, 126);
            if (job != IntPtr.Zero) {
                if (cleanup == null) cleanup = Stopwatch.StartNew();
                try {
                    if ((bool)result["naturalRelease"] == false) closed &= TerminateJobObject(job, 126);
                    while (0 < Account(job).activeProcesses && cleanup.Elapsed.TotalSeconds < cleanupSeconds) System.Threading.Thread.Sleep(10);
                    result["activeProcessesAfterCleanup"] = Account(job).activeProcesses;
                    result["cleanupElapsedSeconds"] = cleanup.Elapsed.TotalSeconds;
                    closed &= (uint)result["activeProcessesAfterCleanup"] == 0 && cleanup.Elapsed.TotalSeconds < cleanupSeconds;
                } catch (Exception failure) {
                    result["cleanupError"] = failure.Message;
                    closed = false;
                }
                closed &= CloseHandle(job);
            }
            foreach (var handle in memberHandles.Values) closed &= CloseHandle(handle);
            foreach (var item in members) serializedMembers[item.Key.ToString(System.Globalization.CultureInfo.InvariantCulture)] = item.Value;
            if (process.thread != IntPtr.Zero) closed &= CloseHandle(process.thread);
            if (process.process != IntPtr.Zero) closed &= CloseHandle(process.process);
            result["handlesClosed"] = closed;
            result["endedUtc"] = DateTime.UtcNow.ToString("O");
        }
        return result;
    }
}
'@
if ($Child) {
    foreach ($entry in $invocation.environment.PSObject.Properties) {
        [Environment]::SetEnvironmentVariable($entry.Name, [string]$entry.Value, 'Process')
    }
    $admissionPath = Join-Path (Split-Path -Parent $Request) 'child-admission.json'
    if (Test-Path -LiteralPath $admissionPath) { throw 'Child admission output already exists.' }
    [StrataPerformanceProcess]::Admit($JobName, $invocation.minimumFreePhysicalBytes) | ConvertTo-Json | Set-Content -LiteralPath $admissionPath -Encoding utf8
    Set-Location -LiteralPath $invocation.workspace
    $arguments = @($invocation.arguments)
    & (Join-Path $invocation.workspace 'gradlew.bat') @arguments
    exit $LASTEXITCODE
}
if ([string]::IsNullOrWhiteSpace($Report) -or (Test-Path -LiteralPath $Report)) { throw 'Supply a fresh terminal report path.' }
$pwsh = (Get-Process -Id $PID).Path
$command = '"{0}" -NoLogo -NoProfile -NonInteractive -File "{1}" -Child -Request "{2}"' -f $pwsh, $PSCommandPath, (Resolve-Path -LiteralPath $Request).Path
$result = [StrataPerformanceProcess]::Run($pwsh, $command, $invocation.workspace, $invocation.minimumFreePhysicalBytes, $invocation.jobCommittedBytes, $invocation.workSeconds, $invocation.cleanupSeconds)
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Report -Encoding utf8
if ($result.exitCode -ne 0 -or $result.naturalRelease -ne $true -or $result.handlesClosed -ne $true -or $result.ContainsKey('error')) {
    throw "Bounded Gradle invocation failed; inspect $Report"
}
