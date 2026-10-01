package dev.s7a.strata.performance

import com.google.gson.JsonObject

/**
 * Shared synchronous execution; consumers describe operations rather than implementing sample loops.
 */
public object JvmPerformanceRunner {
    /**
     * Runs warm-up, successful samples, and optional actual UI diagnostics on the caller's owner thread.
     * Only the operation belongs to the timed interval; monitoring and serialization are separate.
     * The last result is transferred to the caller and no earlier application result is retained.
     */
    public fun <T : Any> measure(
        name: String,
        plan: PerformancePlan = PerformancePlan(),
        diagnosticsOwner: Any? = null,
        beforeSample: (Int) -> Unit = {},
        afterSample: (Int) -> Unit = {},
        afterOperation: (Int, T) -> Unit = { _, _ -> },
        operation: (Int) -> T,
    ): PerformanceSample<T> {
        repeat(plan.warmup) { operation(it) }
        val monitor = diagnosticsOwner?.let(::RuntimeWorkAccumulator)
        return monitor.use {
            val meter = JvmPerformanceMeter(name, plan.samples)
            var last: T? = null
            repeat(plan.samples) { index ->
                beforeSample(index)
                meter.sample { last = operation(index) }
                monitor?.capture()
                afterOperation(index, checkNotNull(last))
                afterSample(index)
            }
            val report = meter.result()
            monitor?.let { report.add("diagnostics", it.snapshot()) }
            PerformanceSample(report, checkNotNull(last) { "Performance operation returned no result" })
        }
    }

    /**
     * Runs untimed preparation operations before an application-owned fixture snapshot.
     * This compatibility entry point preserves existing workload boundaries during migration.
     */
    public fun warmup(
        count: Int,
        operation: (Int) -> Unit,
    ) {
        require(0 <= count)
        repeat(count, operation)
    }

    /**
     * Preserves interleaved legacy operations and their setup without a downstream sampling engine.
     * Failure propagates, incomplete intervals fail, and collector storage is released in either case.
     */
    public fun sequence(
        iterations: Int,
        capacities: Map<String, Int>,
        operation: (Int, JvmPerformanceSequence) -> Unit,
    ): Map<String, JsonObject> {
        require(0 < iterations && capacities.isNotEmpty())
        return JvmPerformanceSequence(capacities).use { sequence ->
            repeat(iterations) { index -> operation(index, sequence) }
            sequence.complete()
        }
    }
}
