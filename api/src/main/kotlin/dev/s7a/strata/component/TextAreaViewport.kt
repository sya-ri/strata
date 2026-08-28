package dev.s7a.strata.component

import dev.s7a.strata.geometry.IntSize

/**
 * Immutable outer viewport requested by a multiline text editor.
 *
 * The active runtime resolves nine-pixel logical line boxes, additional line spacing, and frame insets while preserving the requested width.
 * Values own no runtime resources and may be shared across threads.
 */
public sealed interface TextAreaViewport {
    /**
     * Derives the outer height from a number of visible text rows, line spacing, and runtime frame insets.
     *
     * @property width positive outer logical width, including the runtime's horizontal frame insets.
     * @property lines positive number of visible text rows.
     * @throws IllegalArgumentException when [width] or [lines] is not positive.
     */
    public data class Lines(
        public val width: Int,
        public val lines: Int,
    ) : TextAreaViewport {
        init {
            require(0 < width) { "Text area width must be positive." }
            require(0 < lines) { "Text area visible line count must be positive." }
        }
    }

    /**
     * Uses an exact outer logical size independent of the requested row count or glyph ink height.
     *
     * @property size positive outer extent, including runtime frame insets.
     * @throws IllegalArgumentException when either dimension is non-positive.
     */
    public data class Size(
        public val size: IntSize,
    ) : TextAreaViewport {
        init {
            require(0 < size.width && 0 < size.height) { "Text area size must be positive." }
        }
    }
}
