package dev.s7a.strata.runtime.minecraft

import kotlin.math.abs

/**
 * One detached laid-out line with scalar-aligned original offsets and logical caret metrics.
 *
 * Arrays are copied once and never exposed; one admission flag belongs to their current immutable lifetime.
 * The line owns no renderer, font backend, coordinate history or lookup result cache.
 * Display shaping changes only the run's visual glyphs, not the logical editing coordinates.
 *
 * @property start inclusive original UTF-16 start.
 * @property end exclusive visible logical end before a hard break or ellipsis.
 * @property nextStart start of the next line, including any consumed hard-break sequence.
 * @property run immutable font-preserving glyph run for this line.
 * @param offsets ordered original scalar boundaries, including both ends.
 * @param positions signed native horizontal widths corresponding to [offsets].
 */
internal class MinecraftTextLine(
    @get:JvmSynthetic
    internal val start: Int,
    @get:JvmSynthetic
    internal val end: Int,
    @get:JvmSynthetic
    internal val nextStart: Int,
    @get:JvmSynthetic
    internal val run: MinecraftTextRun,
    offsets: IntArray,
    positions: IntArray,
) {
    private val offsets = offsets.copyOf()
    private val positions = positions.copyOf()
    private val monotone = (1 until this.positions.size).all { index -> this.positions[index - 1] <= this.positions[index] }

    /**
     * Conservative current-run quad bounds computed once for viewport culling, without retaining a renderer or historical line.
     */
    @get:JvmSynthetic
    internal val inkBounds: MinecraftTextInkBounds? = run.inkBounds()

    /**
     * Returns the native width at a logical insertion position clamped to this visible line.
     *
     * @param offset original UTF-16 insertion offset.
     * @return signed horizontal caret coordinate, rounded by the selected Minecraft profile.
     */
    @JvmSynthetic
    internal fun caretX(offset: Int): Int {
        val found = offsets.binarySearch(offset.coerceIn(start, end))
        val index = if (0 <= found) found else maxOf(0, -found - 2)
        return positions[index]
    }

    /**
     * Finds the complete horizontal span visited by scalar insertion boundaries in one source range.
     *
     * Signed or cancelling advances can reach beyond both endpoints, so every contained boundary participates.
     * Work is proportional to the selected current-line scalars; editors derive and cache composition ranges before painting.
     *
     * @param start inclusive original UTF-16 insertion offset, clamped to this line.
     * @param end exclusive source range end whose insertion position is included.
     * @return inclusive minimum and maximum logical caret coordinates; equal endpoints describe a zero-width span.
     */
    @JvmSynthetic
    internal fun caretExtents(
        start: Int,
        end: Int,
    ): IntRange {
        require(start <= end) { "Caret ranges must be ordered." }
        val firstMatch = offsets.binarySearch(start.coerceIn(this.start, this.end))
        val lastMatch = offsets.binarySearch(end.coerceIn(this.start, this.end))
        val first = if (0 <= firstMatch) firstMatch else maxOf(0, -firstMatch - 2)
        val last = if (0 <= lastMatch) lastMatch else maxOf(0, -lastMatch - 2)
        var left = positions[first]
        var right = left
        for (index in first..last) {
            left = minOf(left, positions[index])
            right = maxOf(right, positions[index])
        }
        return left..right
    }

    /**
     * Finds the nearest logical scalar boundary, including for non-monotonic signed font advances.
     *
     * Nondecreasing rounded coordinates use two bounded lower-bound searches; other lines retain the complete ordered scan.
     * Admission is computed once from this line's owned coordinates, without font calls or additional lookup storage.
     *
     * @param x local horizontal pointer or preferred vertical-navigation position.
     * @return original UTF-16 scalar boundary; equal distances and coordinate plateaus select the earliest logical boundary.
     */
    @JvmSynthetic
    internal fun offsetAt(x: Int): Int = offsets[nearestIndex(x) { index -> positions[index] }]

    /**
     * Counts coordinate reads through the exact lookup algorithm outside timed input or rendering.
     * The inline read hook adds no callback, counter, allocation or retained state to [offsetAt].
     */
    @JvmSynthetic
    internal fun boundaryVisits(x: Int): Int {
        var visits = 0
        nearestIndex(x) { index ->
            visits += 1
            positions[index]
        }
        return visits
    }

    private inline fun nearestIndex(
        x: Int,
        position: (Int) -> Int,
    ): Int {
        if (monotone.not()) return scannedIndex(x, position)
        val right = lowerBound(x, position)
        if (right == 0) return 0
        if (right == positions.size) return lowerBound(position(positions.lastIndex), position)
        val leftPosition = position(right - 1)
        val leftDistance = abs(leftPosition.toLong() - x.toLong())
        val rightDistance = abs(position(right).toLong() - x.toLong())
        return if (leftDistance <= rightDistance) lowerBound(leftPosition, position) else right
    }

    private inline fun lowerBound(
        x: Int,
        position: (Int) -> Int,
    ): Int {
        var first = 0
        var end = positions.size
        while (first < end) {
            val middle = first + (end - first) / 2
            if (position(middle) < x) first = middle + 1 else end = middle
        }
        return first
    }

    private inline fun scannedIndex(
        x: Int,
        position: (Int) -> Int,
    ): Int {
        var nearest = 0
        var distance = Long.MAX_VALUE
        for (index in positions.indices) {
            val candidate = abs(position(index).toLong() - x.toLong())
            if (candidate < distance) {
                nearest = index
                distance = candidate
            }
        }
        return nearest
    }
}
