package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph

/**
 * Constant-size raw vertical extrema of one immutable run or the union of a current set of lines.
 *
 * Metrics deliberately ignore horizontal collapse and whole-line prepared-text rejection.
 * Such rejection at origin zero does not establish rejection after a different float origin is applied.
 * The extrema retain no glyph, image, native object, callback, or previous run.
 */
internal class MinecraftTextVerticalMetrics private constructor(
    private val sampled: Sampled?,
    private val legacyHeight: Int?,
) {
    /**
     * Evaluates a monotone conservative top edge without discarding collapsed intervals.
     *
     * @param origin candidate line origin computed in long arithmetic; callers validate its final integer range before painting.
     * @return minimum of actual-order sampled and exact integer-origin legacy top edges.
     */
    @JvmSynthetic
    internal fun minimumTopAt(origin: Long): Double {
        val sampledTop = sampled?.let { (origin.toFloat() + it.minimumTop + 0f).toDouble() } ?: Double.POSITIVE_INFINITY
        val legacyTop = if (legacyHeight == null) Double.POSITIVE_INFINITY else origin.toDouble()
        return minOf(sampledTop, legacyTop)
    }

    /**
     * Evaluates a monotone conservative bottom edge without discarding collapsed intervals.
     *
     * @param origin candidate line origin computed in long arithmetic; callers validate its final integer range before painting.
     * @return maximum of actual-order sampled and exact integer-origin legacy bottom edges; overflow remains conservatively infinite.
     */
    @JvmSynthetic
    internal fun maximumBottomAt(origin: Long): Double {
        val sampledBottom = sampled?.let { (origin.toFloat() + it.maximumBottom + it.maximumShadow).toDouble() } ?: Double.NEGATIVE_INFINITY
        val legacyBottom = legacyHeight?.let { origin.toDouble() + it } ?: Double.NEGATIVE_INFINITY
        return maxOf(sampledBottom, legacyBottom)
    }

    /**
     * Evaluates a conservative interval using the exact vertical addition order of sampled glyph painting.
     *
     * @param originY current integer origin before conversion to the painter's float coordinate space.
     * @return actual-origin envelope, possibly unbounded after overflow, or null when even the envelope collapses.
     */
    @JvmSynthetic
    internal fun at(originY: Int): MinecraftTextVerticalBounds? {
        val top = minimumTopAt(originY.toLong())
        val bottom = maximumBottomAt(originY.toLong())
        return if (top < bottom) MinecraftTextVerticalBounds(top, bottom) else null
    }

    private data class Sampled(
        val minimumTop: Float,
        val maximumBottom: Float,
        val maximumShadow: Float,
    )

    /**
     * Owner-thread accumulator used only while constructing a single run.
     * Every update borrows its glyph and stores only primitive extrema; [build] returns detached immutable metrics.
     */
    internal class Builder {
        private var minimumTop = Float.MAX_VALUE
        private var maximumBottom = -Float.MAX_VALUE
        private var maximumShadow = 0f
        private var hasGlyph = false
        private var legacyHeight: Int? = null

        /**
         * Unites another run's raw metrics while retaining only primitive extrema and an optional legacy height.
         *
         * @param metrics immutable borrowed run metrics; null contributes nothing.
         */
        @JvmSynthetic
        internal fun add(metrics: MinecraftTextVerticalMetrics?) {
            if (metrics == null) return
            metrics.sampled?.let { sampled ->
                minimumTop = minOf(minimumTop, sampled.minimumTop)
                maximumBottom = maxOf(maximumBottom, sampled.maximumBottom)
                maximumShadow = maxOf(maximumShadow, sampled.maximumShadow)
                hasGlyph = true
            }
            metrics.legacyHeight?.let(::addLegacy)
        }

        /**
         * Includes the exact height of a legacy bitmap layer and its optional shadow.
         *
         * @param height positive logical extent below the integer text origin.
         * @throws IllegalArgumentException when [height] is not positive.
         */
        @JvmSynthetic
        internal fun addLegacy(height: Int) {
            require(0 < height) { "Legacy glyph height must be positive." }
            legacyHeight = maxOf(legacyHeight ?: height, height)
        }

        /**
         * Includes one image glyph's finite vertical metrics without consulting its horizontal geometry.
         * Non-finite vertical provider metrics cannot produce a finite quad at any integer paint origin and are omitted.
         *
         * @param glyph borrowed detached provider result.
         * @param shadow whether this run may submit the glyph's shadow.
         */
        @JvmSynthetic
        internal fun add(
            glyph: MinecraftFontGlyph,
            shadow: Boolean,
        ) {
            if (glyph.image == null || glyph.top.isFinite().not() || glyph.bottom.isFinite().not()) return
            minimumTop = minOf(minimumTop, glyph.top)
            maximumBottom = maxOf(maximumBottom, glyph.bottom)
            if (shadow) maximumShadow = maxOf(maximumShadow, glyph.shadowOffset)
            hasGlyph = true
        }

        /**
         * Copies the current primitive extrema without retaining this accumulator.
         *
         * @return immutable metrics or null when no glyph has potentially finite vertical ink.
         */
        @JvmSynthetic
        internal fun build(): MinecraftTextVerticalMetrics? {
            if (hasGlyph.not() && legacyHeight == null) return null
            val sampled = if (hasGlyph) Sampled(minimumTop, maximumBottom, maximumShadow) else null
            return MinecraftTextVerticalMetrics(sampled, legacyHeight)
        }
    }
}
