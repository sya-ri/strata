package dev.s7a.strata.performance

/**
 * Collects one synchronous operation per application-owned scheduling opportunity.
 * The host supplies ticks or callbacks; warm-up counts, sample boundaries and incomplete evidence belong to the kit.
 * Construct, advance, complete and close on the same physical owner thread.
 * An operation must release its own temporary resources, including when warm-up fails.
 * Closing releases callbacks and collector storage, without closing the application fixture.
 */
public class JvmPerformanceSchedule<T : Any>(
    private val name: String,
    private val plan: PerformancePlan = PerformancePlan(),
    private var diagnosticsOwner: Any? = null,
    beforeSample: (Int) -> Unit = {},
    afterOperation: (Int, T) -> Unit = { _, _ -> },
    operation: (Int) -> T,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val deadline = PerformanceDeadline(plan.preparationTimeoutMillis)
    private var beforeSample: ((Int) -> Unit)? = beforeSample
    private var afterOperation: ((Int, T) -> Unit)? = afterOperation
    private var operation: ((Int) -> T)? = operation
    private var warmed = 0
    private var sampled = 0
    private var meter: JvmPerformanceMeter? = null
    private var monitor: RuntimeWorkAccumulator? = null
    private var last: T? = null
    private var closed = false

    /**
     * Runs at most one warm-up or measured operation and reports whether all required samples are complete.
     * Scheduling delay, preparation, diagnostics and assertions remain outside the timed operation.
     * Any callback failure closes the interval and propagates; it cannot subsequently publish evidence.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun advance(): Boolean {
        checkOwner()
        if (sampled == plan.samples) return true
        try {
            check(deadline.isWithin()) { "Scheduled performance interval timed out: $name" }
            val action = checkNotNull(operation)
            if (warmed < plan.warmup) {
                action(warmed)
                warmed += 1
                return false
            }
            if (meter == null) {
                meter = JvmPerformanceMeter(name, plan.samples)
                monitor = diagnosticsOwner?.let(::RuntimeWorkAccumulator)
                diagnosticsOwner = null
            }
            checkNotNull(beforeSample)(sampled)
            checkNotNull(meter).sample { last = action(sampled) }
            monitor?.capture()
            checkNotNull(afterOperation)(sampled, checkNotNull(last))
            check(deadline.isWithin()) { "Scheduled performance interval timed out: $name" }
            sampled += 1
            return sampled == plan.samples
        } catch (failure: Throwable) {
            runCatching(::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    /**
     * Returns detached evidence and transfers the last successful result only after complete collection.
     * Repeated calls detach new evidence; a closed or failed schedule cannot complete.
     */
    public fun complete(): PerformanceSample<T> {
        checkOwner()
        check(sampled == plan.samples)
        val report = checkNotNull(meter).result()
        monitor?.let { report.add("diagnostics", it.snapshot()) }
        return PerformanceSample(report, checkNotNull(last))
    }

    /**
     * Releases retained callbacks, application references and collector storage exactly once.
     */
    override fun close() {
        check(Thread.currentThread() === owner)
        if (closed) return
        closed = true
        try {
            monitor?.close()
        } finally {
            monitor = null
            meter = null
            diagnosticsOwner = null
            operation = null
            beforeSample = null
            afterOperation = null
            last = null
        }
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner && closed.not())
    }
}
