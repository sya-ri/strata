package dev.s7a.strata.performance

/**
 * Bounded owner-thread application invocation counters with caller-defined stable names.
 * Only counters are retained; snapshots and serialization belong outside sample boundaries.
 * Overflow fails the invoking operation instead of wrapping into apparently valid work.
 */
public class WorkCounter(
    names: List<String>,
) {
    private val names = names.toList()
    private val counts = LongArray(names.size)

    init {
        require(this.names.isNotEmpty() && this.names.all(String::isNotBlank) && this.names.distinct().size == this.names.size)
    }

    /**
     * Records one invocation at the caller's registered ordinal.
     */
    public fun record(index: Int) {
        val previous = counts[index]
        check(previous < Long.MAX_VALUE) { "Application performance counter overflowed" }
        counts[index] = previous + 1
    }

    /**
     * Starts a fresh interval without allocating or changing counter identities.
     */
    public fun reset() {
        counts.fill(0)
    }

    /**
     * Transfers detached actual counts for work assertions and evidence.
     */
    public fun snapshot(): Map<String, Long> = names.mapIndexed { index, name -> name to counts[index] }.toMap()
}
