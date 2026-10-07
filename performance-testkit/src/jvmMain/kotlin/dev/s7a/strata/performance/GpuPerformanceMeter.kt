package dev.s7a.strata.performance

import com.google.gson.JsonObject
import kotlin.math.roundToLong

/**
 * Collects completed native timestamp pairs without inferring GPU work from a CPU clock.
 *
 * The host owns query allocation, placement, submission, completion and release on its render thread.
 * [readTimestamp] is borrowed until [close]; null means the actual query has not completed.
 * Tick conversion uses the loaded device's positive [timestampPeriodNanos], never a backend-name assumption.
 * Storage is bounded by [samples]; the host calls [begin] before its owner-thread operation and [recorded] after recording the pair.
 * This collector measures only [scope]; unrelated host commands inside that boundary are included.
 */
public class GpuPerformanceMeter(
    private val samples: Int,
    private val timestampPeriodNanos: Double,
    private val scope: String,
    readTimestamp: (Int) -> Long?,
) : AutoCloseable {
    init {
        require(samples in 1..10_000)
        require(timestampPeriodNanos.isFinite() && 0.0 < timestampPeriodNanos)
        require(scope.isNotBlank())
    }

    private var reader: ((Int) -> Long?)? = readTimestamp
    private var recorded = 0
    private var pending = false
    private var completedCount = 0
    private val starts = LongArray(samples)
    private val timestamps = LongArray(samples * 2)
    private val observations = LongArray(samples)

    /**
     * Starts one host operation and polls preceding completed pairs without blocking.
     * The kit owns this wall clock; first-observed completion includes queueing and host polling delay.
     */
    public fun begin() {
        check(reader != null && pending.not() && recorded < samples)
        poll()
        starts[recorded] = System.nanoTime()
        pending = true
    }

    /**
     * Acknowledges a successfully recorded pair; missing or duplicate pairs cannot certify a complete interval.
     */
    public fun recorded() {
        check(reader != null && pending && recorded < samples)
        pending = false
        recorded += 1
    }

    /**
     * True only when every recorded start and end query has a completed native value.
     * This owner-thread poll does not block or submit new GPU work.
     */
    public val completed: Boolean
        get() {
            poll()
            return pending.not() && completedCount == samples
        }

    private fun poll() {
        val source = checkNotNull(reader) { "GPU collector is closed." }
        while (completedCount < recorded) {
            val index = completedCount
            val start = source(index * 2) ?: return
            val end = source(index * 2 + 1) ?: return
            timestamps[index * 2] = start
            timestamps[index * 2 + 1] = end
            observations[index] = (System.nanoTime() - starts[index]).also { require(0 <= it) }
            completedCount += 1
        }
    }

    /**
     * Returns detached nanosecond distributions after complete query evidence, without a frame or FPS claim.
     * Reversed, overflowing or nonrepresentable timestamp differences reject the whole interval.
     */
    public fun result(): JsonObject {
        check(completed) { "GPU timestamp interval is incomplete." }
        val durations =
            LongArray(samples) { index ->
                val ticks = Math.subtractExact(timestamps[index * 2 + 1], timestamps[index * 2])
                require(0 <= ticks) { "GPU timestamp pair is reversed." }
                val nanos = ticks.toDouble() * timestampPeriodNanos
                require(nanos.isFinite() && nanos < Long.MAX_VALUE.toDouble()) { "GPU duration is not representable." }
                nanos.roundToLong()
            }
        return JsonObject().apply {
            addProperty("available", true)
            addProperty("samples", samples)
            addProperty("scope", scope)
            addProperty("timestamp_period_ns", timestampPeriodNanos)
            add("duration", PerformanceJson.distribution(durations.toList()))
            add("operation_to_completion_observation", PerformanceJson.distribution(observations.toList()))
            addProperty("completion_scope", "Owner-thread operation start to first observed completed GUI timestamp pair; includes queueing and polling delay, excludes display presentation.")
        }
    }

    /**
     * Severs the borrowed query reader; the host independently releases its completed native queries.
     */
    override fun close() {
        reader = null
    }
}
