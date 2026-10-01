package dev.s7a.strata.performance

import com.google.gson.JsonObject

/**
 * Bounded aggregation of complete per-sample runtime diagnostics on the application's owner thread.
 * Retired node identities are discarded after each snapshot, outside the measured operation.
 * A single overflowing sample still fails; global counts never stand in for truncated node evidence.
 *
 * @param owner the actual measured runtime diagnostics owner.
 */
public class RuntimeWorkAccumulator(
    owner: Any,
) : AutoCloseable {
    private val monitor = RuntimeWorkMonitor(owner)
    private val counts = mutableMapOf<String, Long>()
    private var samples = 0L
    private var subscriptions = 0L
    private var maximumSubscriptions = 0L
    private var inventorySize = 0L
    private var maximumInventorySize = 0L
    private var closed = false
    private var failed = false

    /**
     * Captures a complete sample and clears retired records before the next application update.
     * Failures poison the interval and cannot be retried into a successful report.
     */
    public fun capture() {
        check(closed.not() && failed.not()) { "Runtime work interval is closed or failed" }
        failed = true
        val snapshot = monitor.snapshot()
        val current = snapshot.getAsJsonObject("counts").entrySet().associate { (key, value) -> key to value.asLong }
        check(samples == 0L || counts.keys == current.keys) { "Runtime diagnostic metric inventory changed" }
        current.forEach { (key, value) ->
            check(0 <= value) { "Runtime diagnostic counter is negative: $key" }
            counts[key] = Math.addExact(counts[key] ?: 0L, value)
        }
        subscriptions = snapshot.get("active_subscriptions").asLong
        check(0 <= subscriptions) { "Runtime subscription gauge is negative" }
        maximumSubscriptions = maxOf(maximumSubscriptions, subscriptions)
        inventorySize = snapshot.get("node_inventory_size").asLong
        maximumInventorySize = maxOf(maximumInventorySize, inventorySize)
        samples = Math.addExact(samples, 1L)
        monitor.checkpoint()
        failed = false
    }

    /**
     * Returns detached totals from successful samples and the final and maximum subscription gauges.
     */
    public fun snapshot(): JsonObject {
        check(closed.not() && failed.not() && 0 < samples) { "Runtime work interval has no complete evidence" }
        return JsonObject().apply {
            add("counts", JsonObject().apply { counts.forEach { (key, value) -> addProperty(key, value) } })
            addProperty("active_subscriptions", subscriptions)
            addProperty("maximum_active_subscriptions", maximumSubscriptions)
            addProperty("node_inventory_size", inventorySize)
            addProperty("maximum_node_inventory_size", maximumInventorySize)
            addProperty("node_inventory_truncated", false)
            addProperty("diagnostic_samples", samples)
            addProperty("aggregation", "complete-sample-intervals-v1")
        }
    }

    /**
     * Terminally releases monitoring and aggregate storage without closing the application.
     */
    override fun close() {
        if (closed) return
        closed = true
        counts.clear()
        monitor.close()
    }
}
