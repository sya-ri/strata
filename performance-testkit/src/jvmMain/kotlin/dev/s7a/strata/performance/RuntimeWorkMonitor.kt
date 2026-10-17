package dev.s7a.strata.performance

import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * Compatibility reader for runtimes that expose the former opt-in diagnostic capability.
 * Production runtimes without that capability remain unmodified and report unavailable work metrics.
 * Operations and external elapsed/CPU/allocation measurements still run; work expectations require real counters.
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
            if (owner.javaClass.methods.any { it.name == "startRenderMonitoring" }) {
                checkNotNull(HostReflection.invoke(owner, "startRenderMonitoring", *arguments))
            } else {
                null
            }
        }
    private var remaining = 0
    private var closed = false

    /**
     * Whether the measured runtime supplies actual legacy work counters.
     */
    public val available: Boolean
        get() = closed.not() && monitor != null

    /**
     * Preserves a declared JMH monitoring window without downstream checkpoint scheduling.
     * This records work without adding another timer or changing the JMH harness boundary.
     */
    public fun <T> sample(operation: () -> T): T {
        check(closed.not()) { "Runtime work monitor is closed" }
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
        check(closed.not()) { "Runtime work monitor is closed" }
        monitor?.let { HostReflection.invoke(it, "checkpoint") }
    }

    /**
     * Rejects incomplete node evidence before returning detached counts and retention information.
     */
    public fun snapshot(): JsonObject {
        check(closed.not()) { "Runtime work monitor is closed" }
        val measured = monitor ?: return JsonObject().apply {
            addProperty("available", false)
            addProperty("reason", "The production runtime contains no work-measurement hooks; use external or test-only instrumentation.")
            add("counts", JsonNull.INSTANCE)
            add("active_subscriptions", JsonNull.INSTANCE)
            add("node_inventory_size", JsonNull.INSTANCE)
        }
        val snapshot = checkNotNull(HostReflection.invoke(measured, "snapshot"))
        return PerformanceJson.diagnostics(snapshot).apply { addProperty("available", true) }
    }

    /**
     * Applies declarative work expectations using complete actual invocation counters.
     */
    public fun verify(expectation: WorkExpectation) {
        check(available) { "Runtime work expectations require actual external or test-only counter evidence." }
        val counts = PerformanceJson.work(snapshot())
        expectation.verify(counts)
    }

    /**
     * Releases diagnostic ownership without closing the application session.
     */
    override fun close() {
        if (closed) return
        closed = true
        val previous = monitor
        monitor = null
        previous?.let { HostReflection.invoke(it, "close") }
    }
}
