package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.text.UiText
import java.util.Collections

/**
 * Validated single-line printable-ASCII text with immutable positioned glyph ownership.
 *
 * The run retains only glyph layers used by its literal and never retains the complete profile.
 *
 * @property text exact literal retained for unresolved semantics.
 * @property size natural Minecraft text size before constraints.
 */
internal class MinecraftTextRun private constructor(
    @get:JvmSynthetic
    internal val text: UiText.Literal,
    glyphs: List<PositionedGlyph>,
    @get:JvmSynthetic
    internal val size: IntSize,
) {
    private val glyphSize = IntSize(8, 8)
    private val shadowOffset = IntOffset(1, 1)
    private val glyphs: List<PositionedGlyph> = Collections.unmodifiableList(glyphs.toList())

    /**
     * Emits every glyph in character order with that glyph's shadow immediately before its foreground.
     *
     * @param scope active node-local paint collector.
     * @param originX local horizontal text origin.
     * @param originY local top of the nine-pixel line box.
     */
    @JvmSynthetic
    internal fun paint(
        scope: PaintScope,
        originX: Int,
        originY: Int,
    ) {
        val source = IntRect(0, 0, glyphSize.width, glyphSize.height)
        for (positioned in glyphs) {
            val glyphX = Math.addExact(originX, positioned.x)
            val shadowLeft = Math.addExact(glyphX, shadowOffset.x)
            val shadowTop = Math.addExact(originY, shadowOffset.y)
            scope.blitImage(
                positioned.shadow,
                source,
                IntRect(
                    shadowLeft,
                    shadowTop,
                    Math.addExact(shadowLeft, glyphSize.width),
                    Math.addExact(shadowTop, glyphSize.height),
                ),
            )
            scope.blitImage(
                positioned.foreground,
                source,
                IntRect(
                    glyphX,
                    originY,
                    Math.addExact(glyphX, glyphSize.width),
                    Math.addExact(originY, glyphSize.height),
                ),
            )
        }
    }

    /**
     * Returns whether another run has the same literal, natural size, positions, and retained glyph values.
     *
     * @param other run to compare.
     * @return true when an element update is an exact retained no-op.
     */
    @JvmSynthetic
    internal fun equivalentTo(other: MinecraftTextRun): Boolean = text == other.text && size == other.size && glyphs == other.glyphs

    private data class PositionedGlyph(
        val x: Int,
        val shadow: DrawImage,
        val foreground: DrawImage,
    )

    /**
     * Supported code-point constants and the validated-run factory.
     */
    companion object {
        /**
         * Creates a normal-color run retaining only normal shadow and foreground images.
         *
         * @param text unresolved value supplied by the application.
         * @param glyphAt synchronous lookup for one printable-ASCII glyph.
         * @return a normal-color text run.
         */
        @JvmSynthetic
        internal fun createNormal(
            text: UiText,
            glyphAt: (Int) -> MinecraftGlyphSnapshot,
        ): MinecraftTextRun =
            create(text, glyphAt) { glyph ->
                PositionedColors(glyph.normalShadow, glyph.normalForeground)
            }

        /**
         * Creates an inactive-color run retaining only inactive shadow and foreground images.
         *
         * @param text unresolved value supplied by the application.
         * @param glyphAt synchronous lookup for one printable-ASCII glyph.
         * @return an inactive-color text run.
         */
        @JvmSynthetic
        internal fun createInactive(
            text: UiText,
            glyphAt: (Int) -> MinecraftGlyphSnapshot,
        ): MinecraftTextRun =
            create(text, glyphAt) { glyph ->
                PositionedColors(glyph.inactiveShadow, glyph.inactiveForeground)
            }

        /**
         * Creates an EditBox-color run retaining only the selected enabled or disabled layers.
         *
         * @param text printable-ASCII literal value.
         * @param enabled whether enabled EditBox colors are selected.
         * @param glyphAt synchronous printable-ASCII glyph lookup.
         * @return one EditBox text run.
         */
        @JvmSynthetic
        internal fun createTextField(
            text: String,
            enabled: Boolean,
            glyphAt: (Int) -> MinecraftGlyphSnapshot,
        ): MinecraftTextRun =
            create(UiText.Literal(text), glyphAt) { glyph ->
                if (enabled) {
                    PositionedColors(glyph.textFieldShadow, glyph.textFieldForeground)
                } else {
                    PositionedColors(glyph.textFieldDisabledShadow, glyph.textFieldDisabledForeground)
                }
            }

        /**
         * Validates and snapshots one literal through a complete profile.
         *
         * @param text unresolved value supplied by the application.
         * @param glyphAt synchronous lookup for one printable-ASCII glyph; the callback is never retained.
         * @return a natural-size printable-ASCII text run.
         * @throws IllegalArgumentException when [text] is not a literal or contains a value outside U+0020 through U+007E.
         */
        private fun create(
            text: UiText,
            glyphAt: (Int) -> MinecraftGlyphSnapshot,
            colors: (MinecraftGlyphSnapshot) -> PositionedColors,
        ): MinecraftTextRun {
            val supportedCodePoints = 0x20..0x7E
            val spaceAdvance = 4
            val lineHeight = 9
            val literal =
                requireNotNull(text as? UiText.Literal) {
                    "Common Minecraft text currently requires UiText.Literal."
                }
            val positioned = ArrayList<PositionedGlyph>()
            var cursor = 0
            for (character in literal.value) {
                val codePoint = character.code
                require(codePoint in supportedCodePoints) {
                    "Common Minecraft text supports only U+0020 through U+007E."
                }
                if (codePoint == supportedCodePoints.first) {
                    cursor = Math.addExact(cursor, spaceAdvance)
                } else {
                    val glyph = glyphAt(codePoint)
                    val selected = colors(glyph)
                    positioned += PositionedGlyph(cursor, selected.shadow, selected.foreground)
                    cursor = Math.addExact(cursor, glyph.advance)
                }
            }
            return MinecraftTextRun(literal, positioned, IntSize(cursor, lineHeight))
        }

        private data class PositionedColors(
            val shadow: DrawImage,
            val foreground: DrawImage,
        )
    }
}
