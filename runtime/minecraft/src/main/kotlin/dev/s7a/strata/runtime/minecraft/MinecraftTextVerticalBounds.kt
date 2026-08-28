package dev.s7a.strata.runtime.minecraft

/**
 * Immutable conservative nonempty vertical ink interval in one paint scope's coordinates.
 *
 * Infinite endpoints are permitted as conservative envelopes, but NaN and empty intervals are rejected.
 * This value owns no font, image, profile, or mutable rendering state and is safe to share with its immutable run.
 *
 * @property top inclusive minimum possible quad edge.
 * @property bottom exclusive maximum possible quad edge.
 */
internal data class MinecraftTextVerticalBounds(
    @get:JvmSynthetic internal val top: Double,
    @get:JvmSynthetic internal val bottom: Double,
) {
    init {
        require(top < bottom) { "Vertical ink bounds must be ordered and nonempty." }
    }

    /**
     * Tests a half-open integer clip without rounding its endpoints to float.
     *
     * @param viewportTop inclusive local clip edge.
     * @param viewportBottom exclusive local clip edge; an empty or reversed interval never intersects.
     * @return whether this conservative ink interval can overlap the supplied vertical clip.
     */
    @JvmSynthetic
    internal fun intersects(
        viewportTop: Int,
        viewportBottom: Int,
    ): Boolean = viewportTop < viewportBottom && top < viewportBottom.toDouble() && viewportTop.toDouble() < bottom
}
