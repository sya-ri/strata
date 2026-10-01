package dev.s7a.strata.performance

/**
 * Monotonic preparation/execution budget, independent of timed sample distributions.
 * Child sections retain an earlier enclosing deadline, including one that has already expired.
 */
public class PerformanceDeadline internal constructor(
    timeoutMillis: Long,
    private val readTime: () -> Long,
) {
    private val timeoutNanos = Math.multiplyExact(timeoutMillis.also { require(0 <= it) }, 1_000_000L)
    private val startedAt = readTime()
    private var expiresAt = startedAt + timeoutNanos

    /**
     * Starts one nonnegative budget on the JDK monotonic clock; oversized durations fail before acquisition.
     */
    public constructor(timeoutMillis: Long) : this(timeoutMillis, System::nanoTime)

    /**
     * Checks relative order across nano-time wrap, allowing the exact terminal instant.
     */
    public fun isWithin(): Boolean = readTime() - expiresAt <= 0L

    /**
     * Captures whole-suite elapsed time, including preparation and cleanup rather than sample timing.
     */
    public fun elapsedNanos(): Long = (readTime() - startedAt).also { check(0 <= it) { "The monotonic performance clock moved backwards" } }

    /**
     * Starts a section whose own budget cannot extend this enclosing suite's deadline.
     */
    public fun child(timeoutMillis: Long): PerformanceDeadline {
        val section = PerformanceDeadline(timeoutMillis, readTime)
        if (expiresAt - section.startedAt <= section.timeoutNanos) section.expiresAt = expiresAt
        return section
    }
}
