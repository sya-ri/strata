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
    [StructLayout(LayoutKind.Sequential)] struct CompletionPort {
        public IntPtr key, port;
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
    [DllImport("kernel32.dll", EntryPoint="SetInformationJobObject", SetLastError=true)] static extern bool AssociatePort(IntPtr job, int kind, ref CompletionPort port, uint size);
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr CreateIoCompletionPort(IntPtr file, IntPtr existing, UIntPtr key, uint threads);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetQueuedCompletionStatus(IntPtr port, out uint message, out UIntPtr key, out IntPtr pid, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool PostQueuedCompletionStatus(IntPtr port, uint message, UIntPtr key, IntPtr pid);
    [DllImport("kernel32.dll")] static extern void GetSystemTimeAsFileTime(out long time);
    [DllImport("kernel32.dll")] static extern void GetSystemTimePreciseAsFileTime(out long time);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetSystemTimeAdjustment(out uint adjustment, out uint increment, out bool disabled);
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

    // Preserve coarse and precise UTC observations; neither is substituted for kernel process birth.
    static Dictionary<string, object> ObserveClock() {
        long before, coarse, after;
        uint adjustment, increment;
        bool disabled;
        GetSystemTimePreciseAsFileTime(out before);
        GetSystemTimeAsFileTime(out coarse);
        GetSystemTimePreciseAsFileTime(out after);
        Require(GetSystemTimeAdjustment(out adjustment, out increment, out disabled));
        return new Dictionary<string, object> {{"preciseBeforeFileTime", before}, {"coarseFileTime", coarse}, {"preciseAfterFileTime", after}, {"adjustment100ns", adjustment}, {"increment100ns", increment}, {"adjustmentDisabled", disabled}};
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

    // The sampler alone retires handles; lifetime queries run outside the brief per-generation close/removal lock.
    static void Retire(Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, object gate) {
        List<KeyValuePair<uint, IntPtr>> snapshot;
        lock (gate) snapshot = new List<KeyValuePair<uint, IntPtr>>(handles);
        foreach (var item in snapshot) {
            uint wait = WaitForSingleObject(item.Value, 0);
            if (wait == 0x102) continue;
            Require(wait == 0);
            long created, exited, kernel, user;
            uint code;
            Require(GetProcessTimes(item.Value, out created, out exited, out kernel, out user));
            Require(GetExitCodeProcess(item.Value, out code));
            Dictionary<string, object> record;
            lock (gate) {
                var history = records[item.Key];
                record = history[history.Count - 1];
                if (handles[item.Key] != item.Value || (long)record["creationFileTime"] != created || exited == 0)
                    throw new InvalidOperationException("Completed owned process generation changed.");
                // A reused PID cannot publish between releasing its physical identity and removing the old map entry.
                Require(CloseHandle(item.Value));
                record["exitCode"] = code;
                record["exitFileTime"] = exited;
                record["completed"] = true;
                record["handleClosed"] = true;
                handles.Remove(item.Key);
            }
        }
    }

    // Observe through the caller's physical handle before a shared record lock can delay a short-lived descendant.
    static Dictionary<string, object> ObserveHandle(IntPtr job, uint pid, IntPtr handle, bool primary) {
        bool member;
        Require(IsProcessInJob(handle, job, out member));
        if (member == false) return null;
        long created, exited, kernel, user;
        if (GetProcessTimes(handle, out created, out exited, out kernel, out user) == false) return null;
        var executable = new StringBuilder(32768);
        uint size = (uint)executable.Capacity;
        if (QueryFullProcessImageNameW(handle, 0, executable, ref size) == false) return null;
        UIntPtr affinity, system;
        if (GetProcessAffinityMask(handle, out affinity, out system) == false) return null;
        var record = new Dictionary<string, object> {{"pid", pid}, {"creationFileTime", created}, {"image", executable.ToString()}, {"affinityMask", affinity.ToUInt64()}, {"systemAffinityMask", system.ToUInt64()}, {"inOwnedJob", true}, {"completed", false}, {"handleClosed", false}};
        if (primary) record["primaryBeforeResume"] = true;
        return record;
    }

    // Called under the inventory lock; retained transfers ownership before any bound check can throw.
    static void RetainHandle(uint pid, IntPtr handle, Dictionary<string, object> record, ref bool retained, Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, Dictionary<string, object> result) {
        long created = (long)record["creationFileTime"];
        List<Dictionary<string, object>> history;
        if (records.TryGetValue(pid, out history) == false) {
            history = new List<Dictionary<string, object>>();
            records.Add(pid, history);
        }
        foreach (var previous in history) {
            if ((long)previous["creationFileTime"] != created) continue;
            return; // Notifications and complete member snapshots can identify the same held generation.
        }
        if (handles.ContainsKey(pid)) throw new InvalidOperationException("A retained physical PID generation cannot be replaced.");
        history.Add(record);
        handles.Add(pid, handle);
        retained = true;
        result["maximumRetainedHandles"] = Math.Max((int)result["maximumRetainedHandles"], handles.Count);
        // Six bytes per UTF-16 unit covers JSON escaping; 1024 covers all fixed fields and PID/list indentation.
        long historyBytes = (long)result["memberHistorySerializedByteBound"] + 1024 + 6L * ((string)record["image"]).Length;
        result["memberHistorySerializedByteBound"] = historyBytes;
        if (3 * 1024 * 1024 < historyBytes) throw new InvalidOperationException("Owned process history exceeds the existing terminal report bound.");
        if (256 < handles.Count) throw new InvalidOperationException("Owned live handle inventory exceeds its bound.");
    }

    static void Capture(IntPtr job, Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, Dictionary<string, object> result, Stopwatch clock, double seconds, object gate) {
        Retire(handles, records, gate);
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
                lock (gate) result["lastMemberQuery"] = query;
                if (complete == false) throw new Win32Exception(error);
                if (count < 0 || assigned < count || maximumMembers < assigned) throw new InvalidOperationException("Owned member inventory exceeds its bound.");
                if (assigned == count) break;
                // Exits can change the list while it is queried; only a complete snapshot is admitted.
                lock (gate) {
                    if (result.ContainsKey("firstIncompleteMemberQuery") == false) result["firstIncompleteMemberQuery"] = query;
                    result["lastIncompleteMemberQuery"] = query;
                    result["incompleteMemberQueries"] = (int)result["incompleteMemberQueries"] + 1;
                }
                if (seconds <= clock.Elapsed.TotalSeconds) throw new InvalidOperationException("Owned member inventory remained incomplete at the phase deadline.");
                Retire(handles, records, gate);
                Thread.Sleep((int)Math.Min(100, Math.Max(0, (seconds - clock.Elapsed.TotalSeconds) * 1000)));
            }
            lock (gate) result["maximumListedMembers"] = Math.Max((int)result["maximumListedMembers"], count);
            for (int index = 0; index < count; index++) {
                uint pid = (uint)Marshal.ReadIntPtr(buffer, 8 + index * IntPtr.Size).ToInt64();
                lock (gate) { if (handles.ContainsKey(pid)) continue; }
                IntPtr handle = OpenProcess(0x1000 | 0x100000, false, pid);
                if (handle == IntPtr.Zero) continue; // Final Job accounting rejects any generation missed by both capture paths.
                bool retained = false;
                try {
                    var observed = ObserveHandle(job, pid, handle, false);
                    if (observed != null) lock (gate) RetainHandle(pid, handle, observed, ref retained, handles, records, result);
                } finally { if (retained == false) Require(CloseHandle(handle)); }
            }
        } finally { Marshal.FreeHGlobal(buffer); }
        List<KeyValuePair<uint, IntPtr>> snapshot;
        lock (gate) snapshot = new List<KeyValuePair<uint, IntPtr>>(handles);
        foreach (var item in snapshot) {
            if (WaitForSingleObject(item.Value, 0) != 0x102) continue;
            bool member;
            UIntPtr affinity, system;
            Require(IsProcessInJob(item.Value, job, out member));
            Require(GetProcessAffinityMask(item.Value, out affinity, out system));
            lock (gate) {
                var history = records[item.Key];
                var record = history[history.Count - 1];
                if (member == false || (ulong)record["affinityMask"] != affinity.ToUInt64() || (ulong)record["systemAffinityMask"] != system.ToUInt64())
                    throw new InvalidOperationException("Owned process membership or unchanged affinity changed.");
            }
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
        IntPtr port = IntPtr.Zero;
        ProcessInfo process = new ProcessInfo();
        bool assigned = false;
        Thread reader = null;
        bool readerStarted = false;
        var readerReady = new ManualResetEventSlim(false);
        var gate = new object();
        var memberHandles = new Dictionary<uint, IntPtr>();
        var members = new Dictionary<uint, List<Dictionary<string, object>>>();
        Stopwatch cleanup = null;
        var serializedMembers = new Dictionary<string, List<Dictionary<string, object>>>();
        result["members"] = serializedMembers;
        var capture = new Dictionary<string, object> {
            {"maximumRetainedHandles", 0}, {"maximumListedMembers", 0}, {"incompleteMemberQueries", 0},
            {"memberHistorySerializedByteBound", 0L}, {"memberHistorySerializedByteLimit", 3 * 1024 * 1024},
            {"newProcessNotifications", 0L}, {"exitProcessNotifications", 0L}, {"notificationOpenFailures", 0L}, {"notificationMetadataFailures", 0L}
        };
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
            // Associate while the Job is empty. Notifications improve capture latency; accounting, not delivery, proves completeness.
            port = CreateIoCompletionPort(new IntPtr(-1), IntPtr.Zero, UIntPtr.Zero, 1);
            Require(port != IntPtr.Zero);
            var association = new CompletionPort {key=job, port=port};
            Require(AssociatePort(job, 7, ref association, (uint)Marshal.SizeOf<CompletionPort>()));
            reader = new Thread(() => {
                try {
                    readerReady.Set();
                    while (true) {
                        uint message;
                        UIntPtr key;
                        IntPtr notifiedPid;
                        Require(GetQueuedCompletionStatus(port, out message, out key, out notifiedPid, uint.MaxValue));
                        if (message == uint.MaxValue && key == UIntPtr.Zero && notifiedPid == IntPtr.Zero) return;
                        if (key.ToUInt64() != unchecked((ulong)job.ToInt64())) throw new InvalidOperationException("Foreign Job completion notification.");
                        if (message == 7 || message == 8) {
                            lock (gate) capture["exitProcessNotifications"] = (long)capture["exitProcessNotifications"] + 1;
                        }
                        if (message != 6) continue;
                        long rawPid = notifiedPid.ToInt64();
                        if (rawPid <= 0 || uint.MaxValue < rawPid) throw new InvalidOperationException("Invalid Job notification PID.");
                        uint pid = (uint)rawPid;
                        IntPtr handle = OpenProcess(0x1000 | 0x100000, false, pid);
                        int openError = handle == IntPtr.Zero ? Marshal.GetLastWin32Error() : 0;
                        bool retained = false;
                        try {
                            var observed = handle == IntPtr.Zero ? null : ObserveHandle(job, pid, handle, false);
                            lock (gate) {
                                capture["newProcessNotifications"] = (long)capture["newProcessNotifications"] + 1;
                                if (handle == IntPtr.Zero) {
                                    capture["notificationOpenFailures"] = (long)capture["notificationOpenFailures"] + 1;
                                    capture["lastNotificationOpenError"] = openError;
                                    continue;
                                }
                                if (observed == null) {
                                    capture["notificationMetadataFailures"] = (long)capture["notificationMetadataFailures"] + 1;
                                    continue;
                                }
                                RetainHandle(pid, handle, observed, ref retained, memberHandles, members, capture);
                            }
                        } finally { if (handle != IntPtr.Zero && retained == false) Require(CloseHandle(handle)); }
                    }
                } catch (Exception failure) { lock (gate) capture["notificationError"] = failure.Message; }
            });
            reader.IsBackground = true;
            result["clockBeforeResume"] = ObserveClock();
            var startup = new Startup {size=(uint)Marshal.SizeOf<Startup>(), flags=0x100, input=GetStdHandle(-10), output=GetStdHandle(-11), error=GetStdHandle(-12)};
            if (program == false) command += " -JobName \"" + name + "\"";
            Require(CreateProcessW(executable, command, IntPtr.Zero, IntPtr.Zero, true, 0x4 | 0x08000000, IntPtr.Zero, directory, ref startup, out process));
            Require(AssignProcessToJobObject(job, process.process));
            assigned = true;
            IntPtr primary;
            Require(DuplicateHandle(GetCurrentProcess(), process.process, GetCurrentProcess(), out primary, 0, false, 2));
            bool primaryRetained = false;
            try {
                var observedPrimary = ObserveHandle(job, process.processId, primary, true);
                if (observedPrimary != null) lock (gate) RetainHandle(process.processId, primary, observedPrimary, ref primaryRetained, memberHandles, members, capture);
                if (primaryRetained == false) throw new InvalidOperationException("Suspended primary process could not be captured.");
            } finally { if (primaryRetained == false) Require(CloseHandle(primary)); }
            // The known primary is already held; no descendant can start before the reader is ready and ResumeThread runs.
            reader.Start();
            readerStarted = true;
            if (readerReady.Wait(TimeSpan.FromSeconds(cleanupSeconds)) == false) throw new InvalidOperationException("Job notification reader did not start within the owned startup bound.");
            UIntPtr affinity, systemAffinity;
            Require(GetProcessAffinityMask(process.process, out affinity, out systemAffinity));
            result["inheritedAffinityMask"] = affinity.ToUInt64();
            result["systemAffinityMask"] = systemAffinity.ToUInt64();
            result["affinityConfigured"] = false;
            if (program) {
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
                Capture(job, memberHandles, members, capture, clock, workSeconds, gate);
                lock (gate) { if (capture.ContainsKey("notificationError")) throw new InvalidOperationException((string)capture["notificationError"]); }
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
                Capture(job, memberHandles, members, capture, cleanup, cleanupSeconds - killReserve, gate);
                lock (gate) { if (capture.ContainsKey("notificationError")) throw new InvalidOperationException((string)capture["notificationError"]); }
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
            Retire(memberHandles, members, gate);
        } catch (Exception failure) {
            result["error"] = failure.Message;
        } finally {
            bool closed = true;
            if (process.process != IntPtr.Zero && assigned == false) closed &= TerminateProcess(process.process, 126);
            if (job != IntPtr.Zero) {
                if (cleanup == null) cleanup = Stopwatch.StartNew();
                try {
                    if ((bool)result["naturalRelease"] == false) closed &= TerminateJobObject(job, 126);
                    while (cleanup.Elapsed.TotalSeconds < cleanupSeconds) {
                        int retained;
                        Retire(memberHandles, members, gate);
                        lock (gate) retained = memberHandles.Count;
                        if (Account(job).activeProcesses == 0 && retained == 0) break;
                        Thread.Sleep(10);
                    }
                    result["activeProcessesAfterCleanup"] = Account(job).activeProcesses;
                    Retire(memberHandles, members, gate);
                    closed &= (uint)result["activeProcessesAfterCleanup"] == 0;
                } catch (Exception failure) {
                    result["cleanupError"] = failure.Message;
                    closed = false;
                }
                try {
                if (readerStarted) {
                    // Wake after the Job has closed its process tree; queued notifications preceding this sentinel are drained first.
                    closed &= PostQueuedCompletionStatus(port, uint.MaxValue, UIntPtr.Zero, IntPtr.Zero);
                    int remaining = (int)Math.Max(0, (cleanupSeconds - cleanup.Elapsed.TotalSeconds) * 1000);
                    bool joined = reader.Join(remaining);
                    result["notificationReaderJoined"] = joined;
                    closed &= joined;
                }
                Retire(memberHandles, members, gate);
                var finalAccounting = Account(job);
                lock (gate) {
                    int captured = 0, completed = 0;
                    foreach (var generations in members.Values) foreach (var member in generations) {
                        captured++;
                        if ((bool)member["completed"] && (bool)member["handleClosed"]) completed++;
                    }
                    bool complete = closed && finalAccounting.activeProcesses == 0 && captured == finalAccounting.totalProcesses && completed == captured && capture.ContainsKey("notificationError") == false;
                    result["generationAccounting"] = new Dictionary<string, object> {{"schema", "strata-owned-generation-accounting-v1"}, {"totalProcesses", finalAccounting.totalProcesses}, {"capturedGenerations", captured}, {"closedGenerations", completed}, {"complete", complete}};
                    if ((bool)result["naturalRelease"] && complete == false && result.ContainsKey("error") == false)
                        result["error"] = "Owned process generations do not reconcile with actual Job accounting.";
                    closed &= memberHandles.Count == 0;
                }
                result["clockAfterCleanup"] = ObserveClock();
                result["cleanupElapsedSeconds"] = cleanup.Elapsed.TotalSeconds;
                closed &= cleanup.Elapsed.TotalSeconds < cleanupSeconds;
                } catch (Exception failure) {
                    if (result.ContainsKey("cleanupError") == false) result["cleanupError"] = failure.Message;
                    closed = false;
                }
                if (port != IntPtr.Zero) { closed &= CloseHandle(port); port = IntPtr.Zero; }
                closed &= CloseHandle(job);
            }
            List<IntPtr> remainingHandles;
            lock (gate) {
                remainingHandles = new List<IntPtr>(memberHandles.Values);
                memberHandles.Clear();
                foreach (var item in members) {
                    var snapshot = new List<Dictionary<string, object>>();
                    foreach (var member in item.Value) snapshot.Add(new Dictionary<string, object>(member));
                    serializedMembers[item.Key.ToString(System.Globalization.CultureInfo.InvariantCulture)] = snapshot;
                }
                foreach (var item in capture) result[item.Key] = item.Value;
            }
            foreach (var handle in remainingHandles) closed &= CloseHandle(handle);
            readerReady.Dispose();
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
