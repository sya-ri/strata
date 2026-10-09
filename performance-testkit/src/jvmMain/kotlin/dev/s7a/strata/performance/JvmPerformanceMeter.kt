package dev.s7a.strata.performance

import com.google.gson.JsonObject
import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory

/**
 * Owner-thread JDK timing, allocation, heap-boundary, and process-GC collector.
 * Setup, assertions, and evidence serialization belong outside sample boundaries.
 * Unsupported metrics remain null and failed samples cannot publish successful evidence.
 * All-thread allocation uses the loaded VM's aggregate counter when available; otherwise it is null.
 * It is distinct from owner-thread allocation and required JMH GC-profiler allocation evidence.
 *
 * @param name stable interval identity.
 * @param capacity exact number of required successful samples.
 */
public class JvmPerformanceMeter(
    private val name: String,
    private val capacity: Int,
) {
    private val thread = ManagementFactory.getThreadMXBean()
    private val allocation = thread as? ThreadMXBean

    // Resolve the newer getter once through its public interface, preserving baseline compilation.
    private val totalAllocationCounter = runCatching { ThreadMXBean::class.java.getMethod("getTotalThreadAllocatedBytes") }.getOrNull()
    private val process = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
    private val memory = ManagementFactory.getMemoryMXBean()
    private val collectors = ManagementFactory.getGarbageCollectorMXBeans()
    private val durations = LongArray(capacity)
    private val cpuSamples = mutableListOf<Long>()
    private val allocationSamples = mutableListOf<Long>()
    private var count = 0
    private var ownerCpu: Long? = 0
    private var ownerAllocation: Long? = 0
    private var processCpu: Long? = 0
    private var totalAllocation: Long? = 0
    private var firstHeap = 0L
    private var lastHeap = 0L
    private var largestHeap = 0L
    private var gcCount: Long? = 0
    private var gcMillis: Long? = 0
    private var started: Long? = null
    private var failed = false
    private val owner = Thread.currentThread()
    private var heapBefore = 0L
    private var gcBefore = 0L
    private var gcMillisBefore = 0L
    private var processBefore = 0L
    private var totalBefore = 0L
    private var allocationBefore = 0L
    private var cpuBefore = 0L

    init {
        require(0 < capacity)
        if (thread.isCurrentThreadCpuTimeSupported && thread.isThreadCpuTimeEnabled.not()) {
            runCatching { thread.isThreadCpuTimeEnabled = true }
        }
        allocation?.let { bean ->
            if (bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled.not()) {
                runCatching { bean.isThreadAllocatedMemoryEnabled = true }
            }
        }
    }

    /**
     * Records one successful operation, propagating failure without counting a failed sample.
     * A caller operation may fail with any Throwable; poison the partial interval and rethrow.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun sample(operation: () -> Unit) {
        begin()
        try {
            operation()
            end()
        } catch (failure: Throwable) {
            started = null
            failed = true
            throw failure
        }
    }

    /**
     * Starts an externally driven sample on the construction owner thread.
     */
    public fun begin() {
        check(Thread.currentThread() === owner && failed.not())
        check(count < capacity)
        check(started == null)
        heapBefore = memory.heapMemoryUsage.used
        gcBefore = collectorTotal(GarbageCollectorMXBean::getCollectionCount)
        gcMillisBefore = collectorTotal(GarbageCollectorMXBean::getCollectionTime)
        processBefore = process?.processCpuTime ?: -1
        totalBefore = allocated(allThreads = true)
        allocationBefore = allocated(allThreads = false)
        cpuBefore = currentCpu()
        started = System.nanoTime()
    }

    /**
     * Completes an externally driven sample; it does not wait for GPU completion.
     */
    public fun end() {
        check(Thread.currentThread() === owner && failed.not())
        val elapsed = System.nanoTime() - checkNotNull(started)
        started = null
        val cpuAfter = currentCpu()
        val allocationAfter = allocated(allThreads = false)
        val totalAfter = allocated(allThreads = true)
        val processAfter = process?.processCpuTime ?: -1
        val heapAfter = memory.heapMemoryUsage.used
        if (cpuBefore in 0..cpuAfter) cpuSamples.add(cpuAfter - cpuBefore)
        if (allocationBefore in 0..allocationAfter) allocationSamples.add(allocationAfter - allocationBefore)
        ownerCpu = addDelta(ownerCpu, cpuBefore, cpuAfter)
        ownerAllocation = addDelta(ownerAllocation, allocationBefore, allocationAfter)
        processCpu = addDelta(processCpu, processBefore, processAfter)
        totalAllocation = addDelta(totalAllocation, totalBefore, totalAfter)
        gcCount = addDelta(gcCount, gcBefore, collectorTotal(GarbageCollectorMXBean::getCollectionCount))
        gcMillis = addDelta(gcMillis, gcMillisBefore, collectorTotal(GarbageCollectorMXBean::getCollectionTime))
        if (count == 0) firstHeap = heapBefore
        lastHeap = heapAfter
        largestHeap = maxOf(largestHeap, heapBefore, heapAfter)
        durations[count] = elapsed
        count += 1
    }

    /**
     * Returns detached JSON only after all required successful samples are complete.
     */
    public fun result(): JsonObject {
        check(Thread.currentThread() === owner && failed.not() && started == null && count == capacity)
        val distribution = PerformanceDistribution.of(durations.toList())
        return JsonObject().apply {
            add("wall_distribution", PerformanceJson.distribution(durations.toList()))
            add("owner_thread_cpu_distribution", if (cpuSamples.size == capacity) PerformanceJson.distribution(cpuSamples) else null)
            add("owner_thread_allocation_distribution", if (allocationSamples.size == capacity) PerformanceJson.distribution(allocationSamples) else null)
            addProperty("name", name)
            addProperty("samples", count)
            addProperty("wall_total_ns", distribution.total)
            addProperty("wall_p50_ns", distribution.p50)
            addProperty("wall_p95_ns", distribution.p95)
            addProperty("wall_p99_ns", distribution.p99)
            addProperty("wall_max_ns", distribution.maximum)
            addProperty("owner_thread_cpu_ns", ownerCpu)
            addProperty("process_cpu_ns", processCpu)
            addProperty("owner_thread_allocated_bytes", ownerAllocation)
            addProperty("all_threads_allocated_bytes", totalAllocation)
            addProperty("heap_first_sample_before_bytes", firstHeap)
            addProperty("heap_last_sample_after_bytes", lastHeap)
            addProperty("heap_largest_sample_boundary_bytes", largestHeap)
            addProperty("gc_collections_during_samples", gcCount)
            addProperty("gc_collection_time_ms_during_samples", gcMillis)
        }
    }

    private fun allocated(allThreads: Boolean): Long {
        val allocation = this.allocation ?: return -1
        if (allocation.isThreadAllocatedMemorySupported.not() || allocation.isThreadAllocatedMemoryEnabled.not()) return -1
        return runCatching {
            if (allThreads) (totalAllocationCounter?.invoke(allocation) as? Long) ?: -1 else allocation.currentThreadAllocatedBytes
        }.getOrDefault(-1)
    }

    private fun currentCpu(): Long = if (thread.isCurrentThreadCpuTimeSupported && thread.isThreadCpuTimeEnabled) thread.currentThreadCpuTime else -1

    private fun collectorTotal(metric: (GarbageCollectorMXBean) -> Long): Long =
        collectors.fold(0L) { total, collector ->
            val value = metric(collector)
            if (total < 0 || value < 0) -1 else total + value
        }

    private fun addDelta(
        total: Long?,
        before: Long,
        after: Long,
    ): Long? = if (total == null || before < 0 || after < before) null else total + after - before
}
