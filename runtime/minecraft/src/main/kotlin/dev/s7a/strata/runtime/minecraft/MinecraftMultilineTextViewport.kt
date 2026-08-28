package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Separates natural glyph overhang from explicitly constrained display-text viewport axes.
 *
 * A loose constraint that contains the submitted ink adds no clip.
 * An exact extent or overflowing axis clips only that axis; the other axis retains its natural ink bearings.
 * A maximum line count omits later runs without cutting the final visible line's ink.
 */
internal object MinecraftMultilineTextViewport {
    /**
     * Computes an optional portable clip independently of the logical text's measured extent.
     *
     * @param layout current detached lines and conservative glyph bounds.
     * @param constraints parent constraints used for this layout.
     * @return local clip including unconstrained overhang, or null for naturally fitting text.
     * @throws ArithmeticException when the required expanded clip is outside portable integer geometry.
     */
    @JvmSynthetic
    internal fun clipBounds(
        layout: MinecraftTextLayout,
        constraints: Constraints,
    ): IntRect? {
        val ink = layout.inkBounds() ?: MinecraftTextInkBounds(0.0, 0.0, layout.size.width.toDouble(), layout.size.height.toDouble())
        val clipX =
            constraints.maxWidth != Int.MAX_VALUE &&
                (constraints.minWidth == constraints.maxWidth || constraints.maxWidth.toDouble() < ink.right || layout.lines.any { constraints.maxWidth < it.run.nativeWidth })
        val clipY =
            constraints.maxHeight != Int.MAX_VALUE &&
                (constraints.minHeight == constraints.maxHeight || constraints.maxHeight.toDouble() < ink.bottom || constraints.maxHeight < layout.size.height)
        if (clipX.not() && clipY.not()) return null
        val left = if (clipX) 0 else integerFloor(minOf(0.0, ink.left))
        val top = if (clipY) 0 else integerFloor(minOf(0.0, ink.top))
        val right = if (clipX) constraints.maxWidth else integerCeil(maxOf(layout.size.width.toDouble(), ink.right))
        val bottom = if (clipY) constraints.maxHeight else integerCeil(maxOf(layout.size.height.toDouble(), ink.bottom))
        return IntRect(left, top, right, bottom)
    }

    private fun integerFloor(value: Double): Int = Math.toIntExact(floor(value).toLong())

    private fun integerCeil(value: Double): Int = Math.toIntExact(ceil(value).toLong())
}
