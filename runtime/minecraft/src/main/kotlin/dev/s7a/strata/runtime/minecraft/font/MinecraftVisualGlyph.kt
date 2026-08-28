package dev.s7a.strata.runtime.minecraft.font

/**
 * Immutable visual-order Unicode scalar associated with its native UTF-16 style lookup position.
 * The position belongs to the shaped logical line and indexes the original unadjusted style sequence, matching native text processing.
 * Arabic contractions can therefore make it differ from the original scalar's position.
 * Values contain no backend ownership and can be shared across threads.
 *
 * @property codePoint shaped Unicode scalar to render.
 * @property sourceIndex non-negative UTF-16 style offset in the shaped logical line.
 * @throws IllegalArgumentException when the scalar or source offset is invalid.
 */
public data class MinecraftVisualGlyph(
    public val codePoint: Int,
    public val sourceIndex: Int,
) {
    init {
        FontJson.validateScalar(codePoint)
        require(0 <= sourceIndex) { "Visual glyph source offsets must be non-negative." }
    }
}
