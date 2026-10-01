package dev.s7a.strata.performance

import com.google.gson.JsonObject

/**
 * Kit-owned sample boundaries for interleaved historical workloads with untimed per-sample setup.
 * Consumers choose which operation to time; they cannot publish an incomplete or failed sequence.
 */
public class JvmPerformanceSequence internal constructor(
    capacities: Map<String, Int>,
) : AutoCloseable {
    private val meters = capacities.mapValues { (name, capacity) -> JvmPerformanceMeter(name, capacity) }.toMutableMap()
    private var closed = false

    /**
     * Collects one named successful operation on the construction owner thread.
     */
    public fun sample(
        name: String,
        operation: () -> Unit,
    ) {
        check(closed.not())
        meters.getValue(name).sample(operation)
    }

    /**
     * Requires every named interval to have exactly its declared number of successful samples.
     */
    internal fun complete(): Map<String, JsonObject> {
        check(closed.not())
        return meters.mapValues { it.value.result() }
    }

    /**
     * Releases collector storage; a retained caller reference cannot resume the sequence.
     */
    override fun close() {
        closed = true
        meters.clear()
    }
}
