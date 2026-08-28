package dev.s7a.strata.runtime.minecraft.font

/**
 * Immutable visual-order Unicode scalar associated with its original logical UTF-16 position.
 * The position identifies the first contributing scalar before shaping and bidirectional reordering; a Lam-Alef ligature belongs to the Lam.
 * This preserves the original font spans after contractions, deliberately correcting native text processing's shifted style lookup.
 * Values contain no backend ownership and can be shared across threads.
 *
 * @property codePoint shaped Unicode scalar to render.
 * @property sourceIndex non-negative UTF-16 scalar offset in the original logical line.
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
