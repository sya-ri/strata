package dev.s7a.strata.runtime.minecraft

/**
 * Conservative finite glyph-quad bounds independent of logical line advances and nine-pixel line boxes.
 *
 * Double precision preserves a union of distant finite float quads without overflowing the union's extent.
 * Bounds own no pixels or font resources and are used only to preserve natural overhang around a constrained text axis.
 *
 * @property left smallest submitted local quad edge.
 * @property top smallest submitted local quad edge.
 * @property right largest submitted local quad edge.
 * @property bottom largest submitted local quad edge.
 */
internal data class MinecraftTextInkBounds(
    @get:JvmSynthetic internal val left: Double,
    @get:JvmSynthetic internal val top: Double,
    @get:JvmSynthetic internal val right: Double,
    @get:JvmSynthetic internal val bottom: Double,
) {
    /**
     * Unites two detached conservative bounds without changing either input.
     *
     * @param other finite quad bounds in the same local coordinates.
     * @return smallest rectangle enclosing both inputs.
     */
    @JvmSynthetic
    internal fun union(other: MinecraftTextInkBounds): MinecraftTextInkBounds = MinecraftTextInkBounds(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
}
