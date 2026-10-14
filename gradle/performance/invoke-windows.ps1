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
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

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
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool DuplicateHandle(IntPtr sourceProcess, IntPtr source, IntPtr targetProcess, out IntPtr duplicate, uint access, bool inherit, uint options);
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

    // Only signalled owned handles retire; history keeps every immutable PID/birth identity.
    static void Retire(Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records) {
        foreach (var item in new List<KeyValuePair<uint, IntPtr>>(handles)) {
            uint wait = WaitForSingleObject(item.Value, 0);
            if (wait == 0x102) continue;
            Require(wait == 0);
            long created, exited, kernel, user;
            uint code;
            Require(GetProcessTimes(item.Value, out created, out exited, out kernel, out user));
            Require(GetExitCodeProcess(item.Value, out code));
            var history = records[item.Key];
            var record = history[history.Count - 1];
            if ((long)record["creationFileTime"] != created || exited == 0)
                throw new InvalidOperationException("Completed owned process generation changed.");
            record["exitCode"] = code;
            record["exitFileTime"] = exited;
            record["completed"] = true;
            Require(CloseHandle(item.Value));
            record["handleClosed"] = true;
            handles.Remove(item.Key);
        }
    }

    // The caller owns an unretained handle; a successful capture transfers it to Retire.
    static bool CaptureHandle(IntPtr job, uint pid, IntPtr handle, bool primary, Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, Dictionary<string, object> result) {
        bool member;
        Require(IsProcessInJob(handle, job, out member));
        if (member == false) return false;
        long created, exited, kernel, user;
        if (GetProcessTimes(handle, out created, out exited, out kernel, out user) == false) return false;
        var executable = new StringBuilder(32768);
        uint size = (uint)executable.Capacity;
        if (QueryFullProcessImageNameW(handle, 0, executable, ref size) == false) return false;
        UIntPtr affinity, system;
        if (GetProcessAffinityMask(handle, out affinity, out system) == false) return false;
        List<Dictionary<string, object>> history;
        if (records.TryGetValue(pid, out history) == false) {
            history = new List<Dictionary<string, object>>();
            records.Add(pid, history);
        }
        foreach (var previous in history) {
            if ((long)previous["creationFileTime"] != created) continue;
            if (previous.ContainsKey("primaryBeforeResume") && (bool)previous["completed"]) return false;
            throw new InvalidOperationException("Owned process generation was captured twice.");
        }
        var record = new Dictionary<string, object> {{"pid", pid}, {"creationFileTime", created}, {"image", executable.ToString()}, {"affinityMask", affinity.ToUInt64()}, {"systemAffinityMask", system.ToUInt64()}, {"inOwnedJob", true}, {"completed", false}, {"handleClosed", false}};
        if (primary) record["primaryBeforeResume"] = true;
        history.Add(record);
        handles.Add(pid, handle);
        result["maximumRetainedHandles"] = Math.Max((int)result["maximumRetainedHandles"], handles.Count);
        // Six bytes per UTF-16 unit covers JSON escaping; 1024 covers all fixed fields and PID/list indentation.
        long historyBytes = (long)result["memberHistorySerializedByteBound"] + 1024 + 6L * executable.Length;
        result["memberHistorySerializedByteBound"] = historyBytes;
        if (3 * 1024 * 1024 < historyBytes) throw new InvalidOperationException("Owned process history exceeds the existing terminal report bound.");
        if (256 < handles.Count) throw new InvalidOperationException("Owned live handle inventory exceeds its bound.");
        return true;
    }

    static void Capture(IntPtr job, Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, Dictionary<string, object> result, Stopwatch clock, double seconds) {
        Retire(handles, records);
        const int maximumMembers = 256;
        int bytes = 8 + maximumMembers * IntPtr.Size;
        IntPtr buffer = Marshal.AllocHGlobal(bytes);
        try {
            int assigned, count;
            while (true) {
                Marshal.WriteInt32(buffer, 0, 0);
                Marshal.WriteInt32(buffer, 4, 0);
                bool complete = QueryMembers(job, 3, buffer, (uint)bytes, IntPtr.Zero);
                int error = complete ? 0 : Marshal.GetLastWin32Error();
                assigned = Marshal.ReadInt32(buffer);
                count = Marshal.ReadInt32(buffer, 4);
                var query = new Dictionary<string, object> {{"complete", complete}, {"assigned", assigned}, {"returned", count}, {"capacity", maximumMembers}, {"error", error}};
                result["lastMemberQuery"] = query;
                if (complete == false) throw new Win32Exception(error);
                if (count < 0 || assigned < count || maximumMembers < assigned) throw new InvalidOperationException("Owned member inventory exceeds its bound.");
                if (assigned == count) break;
                // Exits can change the list while it is queried; only a complete snapshot is admitted.
                if (result.ContainsKey("firstIncompleteMemberQuery") == false) result["firstIncompleteMemberQuery"] = query;
                result["lastIncompleteMemberQuery"] = query;
                result["incompleteMemberQueries"] = (int)result["incompleteMemberQueries"] + 1;
                if (seconds <= clock.Elapsed.TotalSeconds) throw new InvalidOperationException("Owned member inventory remained incomplete at the phase deadline.");
                Retire(handles, records);
                Thread.Sleep((int)Math.Min(100, Math.Max(0, (seconds - clock.Elapsed.TotalSeconds) * 1000)));
            }
            result["maximumListedMembers"] = Math.Max((int)result["maximumListedMembers"], count);
            for (int index = 0; index < count; index++) {
                uint pid = (uint)Marshal.ReadIntPtr(buffer, 8 + index * IntPtr.Size).ToInt64();
                if (handles.ContainsKey(pid)) continue;
                IntPtr handle = OpenProcess(0x1000 | 0x100000, false, pid);
                if (handle == IntPtr.Zero) continue; // Short non-measured helpers may already have exited; forks require a captured generation.
                bool retained = false;
                try {
                    retained = CaptureHandle(job, pid, handle, false, handles, records, result);
                } finally { if (retained == false && handles.ContainsKey(pid) == false) Require(CloseHandle(handle)); }
            }
        } finally { Marshal.FreeHGlobal(buffer); }
        foreach (var item in handles) {
            if (WaitForSingleObject(item.Value, 0) != 0x102) continue;
            bool member;
            UIntPtr affinity, system;
            Require(IsProcessInJob(item.Value, job, out member));
            Require(GetProcessAffinityMask(item.Value, out affinity, out system));
            var history = records[item.Key];
            var record = history[history.Count - 1];
            if (member == false || (ulong)record["affinityMask"] != affinity.ToUInt64() || (ulong)record["systemAffinityMask"] != system.ToUInt64())
                throw new InvalidOperationException("Owned process membership or unchanged affinity changed.");
        }
    }

    /// <summary>Quotes one literal Windows argument without shell expansion, including empty values and trailing backslashes.</summary>
    public static string QuoteArgument(string value) {
        if (value == null || 0 <= value.IndexOf('\0')) throw new ArgumentException("Invalid native argument.");
        var quoted = new StringBuilder("\"");
        int slashes = 0;
        foreach (char character in value) {
            if (character == '\\') { slashes++; continue; }
            quoted.Append('\\', character == '"' ? 2 * slashes + 1 : slashes);
            quoted.Append(character);
            slashes = 0;
        }
        return quoted.Append('\\', 2 * slashes).Append('"').ToString();
    }

    static string FileHash(string path) {
        using (var input = File.OpenRead(path))
        using (var sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(input)).Replace("-", "").ToLowerInvariant();
    }

    /// <summary>Launches a pinned program directly, with its primary generation recorded while its thread remains suspended.</summary>
    public static Dictionary<string, object> RunProgram(string executable, string[] arguments, string expectedSha, string directory, ulong minimumFree, ulong memoryLimit, int workSeconds, int cleanupSeconds) {
        string command = QuoteArgument(executable);
        foreach (string argument in arguments) command += " " + QuoteArgument(argument);
        if (32767 <= command.Length) throw new ArgumentException("Native command exceeds the Windows command-line bound.");
        return Run(executable, command, directory, minimumFree, memoryLimit, workSeconds, cleanupSeconds, true, expectedSha);
    }

    /// <summary>Returns the actual exit, admission, job accounting and release; failure never admits a next call.</summary>
    public static Dictionary<string, object> Run(string executable, string command, string directory, ulong minimumFree, ulong memoryLimit, int workSeconds, int cleanupSeconds) {
        return Run(executable, command, directory, minimumFree, memoryLimit, workSeconds, cleanupSeconds, false, null);
    }

    static Dictionary<string, object> Run(string executable, string command, string directory, ulong minimumFree, ulong memoryLimit, int workSeconds, int cleanupSeconds, bool program, string expectedSha) {
        var result = new Dictionary<string, object> {
            {"startedUtc", DateTime.UtcNow.ToString("O")}, {"nativeInvoked", false}, {"exitCode", null},
            {"timedOut", false}, {"naturalRelease", false}, {"handlesClosed", false}, {"minimumFreePhysicalBytes", minimumFree},
            {"jobCommittedLimitBytes", memoryLimit}, {"workSeconds", workSeconds}, {"cleanupSeconds", cleanupSeconds}
        };
        IntPtr job = IntPtr.Zero;
        ProcessInfo process = new ProcessInfo();
        bool assigned = false;
        var memberHandles = new Dictionary<uint, IntPtr>();
        var members = new Dictionary<uint, List<Dictionary<string, object>>>();
        Stopwatch cleanup = null;
        var serializedMembers = new Dictionary<string, List<Dictionary<string, object>>>();
        result["members"] = serializedMembers;
        result["maximumRetainedHandles"] = 0;
        result["maximumListedMembers"] = 0;
        result["incompleteMemberQueries"] = 0;
        result["memberHistorySerializedByteBound"] = 0L;
        result["memberHistorySerializedByteLimit"] = 3 * 1024 * 1024;
        try {
            if (program && FileHash(executable) != expectedSha) throw new InvalidOperationException("The owned Node executable differs from its actual pin.");
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
            if (program == false) command += " -JobName \"" + name + "\"";
            Require(CreateProcessW(executable, command, IntPtr.Zero, IntPtr.Zero, true, 0x4 | 0x08000000, IntPtr.Zero, directory, ref startup, out process));
            Require(AssignProcessToJobObject(job, process.process));
            assigned = true;
            UIntPtr affinity, systemAffinity;
            Require(GetProcessAffinityMask(process.process, out affinity, out systemAffinity));
            result["inheritedAffinityMask"] = affinity.ToUInt64();
            result["systemAffinityMask"] = systemAffinity.ToUInt64();
            result["affinityConfigured"] = false;
            if (program) {
                IntPtr primary;
                Require(DuplicateHandle(GetCurrentProcess(), process.process, GetCurrentProcess(), out primary, 0, false, 2));
                bool retained = false;
                try {
                    retained = CaptureHandle(job, process.processId, primary, true, memberHandles, members, result);
                    if (retained == false) throw new InvalidOperationException("Suspended primary process could not be captured.");
                } finally { if (retained == false && memberHandles.ContainsKey(process.processId) == false) Require(CloseHandle(primary)); }
                var actual = members[process.processId][0];
                if (String.Equals(Path.GetFullPath((string)actual["image"]), Path.GetFullPath(executable), StringComparison.OrdinalIgnoreCase) == false || FileHash((string)actual["image"]) != expectedSha)
                    throw new InvalidOperationException("Suspended primary image differs from its actual pin.");
                Require(GlobalMemoryStatusEx(ref memory));
                if (memory.availablePhysical < minimumFree) throw new InvalidOperationException("Insufficient free physical RAM inside the owning Job; no program resumed.");
                result["primaryAdmission"] = new Dictionary<string, object> {{"jobName", name}, {"pid", process.processId}, {"creationFileTime", actual["creationFileTime"]}, {"image", actual["image"]}, {"inOwnedJob", true}, {"availablePhysicalBytes", memory.availablePhysical}, {"beforeResume", true}};
            }
            var clock = Stopwatch.StartNew();
            Require(ResumeThread(process.thread) != uint.MaxValue);
            result["nativeInvoked"] = true;
            result["callerPid"] = process.processId;
            uint wait;
            do {
                Capture(job, memberHandles, members, result, clock, workSeconds);
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
                Capture(job, memberHandles, members, result, cleanup, cleanupSeconds - killReserve);
                Thread.Sleep(100);
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
            Retire(memberHandles, members);
        } catch (Exception failure) {
            result["error"] = failure.Message;
        } finally {
            bool closed = true;
            if (process.process != IntPtr.Zero && assigned == false) closed &= TerminateProcess(process.process, 126);
            if (job != IntPtr.Zero) {
                if (cleanup == null) cleanup = Stopwatch.StartNew();
                try {
                    if ((bool)result["naturalRelease"] == false) closed &= TerminateJobObject(job, 126);
                    while ((0 < Account(job).activeProcesses || 0 < memberHandles.Count) && cleanup.Elapsed.TotalSeconds < cleanupSeconds) {
                        Retire(memberHandles, members);
                        if (0 < Account(job).activeProcesses || 0 < memberHandles.Count) Thread.Sleep(10);
                    }
                    result["activeProcessesAfterCleanup"] = Account(job).activeProcesses;
                    Retire(memberHandles, members);
                    result["cleanupElapsedSeconds"] = cleanup.Elapsed.TotalSeconds;
                    closed &= (uint)result["activeProcessesAfterCleanup"] == 0 && memberHandles.Count == 0 && cleanup.Elapsed.TotalSeconds < cleanupSeconds;
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
    if ($null -ne $invocation.program) { throw 'A pinned program requires direct primary ownership.' }
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
if ($null -ne $invocation.program) {
    $program = $invocation.program
    if ($program.kind -ne 'node' -or -not [System.IO.Path]::IsPathFullyQualified($program.path) -or
        [System.IO.Path]::GetFileName($program.path) -ne 'node.exe' -or $program.sha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'Supply one full-path pinned Node executable.'
    }
    $admissionPath = Join-Path (Split-Path -Parent $Request) 'child-admission.json'
    if (Test-Path -LiteralPath $admissionPath) { throw 'Primary admission output already exists.' }
    foreach ($entry in $invocation.environment.PSObject.Properties) {
        [Environment]::SetEnvironmentVariable($entry.Name, [string]$entry.Value, 'Process')
    }
    $result = [StrataPerformanceProcess]::RunProgram($program.path, [string[]]@($invocation.arguments), $program.sha256, $invocation.workspace, $invocation.minimumFreePhysicalBytes, $invocation.jobCommittedBytes, $invocation.workSeconds, $invocation.cleanupSeconds)
    if ($result.ContainsKey('primaryAdmission')) {
        $result.primaryAdmission | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $admissionPath -Encoding utf8
    }
} else {
    $pwsh = (Get-Process -Id $PID).Path
    $command = '"{0}" -NoLogo -NoProfile -NonInteractive -File "{1}" -Child -Request "{2}"' -f $pwsh, $PSCommandPath, (Resolve-Path -LiteralPath $Request).Path
    $result = [StrataPerformanceProcess]::Run($pwsh, $command, $invocation.workspace, $invocation.minimumFreePhysicalBytes, $invocation.jobCommittedBytes, $invocation.workSeconds, $invocation.cleanupSeconds)
}
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Report -Encoding utf8
if ($result.exitCode -ne 0 -or $result.naturalRelease -ne $true -or $result.handlesClosed -ne $true -or $result.ContainsKey('error')) {
    throw "Bounded invocation failed; inspect $Report"
}
