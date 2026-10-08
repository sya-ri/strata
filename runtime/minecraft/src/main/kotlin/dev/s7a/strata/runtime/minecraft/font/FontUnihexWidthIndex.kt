package dev.s7a.strata.runtime.minecraft.font

import java.util.TreeSet

/**
 * Immutable first-declaration winners between sorted inclusive scalar endpoints.
 * Construction reads only scalar endpoints and never evaluates or validates pixel bounds.
 * One owner-thread engine retains this derived state; aliases of a declaration share it, while engines do not.
 * Two primitive arrays reserve at most twice the override count, with no scalar-request history.
 *
 * @param overrides complete immutable declaration order from the engine's fixed snapshot.
 */
internal class FontUnihexWidthIndex private constructor(
    private val overrides: List<FontProvider.WidthOverride>,
    private val starts: IntArray,
    private val winners: IntArray,
    val segmentCount: Int,
) {
    /**
     * Reserved primitive payload, including unused capacity; excludes collection and object headers.
     */
    val bytes: Int get() = Math.multiplyExact(starts.size, 8)

    /**
     * Resolves the earliest declared range containing the scalar, or null in a gap.
     * Binary search examines at most logarithmically many retained boundaries and does no pixel arithmetic.
     */
    fun lookup(codePoint: Int): FontProvider.WidthOverride? {
        var first = 0
        var last = segmentCount
        while (first < last) {
            val middle = first + (last - first) / 2
            if (starts[middle] <= codePoint) first = middle + 1 else last = middle
        }
        val winner = if (first == 0) -1 else winners[first - 1]
        return if (winner < 0) null else overrides[winner]
    }

    /**
     * Checked provider admission and sweep construction under the engine's separate aggregate storage bound.
     */
    companion object {
        /**
         * A fixed temporary-allocation ceiling even when snapshot input limits are increased.
         */
        const val MAX_OVERRIDES = 8_192

        /**
         * Builds at most two boundaries per declaration in O(R log R) work.
         * Sorting endpoints preserves scalar order; the active declaration set separately preserves original priority.
         * Callers admit storage before construction and publish only a fully built value.
         */
        fun build(overrides: List<FontProvider.WidthOverride>): FontUnihexWidthIndex {
            require(overrides.size <= MAX_OVERRIDES)
            val capacity = Math.multiplyExact(overrides.size, 2)
            val events = LongArray(capacity)
            overrides.forEachIndexed { priority, bounds ->
                events[priority * 2] = (bounds.first.toLong() shl 32) or (priority.toLong() shl 1)
                events[priority * 2 + 1] = (Math.incrementExact(bounds.last).toLong() shl 32) or (priority.toLong() shl 1) or 1L
            }
            events.sort()
            val active = TreeSet<Int>()
            val starts = IntArray(capacity)
            val winners = IntArray(capacity)
            var event = 0
            var segments = 0
            var previous = -1
            while (event < events.size) {
                val scalar = (events[event] ushr 32).toInt()
                do {
                    val encoded = events[event].toInt()
                    val priority = encoded ushr 1
                    if (encoded and 1 == 0) active.add(priority) else active.remove(priority)
                    event++
                } while (event < events.size && (events[event] ushr 32).toInt() == scalar)
                val winner = if (active.isEmpty()) -1 else active.first()
                if (winner != previous) {
                    starts[segments] = scalar
                    winners[segments] = winner
                    segments++
                    previous = winner
                }
            }
            return FontUnihexWidthIndex(overrides, starts, winners, segments)
        }
    }
}
