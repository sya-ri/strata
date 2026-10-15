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
    [StructLayout(LayoutKind.Sequential)] struct CreationInfo {
        public IntPtr file, process, thread, imageBase;
        public uint debugOffset, debugSize;
        public IntPtr threadLocalBase, startAddress, imageName;
        public ushort unicode;
    }
    [StructLayout(LayoutKind.Sequential)] struct ExceptionRecord {
        public uint code, flags;
        public IntPtr next, address;
        public uint parameterCount;
        public UIntPtr p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14;
    }
    [StructLayout(LayoutKind.Sequential)] struct ExceptionInfo {
        public ExceptionRecord exception;
        public uint firstChance;
    }
    [StructLayout(LayoutKind.Explicit)] struct DebugInfo {
        [FieldOffset(0)] public CreationInfo creation;
        [FieldOffset(0)] public ExceptionInfo exception;
        [FieldOffset(0)] public IntPtr file;
    }
    [StructLayout(LayoutKind.Sequential)] struct DebugEvent {
        public uint kind, pid, threadId;
        public DebugInfo info;
    }
    // A pending claim owns this exact handle and its image; it is not a captured physical generation yet.
    sealed class PendingProcess {
        public uint pid;
        public IntPtr handle;
        public string image;
        public long bytes;
    }
    // The shared bounds include reader/sampler temporaries, pending metadata and published handles.
    sealed class Acquisitions {
        public readonly Queue<PendingProcess> pending = new Queue<PendingProcess>();
        public int handles, peakHandles, metadataInFlight, peakPending;
        public long unpublishedBytes, publishedBytes;
        public long newNotifications, exitNotifications;
        public bool captureFailed;
        public IntPtr anchor, rootThread;
        public bool anchorReserved, anchorCloseAttempted;
        public readonly Dictionary<uint, IntPtr> debugProcesses = new Dictionary<uint, IntPtr>();
        public readonly HashSet<uint> initialBreakpoints = new HashSet<uint>();
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
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool WaitForDebugEventEx(out DebugEvent debugEvent, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool ContinueDebugEvent(uint pid, uint threadId, uint status);
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

    static void ReserveHandle(Acquisitions acquisitions, bool alreadyOwned = false) {
        int count = Interlocked.Increment(ref acquisitions.handles);
        if (256 < count && alreadyOwned == false) {
            Interlocked.Decrement(ref acquisitions.handles);
            throw new InvalidOperationException("Owned live handle inventory exceeds its bound.");
        }
        int previous;
        do {
            previous = Volatile.Read(ref acquisitions.peakHandles);
            if (count <= previous) break;
        } while (Interlocked.CompareExchange(ref acquisitions.peakHandles, count, previous) != previous);
        if (256 < count) throw new InvalidOperationException("Owned live handle inventory exceeds its bound.");
    }

    static void ReleaseReservation(Acquisitions acquisitions) {
        if (Interlocked.Decrement(ref acquisitions.handles) < 0) throw new InvalidOperationException("Owned physical handle released twice.");
    }

    static void CloseTracked(IntPtr handle, Acquisitions acquisitions) {
        Require(CloseHandle(handle));
        ReleaseReservation(acquisitions);
    }

    // The original primary handle has its own reservation and is closed at most once.
    static bool CloseAnchor(Acquisitions acquisitions) {
        if (acquisitions.anchorReserved == false) return true;
        if (acquisitions.anchorCloseAttempted) return false;
        acquisitions.anchorCloseAttempted = true;
        if (acquisitions.anchor != IntPtr.Zero && CloseHandle(acquisitions.anchor) == false) return false;
        acquisitions.anchor = IntPtr.Zero;
        acquisitions.anchorReserved = false;
        ReleaseReservation(acquisitions);
        return true;
    }

    // Called under the gate; retain the maximum reservation so a refusal still reports its original observed bound.
    static void CheckHistoryBound(Acquisitions acquisitions, Dictionary<string, object> result) {
        long bytes = acquisitions.publishedBytes + acquisitions.unpublishedBytes;
        result["memberHistorySerializedByteBound"] = Math.Max((long)result["memberHistorySerializedByteBound"], bytes);
        if (3 * 1024 * 1024 < bytes) throw new InvalidOperationException("Owned process history exceeds the existing terminal report bound.");
    }

    // The sampler alone retires handles; lifetime queries run outside the brief per-generation close/removal lock.
    static void Retire(Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, object gate, Acquisitions acquisitions) {
        List<KeyValuePair<uint, IntPtr>> snapshot;
        lock (gate) snapshot = new List<KeyValuePair<uint, IntPtr>>(handles);
        foreach (var item in snapshot) {
            IntPtr borrowed;
            lock (gate) if (acquisitions.debugProcesses.TryGetValue(item.Key, out borrowed) && borrowed == item.Value) continue;
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
                CloseTracked(item.Value, acquisitions);
                record["exitCode"] = code;
                record["exitFileTime"] = exited;
                record["completed"] = true;
                record["handleClosed"] = true;
                handles.Remove(item.Key);
            }
        }
    }

    // Held handles do not preserve the image-query window during termination; creation events gate this query before execution.
    static string ObserveImage(IntPtr handle) {
        var executable = new StringBuilder(32768);
        uint size = (uint)executable.Capacity;
        return QueryFullProcessImageNameW(handle, 0, executable, ref size) ? executable.ToString() : null;
    }

    // Failure-only observations borrow this private handle and never establish a captured generation or replace the original error.
    static Dictionary<string, object> ObserveAcquisitionFailure(IntPtr job, uint pid, IntPtr handle, string stage, int error) {
        var diagnostic = new Dictionary<string, object> {{"stage", stage}, {"pid", pid}, {"nativeError", error}, {"handleAvailable", handle != IntPtr.Zero}, {"heldHandleQueriesAttempted", false}};
        if (handle == IntPtr.Zero) return diagnostic;
        diagnostic["heldHandleQueriesAttempted"] = true;
        // Exit FILETIME is defined only when this wait already observed termination before GetProcessTimes.
        uint wait = WaitForSingleObject(handle, 0);
        int waitError = wait == uint.MaxValue ? Marshal.GetLastWin32Error() : 0;
        diagnostic["wait"] = new Dictionary<string, object> {{"value", wait}, {"error", waitError}};
        long created, exited, kernel, user;
        bool timesRead = GetProcessTimes(handle, out created, out exited, out kernel, out user);
        int timesError = timesRead ? 0 : Marshal.GetLastWin32Error();
        var times = new Dictionary<string, object> {{"success", timesRead}, {"error", timesError}};
        if (timesRead) {
            times["creationFileTime"] = created;
            if (wait == 0) times["exitFileTime"] = exited;
        }
        diagnostic["times"] = times;
        uint code;
        bool codeRead = GetExitCodeProcess(handle, out code);
        int codeError = codeRead ? 0 : Marshal.GetLastWin32Error();
        var status = new Dictionary<string, object> {{"success", codeRead}, {"error", codeError}};
        if (codeRead) status["reportedCode"] = code;
        diagnostic["exitStatus"] = status;
        bool member;
        bool membershipRead = IsProcessInJob(handle, job, out member);
        int membershipError = membershipRead ? 0 : Marshal.GetLastWin32Error();
        var membership = new Dictionary<string, object> {{"success", membershipRead}, {"error", membershipError}};
        if (membershipRead) membership["inOwnedJob"] = member;
        diagnostic["membership"] = membership;
        return diagnostic;
    }

    // Deferred queries use the same held object; a post-exit query failure never establishes a captured generation.
    static Dictionary<string, object> ObserveHandle(IntPtr job, uint pid, IntPtr handle, bool primary, string image = null) {
        bool member;
        Require(IsProcessInJob(handle, job, out member));
        if (member == false) return null;
        long created, exited, kernel, user;
        if (GetProcessTimes(handle, out created, out exited, out kernel, out user) == false) return null;
        if (image == null) image = ObserveImage(handle);
        if (image == null) return null;
        UIntPtr affinity, system;
        if (GetProcessAffinityMask(handle, out affinity, out system) == false) return null;
        var record = new Dictionary<string, object> {{"pid", pid}, {"creationFileTime", created}, {"image", image}, {"affinityMask", affinity.ToUInt64()}, {"systemAffinityMask", system.ToUInt64()}, {"inOwnedJob", true}, {"completed", false}, {"handleClosed", false}};
        if (primary) record["primaryBeforeResume"] = true;
        return record;
    }

    // Called under the inventory lock; retained transfers ownership before any bound check can throw.
    static void RetainHandle(uint pid, IntPtr handle, Dictionary<string, object> record, ref bool retained, Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, Dictionary<string, object> result, Acquisitions acquisitions, bool reserved = false) {
        if (reserved == false) CheckHistoryBound(acquisitions, result);
        long created = (long)record["creationFileTime"];
        List<Dictionary<string, object>> history;
        if (records.TryGetValue(pid, out history) == false) {
            history = new List<Dictionary<string, object>>();
            records.Add(pid, history);
        }
        foreach (var previous in history) {
            if ((long)previous["creationFileTime"] != created) continue;
            if (record.ContainsKey("creationEventBeforeContinue") && (bool)record["creationEventBeforeContinue"]) {
                if (String.Equals((string)previous["image"], (string)record["image"], StringComparison.OrdinalIgnoreCase) == false
                    || (ulong)previous["affinityMask"] != (ulong)record["affinityMask"] || (ulong)previous["systemAffinityMask"] != (ulong)record["systemAffinityMask"])
                    throw new InvalidOperationException("Creation event changed an already held generation's actual metadata.");
                previous["creationEventBeforeContinue"] = true;
            }
            return; // Notifications and complete member snapshots can identify the same held generation.
        }
        if (handles.ContainsKey(pid)) throw new InvalidOperationException("A retained physical PID generation cannot be replaced.");
        history.Add(record);
        handles.Add(pid, handle);
        retained = true;
        result["maximumRetainedHandles"] = Math.Max((int)result["maximumRetainedHandles"], handles.Count);
        // Six bytes per UTF-16 unit covers JSON escaping; 1024 covers all fixed fields and PID/list indentation.
        if (reserved == false) {
            acquisitions.publishedBytes += 1024 + 6L * ((string)record["image"]).Length;
            CheckHistoryBound(acquisitions, result);
        }
        // A queued claim already reserved this same image and fixed metadata envelope; draining only transfers its charge.
        if (256 < handles.Count) throw new InvalidOperationException("Owned live handle inventory exceeds its bound.");
    }

    // The sampler owns each dequeued claim until publication or closure; duplicate identity requires actual birth.
    static void DrainPending(IntPtr job, Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, Dictionary<string, object> result, object gate, Acquisitions acquisitions, bool beforeContinue = false) {
        while (true) {
            PendingProcess claim;
            lock (gate) {
                if (acquisitions.pending.Count == 0) return;
                claim = acquisitions.pending.Dequeue();
                acquisitions.metadataInFlight++;
            }
            bool retained = false;
            try {
                var observed = ObserveHandle(job, claim.pid, claim.handle, false, claim.image);
                if (observed == null) {
                    int error = Marshal.GetLastWin32Error();
                    lock (gate) {
                        result["notificationMetadataFailures"] = (long)result["notificationMetadataFailures"] + 1;
                        result["lastNotificationMetadataError"] = error;
                    }
                    throw new InvalidOperationException("Acquired owned process metadata could not be observed.");
                }
                lock (gate) {
                    observed["creationEventBeforeContinue"] = beforeContinue;
                    result["notificationMetadataSuccesses"] = (long)result["notificationMetadataSuccesses"] + 1;
                    RetainHandle(claim.pid, claim.handle, observed, ref retained, handles, records, result, acquisitions, true);
                }
            } finally {
                lock (gate) {
                    acquisitions.metadataInFlight--;
                    acquisitions.unpublishedBytes -= claim.bytes;
                    if (retained) acquisitions.publishedBytes += claim.bytes;
                }
                if (retained == false) CloseTracked(claim.handle, acquisitions);
            }
        }
    }

    // The creator thread owns this event; the child cannot execute or exit before ContinueDebugEvent.
    static void AcquireCreatedProcess(IntPtr job, uint pid, IntPtr eventProcess, Dictionary<string, object> capture, object gate, Acquisitions acquisitions) {
        ReserveHandle(acquisitions);
        IntPtr handle = IntPtr.Zero;
        bool duplicated = DuplicateHandle(GetCurrentProcess(), eventProcess, GetCurrentProcess(), out handle, 0, false, 2);
        int duplicateError = duplicated ? 0 : Marshal.GetLastWin32Error();
        if (duplicated == false) handle = IntPtr.Zero;
        bool retained = false;
        try {
            // The creation event holds this generation before its first user-mode instruction.
            string image = handle == IntPtr.Zero ? null : ObserveImage(handle);
            int imageError = image == null && handle != IntPtr.Zero ? Marshal.GetLastWin32Error() : 0;
            Dictionary<string, object> failureEvidence = null;
            bool firstFailure;
            lock (gate) firstFailure = capture.ContainsKey("firstProcessAcquisitionFailure") == false;
            if (firstFailure && (handle == IntPtr.Zero || image == null)) {
                failureEvidence = ObserveAcquisitionFailure(job, pid, handle == IntPtr.Zero ? eventProcess : handle, handle == IntPtr.Zero ? "duplicate" : "image", handle == IntPtr.Zero ? duplicateError : imageError);
            }
            lock (gate) {
                if (failureEvidence != null) capture["firstProcessAcquisitionFailure"] = failureEvidence;
                if (handle == IntPtr.Zero) {
                    capture["notificationDuplicateFailures"] = (long)capture["notificationDuplicateFailures"] + 1;
                    capture["lastNotificationDuplicateError"] = duplicateError;
                    throw new Win32Exception(duplicateError);
                }
                capture["notificationDuplicateSuccesses"] = (long)capture["notificationDuplicateSuccesses"] + 1;
                if (image == null) {
                    capture["notificationImageFailures"] = (long)capture["notificationImageFailures"] + 1;
                    capture["lastNotificationImageError"] = imageError;
                    throw new InvalidOperationException("Owned process image acquisition failed.");
                }
                capture["notificationImageSuccesses"] = (long)capture["notificationImageSuccesses"] + 1;
                var claim = new PendingProcess {pid=pid, handle=handle, image=image, bytes=1024 + 6L * image.Length};
                acquisitions.pending.Enqueue(claim);
                retained = true; // The pending owner must survive a throwing byte-bound check.
                acquisitions.unpublishedBytes += claim.bytes;
                acquisitions.peakPending = Math.Max(acquisitions.peakPending, acquisitions.pending.Count);
                CheckHistoryBound(acquisitions, capture);
            }
        } finally {
            if (retained == false) {
                if (handle == IntPtr.Zero) ReleaseReservation(acquisitions);
                else CloseTracked(handle, acquisitions);
            }
        }
    }

    // Keep one live handle: Windows owns the creation handle until EXIT_PROCESS is continued.
    static void UseCreationLease(uint pid, IntPtr borrowed, Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, Dictionary<string, object> capture, object gate, Acquisitions acquisitions) {
        lock (gate) {
            IntPtr owned;
            if (handles.TryGetValue(pid, out owned) == false || owned == borrowed)
                throw new InvalidOperationException("Creation lease lacks its separately acquired generation.");
            var history = records[pid];
            var record = history[history.Count - 1];
            if (record.ContainsKey("creationEventBeforeContinue") == false || (bool)record["creationEventBeforeContinue"] == false)
                throw new InvalidOperationException("Creation lease lacks its gated metadata witness.");
            CloseTracked(owned, acquisitions);
            handles[pid] = borrowed;
            capture["creationLeaseTransfers"] = (long)capture["creationLeaseTransfers"] + 1;
        }
    }

    // Duplicate the same borrowed object before EXIT continuation closes it; only the owned copy is retired.
    static void DuplicateExitLease(uint pid, Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, Dictionary<string, object> capture, object gate, Acquisitions acquisitions) {
        IntPtr borrowed, retained;
        Dictionary<string, object> record;
        lock (gate) {
            if (acquisitions.debugProcesses.TryGetValue(pid, out borrowed) == false)
                throw new InvalidOperationException("Exit lacks its actual creation handle.");
            if (handles.TryGetValue(pid, out retained) == false || retained != borrowed) return;
            var history = records[pid];
            record = history[history.Count - 1];
        }
        ReserveHandle(acquisitions);
        IntPtr duplicate = IntPtr.Zero;
        bool transferred = false;
        try {
            bool success = DuplicateHandle(GetCurrentProcess(), borrowed, GetCurrentProcess(), out duplicate, 0, false, 2);
            int error = success ? 0 : Marshal.GetLastWin32Error();
            lock (gate) {
                string counter = success ? "exitDuplicateSuccesses" : "exitDuplicateFailures";
                capture[counter] = (long)capture[counter] + 1;
            }
            if (success == false) { duplicate = IntPtr.Zero; throw new Win32Exception(error); }
            long created, exited, kernel, user;
            Require(GetProcessTimes(duplicate, out created, out exited, out kernel, out user));
            if (created != (long)record["creationFileTime"])
                throw new InvalidOperationException("Exit duplicate changed the captured physical generation.");
            lock (gate) {
                if (handles[pid] != borrowed) throw new InvalidOperationException("Exit lease changed during duplication.");
                handles[pid] = duplicate;
                transferred = true;
                record["exitDuplicateBeforeContinue"] = true;
            }
        } finally {
            if (transferred == false) {
                if (duplicate == IntPtr.Zero) ReleaseReservation(acquisitions);
                else CloseTracked(duplicate, acquisitions);
            }
        }
    }

    // Run only on the CreateProcess thread, including cleanup. Job messages remain advisory diagnostics.
    static void CaptureCreations(IntPtr job, Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, Dictionary<string, object> capture, object gate, Acquisitions acquisitions, Stopwatch clock, double seconds, uint waitMilliseconds = 0) {
        while (clock.Elapsed.TotalSeconds < seconds) {
            DebugEvent observed;
            if (WaitForDebugEventEx(out observed, waitMilliseconds) == false) {
                int error = Marshal.GetLastWin32Error();
                if (error == 121) return; // ERROR_SEM_TIMEOUT: no event within the bounded wait.
                throw new Win32Exception(error);
            }
            waitMilliseconds = 0;
            lock (gate) {
                var counts = (Dictionary<string, long>)capture["debugEventCounts"];
                string kind = observed.kind.ToString(System.Globalization.CultureInfo.InvariantCulture);
                long count;
                counts.TryGetValue(kind, out count);
                counts[kind] = count + 1;
            }
            uint continuation = 0x00010002; // DBG_CONTINUE for non-exception events.
            bool exited = observed.kind == 5;
            try {
                if (observed.kind == 3) {
                    lock (gate) capture["processCreationEvents"] = (long)capture["processCreationEvents"] + 1;
                    if (observed.info.creation.process == IntPtr.Zero)
                        throw new InvalidOperationException("Creation event lacks its actual physical process handle.");
                    if (acquisitions.debugProcesses.ContainsKey(observed.pid)) throw new InvalidOperationException("Duplicate live debug process identity.");
                    acquisitions.debugProcesses.Add(observed.pid, observed.info.creation.process);
                    if (observed.info.creation.thread == acquisitions.rootThread) acquisitions.rootThread = IntPtr.Zero;
                    // Windows owns this event handle until EXIT_PROCESS is continued; include it in the same bound.
                    if (observed.info.creation.process == acquisitions.anchor) {
                        // Transfer the already charged alias to Windows; keep a separate anchor for post-EXIT queries.
                        acquisitions.anchor = IntPtr.Zero;
                        acquisitions.anchorReserved = false;
                        ReserveHandle(acquisitions);
                        acquisitions.anchorReserved = true;
                        if (DuplicateHandle(GetCurrentProcess(), observed.info.creation.process, GetCurrentProcess(), out acquisitions.anchor, 0, false, 2) == false) {
                            int error = Marshal.GetLastWin32Error();
                            acquisitions.anchor = IntPtr.Zero;
                            throw new Win32Exception(error);
                        }
                    } else ReserveHandle(acquisitions, true);
                    if (observed.info.creation.thread == IntPtr.Zero)
                        throw new InvalidOperationException("Creation event lacks its actual physical thread handle.");
                    if (acquisitions.captureFailed == false) {
                        AcquireCreatedProcess(job, observed.pid, observed.info.creation.process, capture, gate, acquisitions);
                        DrainPending(job, handles, records, capture, gate, acquisitions, true);
                        UseCreationLease(observed.pid, observed.info.creation.process, handles, records, capture, gate, acquisitions);
                        // Preserve physical snapshots for Job members outside this debugging chain, while the new child is gated.
                        Capture(job, handles, records, capture, clock, seconds, gate, acquisitions, false);
                    } else {
                        // A fatal refusal cannot grow the bounded history during termination; actual Job totals remain unreconciled.
                        lock (gate) capture["creationEventsAfterFailure"] = (long)capture["creationEventsAfterFailure"] + 1;
                    }
                } else if (exited) {
                    DuplicateExitLease(observed.pid, handles, records, capture, gate, acquisitions);
                } else if (observed.kind == 1) {
                    continuation = 0x80010001; // Pass application exceptions to their normal handler.
                    if (observed.info.exception.firstChance == 1 && observed.info.exception.exception.code == 0x80000003
                        && acquisitions.initialBreakpoints.Add(observed.pid)) continuation = 0x00010002;
                } else if (observed.kind < 2 || 9 < observed.kind) {
                    throw new InvalidOperationException("Unknown native debugging event.");
                }
            } catch (Exception failure) {
                acquisitions.captureFailed = true;
                // An unacquired exit copy is fatal; do not leave a map entry that Windows is about to close.
                if (exited) lock (gate) {
                    IntPtr borrowed, retained;
                    if (acquisitions.debugProcesses.TryGetValue(observed.pid, out borrowed)
                        && handles.TryGetValue(observed.pid, out retained) && retained == borrowed) handles.Remove(observed.pid);
                }
                lock (gate) if (capture.ContainsKey("creationCaptureError") == false) capture["creationCaptureError"] = failure.Message;
                throw;
            } finally {
                try {
                    if ((observed.kind == 3 || observed.kind == 6) && observed.info.file != IntPtr.Zero) Require(CloseHandle(observed.info.file));
                } finally {
                    Require(ContinueDebugEvent(observed.pid, observed.threadId, continuation));
                    if (exited) {
                        if (acquisitions.debugProcesses.Remove(observed.pid) == false) throw new InvalidOperationException("Exit lacks its actual creation event.");
                        acquisitions.initialBreakpoints.Remove(observed.pid);
                        ReleaseReservation(acquisitions); // ContinueDebugEvent closes the system's process/thread event handles.
                        Retire(handles, records, gate, acquisitions);
                    }
                }
            }
        }
    }

    // Keep metadata failures fatal while still retiring held generations and reaching actual final Job accounting.
    static bool CleanupCapture(IntPtr job, Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, Dictionary<string, object> capture, Dictionary<string, object> result, object gate, Acquisitions acquisitions, Stopwatch cleanup, int cleanupSeconds) {
        bool closed = true;
        try { CaptureCreations(job, handles, records, capture, gate, acquisitions, cleanup, cleanupSeconds); }
        catch (Exception failure) {
            if (result.ContainsKey("cleanupError") == false) result["cleanupError"] = failure.Message;
            closed = false;
        }
        try { DrainPending(job, handles, records, capture, gate, acquisitions); }
        catch (Exception failure) {
            if (result.ContainsKey("cleanupError") == false) result["cleanupError"] = failure.Message;
            closed = false;
        }
        try { Retire(handles, records, gate, acquisitions); }
        catch (Exception failure) {
            if (result.ContainsKey("cleanupError") == false) result["cleanupError"] = failure.Message;
            closed = false;
        }
        return closed;
    }

    static void Capture(IntPtr job, Dictionary<uint, IntPtr> handles, Dictionary<uint, List<Dictionary<string, object>>> records, Dictionary<string, object> result, Stopwatch clock, double seconds, object gate, Acquisitions acquisitions, bool creationEvents = true) {
        if (creationEvents) CaptureCreations(job, handles, records, result, gate, acquisitions, clock, seconds);
        DrainPending(job, handles, records, result, gate, acquisitions);
        Retire(handles, records, gate, acquisitions);
        DrainPending(job, handles, records, result, gate, acquisitions);
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
                DrainPending(job, handles, records, result, gate, acquisitions);
                Retire(handles, records, gate, acquisitions);
                Thread.Sleep((int)Math.Min(100, Math.Max(0, (seconds - clock.Elapsed.TotalSeconds) * 1000)));
            }
            lock (gate) result["maximumListedMembers"] = Math.Max((int)result["maximumListedMembers"], count);
            for (int index = 0; index < count; index++) {
                DrainPending(job, handles, records, result, gate, acquisitions);
                uint pid = (uint)Marshal.ReadIntPtr(buffer, 8 + index * IntPtr.Size).ToInt64();
                lock (gate) { if (handles.ContainsKey(pid)) continue; }
                ReserveHandle(acquisitions);
                IntPtr handle = OpenProcess(0x1000 | 0x100000, false, pid);
                if (handle == IntPtr.Zero) { ReleaseReservation(acquisitions); continue; } // Final accounting still rejects every missed generation.
                bool retained = false;
                try {
                    var observed = ObserveHandle(job, pid, handle, false);
                    if (observed != null) lock (gate) RetainHandle(pid, handle, observed, ref retained, handles, records, result, acquisitions);
                } finally { if (retained == false) CloseTracked(handle, acquisitions); }
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
        var acquisitions = new Acquisitions();
        var memberHandles = new Dictionary<uint, IntPtr>();
        var members = new Dictionary<uint, List<Dictionary<string, object>>>();
        Stopwatch cleanup = null;
        var serializedMembers = new Dictionary<string, List<Dictionary<string, object>>>();
        result["members"] = serializedMembers;
        var capture = new Dictionary<string, object> {
            {"maximumRetainedHandles", 0}, {"maximumListedMembers", 0}, {"incompleteMemberQueries", 0},
            {"memberHistorySerializedByteBound", 0L}, {"memberHistorySerializedByteLimit", 3 * 1024 * 1024},
            {"notificationOpenFailures", 0L}, {"notificationOpenSuccesses", 0L},
            {"notificationDuplicateFailures", 0L}, {"notificationDuplicateSuccesses", 0L},
            {"notificationImageFailures", 0L}, {"notificationImageSuccesses", 0L},
            {"notificationMetadataFailures", 0L}, {"notificationMetadataSuccesses", 0L},
            {"processCreationCapture", "debug-before-continue-v2"}, {"processCreationEvents", 0L}, {"creationEventsAfterFailure", 0L},
            {"creationLeaseTransfers", 0L}, {"exitDuplicateSuccesses", 0L}, {"exitDuplicateFailures", 0L},
            {"processCreationHandleSource", "debug-event-lease-with-exit-duplicate"},
            {"debugEventCounts", new Dictionary<string, long>()}
        };
        try {
            int eventBytes = Marshal.SizeOf<DebugEvent>();
            long unionOffset = Marshal.OffsetOf<DebugEvent>("info").ToInt64();
            long firstChanceOffset = Marshal.OffsetOf<ExceptionInfo>("firstChance").ToInt64();
            if ((IntPtr.Size == 8 && eventBytes == 176 && unionOffset == 16 && firstChanceOffset == 152) == false
                && (IntPtr.Size == 4 && eventBytes == 96 && unionOffset == 12 && firstChanceOffset == 80) == false)
                throw new InvalidOperationException("Unexpected Win32 debugging event ABI; no native call started.");
            capture["processCreationAbi"] = new Dictionary<string, object> {{"pointerBytes", IntPtr.Size}, {"eventBytes", eventBytes}, {"unionOffset", unionOffset}, {"firstChanceOffset", firstChanceOffset}};
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
                            Interlocked.Increment(ref acquisitions.exitNotifications);
                        }
                        if (message != 6) continue;
                        long rawPid = notifiedPid.ToInt64();
                        if (rawPid <= 0 || uint.MaxValue < rawPid) throw new InvalidOperationException("Invalid Job notification PID.");
                        Interlocked.Increment(ref acquisitions.newNotifications);
                    }
                } catch (Exception failure) { lock (gate) capture["notificationError"] = failure.Message; }
            });
            reader.IsBackground = true;
            result["clockBeforeResume"] = ObserveClock();
            var startup = new Startup {size=(uint)Marshal.SizeOf<Startup>(), flags=0x100, input=GetStdHandle(-10), output=GetStdHandle(-11), error=GetStdHandle(-12)};
            if (program == false) command += " -JobName \"" + name + "\"";
            ReserveHandle(acquisitions);
            acquisitions.anchorReserved = true;
            Require(CreateProcessW(executable, command, IntPtr.Zero, IntPtr.Zero, true, 0x1 | 0x4 | 0x08000000, IntPtr.Zero, directory, ref startup, out process));
            acquisitions.anchor = process.process;
            acquisitions.rootThread = process.thread;
            process.process = IntPtr.Zero;
            process.thread = IntPtr.Zero;
            Require(AssignProcessToJobObject(job, acquisitions.anchor));
            assigned = true;
            ReserveHandle(acquisitions);
            IntPtr primary = IntPtr.Zero;
            bool primaryRetained = false;
            try {
                Require(DuplicateHandle(GetCurrentProcess(), acquisitions.anchor, GetCurrentProcess(), out primary, 0, false, 2));
                var observedPrimary = ObserveHandle(job, process.processId, primary, true);
                if (observedPrimary != null) lock (gate) RetainHandle(process.processId, primary, observedPrimary, ref primaryRetained, memberHandles, members, capture, acquisitions);
                if (primaryRetained == false) throw new InvalidOperationException("Suspended primary process could not be captured.");
            } finally {
                if (primaryRetained == false) {
                    if (primary == IntPtr.Zero) ReleaseReservation(acquisitions);
                    else CloseTracked(primary, acquisitions);
                }
            }
            // The known primary is already held; no descendant can start before the reader is ready and ResumeThread runs.
            reader.Start();
            readerStarted = true;
            if (readerReady.Wait(TimeSpan.FromSeconds(cleanupSeconds)) == false) throw new InvalidOperationException("Job notification reader did not start within the owned startup bound.");
            UIntPtr affinity, systemAffinity;
            Require(GetProcessAffinityMask(acquisitions.anchor, out affinity, out systemAffinity));
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
            Require(ResumeThread(acquisitions.rootThread) != uint.MaxValue);
            result["nativeInvoked"] = true;
            result["callerPid"] = process.processId;
            uint wait;
            do {
                Capture(job, memberHandles, members, capture, clock, workSeconds, gate, acquisitions);
                lock (gate) { if (capture.ContainsKey("notificationError")) throw new InvalidOperationException((string)capture["notificationError"]); }
                CaptureCreations(job, memberHandles, members, capture, gate, acquisitions, clock, workSeconds, 100);
                wait = WaitForSingleObject(acquisitions.anchor, 0);
            } while (wait == 0x102 && clock.Elapsed.TotalSeconds < workSeconds);
            if (wait == 0x102) {
                result["timedOut"] = true;
                Require(TerminateJobObject(job, 124));
            } else Require(wait == 0);
            cleanup = Stopwatch.StartNew();
            var accounting = Account(job);
            double killReserve = Math.Min(1, cleanupSeconds / 2.0);
            while (0 < accounting.activeProcesses && cleanup.Elapsed.TotalSeconds + killReserve < cleanupSeconds) {
                Capture(job, memberHandles, members, capture, cleanup, cleanupSeconds - killReserve, gate, acquisitions);
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
            Require(GetExitCodeProcess(acquisitions.anchor, out exitCode));
            result["exitCode"] = exitCode;
            if (0 < accounting.activeProcesses) Require(TerminateJobObject(job, 125));
            DrainPending(job, memberHandles, members, capture, gate, acquisitions);
            Retire(memberHandles, members, gate, acquisitions);
        } catch (Exception failure) {
            result["error"] = failure.Message;
        } finally {
            bool closed = true;
            if (acquisitions.anchor != IntPtr.Zero && assigned == false) closed &= TerminateProcess(acquisitions.anchor, 126);
            if (job != IntPtr.Zero) {
                if (cleanup == null) cleanup = Stopwatch.StartNew();
                try {
                    if ((bool)result["naturalRelease"] == false) closed &= TerminateJobObject(job, 126);
                    while (cleanup.Elapsed.TotalSeconds < cleanupSeconds) {
                        int retained;
                        closed &= CleanupCapture(job, memberHandles, members, capture, result, gate, acquisitions, cleanup, cleanupSeconds);
                        lock (gate) retained = memberHandles.Count;
                        if (Account(job).activeProcesses == 0 && retained == 0 && acquisitions.debugProcesses.Count == 0) break;
                        Thread.Sleep(10);
                    }
                    result["activeProcessesAfterCleanup"] = Account(job).activeProcesses;
                    closed &= CleanupCapture(job, memberHandles, members, capture, result, gate, acquisitions, cleanup, cleanupSeconds);
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
                closed &= CleanupCapture(job, memberHandles, members, capture, result, gate, acquisitions, cleanup, cleanupSeconds);
                closed &= CloseAnchor(acquisitions);
                var finalAccounting = Account(job);
                result["activeProcessesAfterCleanup"] = finalAccounting.activeProcesses;
                if (result.ContainsKey("totalProcesses") == false) result["totalProcesses"] = finalAccounting.totalProcesses;
                lock (gate) {
                    int captured = 0, completed = 0;
                    int beforeContinue = 0;
                    foreach (var generations in members.Values) foreach (var member in generations) {
                        captured++;
                        if ((bool)member["completed"] && (bool)member["handleClosed"]) completed++;
                        if (member.ContainsKey("creationEventBeforeContinue") && (bool)member["creationEventBeforeContinue"]) beforeContinue++;
                    }
                    bool complete = closed && finalAccounting.activeProcesses == 0 && captured == finalAccounting.totalProcesses && completed == captured && capture.ContainsKey("notificationError") == false
                        && result.ContainsKey("error") == false && result.ContainsKey("cleanupError") == false
                        && acquisitions.pending.Count == 0 && acquisitions.metadataInFlight == 0 && acquisitions.handles == 0 && acquisitions.unpublishedBytes == 0
                        && acquisitions.debugProcesses.Count == 0 && beforeContinue == (long)capture["processCreationEvents"]
                        && (long)capture["processCreationEvents"] <= finalAccounting.totalProcesses
                        && (long)capture["creationEventsAfterFailure"] == 0 && capture.ContainsKey("creationCaptureError") == false
                        && (long)capture["creationLeaseTransfers"] == (long)capture["processCreationEvents"]
                        && (long)capture["exitDuplicateSuccesses"] == (long)capture["processCreationEvents"] && (long)capture["exitDuplicateFailures"] == 0
                        && (long)capture["notificationMetadataFailures"] == 0;
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
            List<PendingProcess> remainingPending;
            lock (gate) {
                remainingHandles = new List<IntPtr>(memberHandles.Values);
                memberHandles.Clear();
                remainingPending = new List<PendingProcess>(acquisitions.pending);
                acquisitions.pending.Clear();
                foreach (var item in members) {
                    var snapshot = new List<Dictionary<string, object>>();
                    foreach (var member in item.Value) snapshot.Add(new Dictionary<string, object>(member));
                    serializedMembers[item.Key.ToString(System.Globalization.CultureInfo.InvariantCulture)] = snapshot;
                }
                foreach (var item in capture) result[item.Key] = item.Value;
            }
            foreach (var handle in remainingHandles) {
                if (acquisitions.debugProcesses.ContainsValue(handle)) { closed = false; continue; }
                if (CloseHandle(handle)) ReleaseReservation(acquisitions);
                else closed = false;
            }
            foreach (var claim in remainingPending) {
                if (CloseHandle(claim.handle)) {
                    ReleaseReservation(acquisitions);
                    acquisitions.unpublishedBytes -= claim.bytes;
                } else closed = false;
            }
            closed &= CloseAnchor(acquisitions);
            result["newProcessNotifications"] = acquisitions.newNotifications;
            result["exitProcessNotifications"] = acquisitions.exitNotifications;
            result["processAcquisition"] = new Dictionary<string, object> {
                {"pending", acquisitions.pending.Count}, {"metadataInFlight", acquisitions.metadataInFlight}, {"debugProcessHandles", acquisitions.debugProcesses.Count},
                {"physicalHandles", acquisitions.handles}, {"maximumPhysicalHandles", acquisitions.peakHandles},
                {"maximumPending", acquisitions.peakPending}, {"unpublishedByteBound", acquisitions.unpublishedBytes},
                {"publishedByteBound", acquisitions.publishedBytes}
            };
            closed &= acquisitions.debugProcesses.Count == 0 && acquisitions.handles == 0 && acquisitions.metadataInFlight == 0 && acquisitions.pending.Count == 0 && acquisitions.unpublishedBytes == 0;
            readerReady.Dispose();
            if (acquisitions.rootThread != IntPtr.Zero) closed &= CloseHandle(acquisitions.rootThread);

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
