package dev.s7a.strata.performance

import com.google.gson.JsonObject

/**
 * Owner-thread monitor over the actual measured Strata instance, with no runtime-version substitution.
 *
 * @param owner the measured runtime diagnostics owner.
 * @param checkpointSamples optional fixed diagnostic window for an existing JMH workload.
 * @param maxNodeRecords optional explicit capacity in 1..65,536; absent preserves the measured runtime's existing default.
 */
public class RuntimeWorkMonitor(
    owner: Any,
    private val checkpointSamples: Int? = null,
    maxNodeRecords: Int? = null,
) : AutoCloseable {
    private var monitor: Any? =
        run {
            require(checkpointSamples == null || 0 < checkpointSamples)
            require(maxNodeRecords == null || maxNodeRecords in 1..65_536)
            val arguments = if (maxNodeRecords == null) emptyArray() else arrayOf(maxNodeRecords)
            checkNotNull(HostReflection.invoke(owner, "startRenderMonitoring", *arguments))
        }
    private var remaining = 0

    /**
     * Preserves a declared JMH monitoring window without downstream checkpoint scheduling.
     * This records work without adding another timer or changing the JMH harness boundary.
     */
    public fun <T> sample(operation: () -> T): T {
        checkNotNull(monitor)
        val interval = checkNotNull(checkpointSamples) { "No diagnostic sample window was declared" }
        if (remaining == 0) {
            checkpoint()
            remaining = interval
        }
        remaining -= 1
        return operation()
    }

    /**
     * Begins a fresh work interval without retaining historical frame evidence.
     */
    public fun checkpoint() {
        HostReflection.invoke(checkNotNull(monitor), "checkpoint")
    }

    /**
     * Rejects incomplete node evidence before returning detached counts and retention information.
     */
    public fun snapshot(): JsonObject {
        val snapshot = checkNotNull(HostReflection.invoke(checkNotNull(monitor), "snapshot"))
        return PerformanceJson.diagnostics(snapshot)
    }

    /**
     * Applies declarative work expectations using complete actual invocation counters.
     */
    public fun verify(expectation: WorkExpectation) {
        val counts = PerformanceJson.work(snapshot())
        expectation.verify(counts)
    }

    /**
     * Releases diagnostic ownership without closing the application session.
     */
    override fun close() {
        val previous = monitor ?: return
        monitor = null
        HostReflection.invoke(previous, "close")
    }
}
