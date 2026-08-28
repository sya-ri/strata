package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import dev.s7a.strata.text.UiText
import java.util.Collections

/**
 * Validated single-line text with immutable positioned glyph ownership.
 *
 * The run retains only glyph layers used by its literal and never retains the complete profile.
 * Shadow-free styles omit the shadow image and command rather than retaining a transparent placeholder.
 *
 * @property text exact literal retained for unresolved semantics.
 * @property size non-negative layout projection of the native width and the nine-pixel line height before constraints.
 * @property nativeWidth signed native text width before projection to the non-negative layout extent.
 */
internal class MinecraftTextRun private constructor(
    @get:JvmSynthetic
    internal val text: UiText,
    glyphs: List<PositionedGlyph>,
    @get:JvmSynthetic
    internal val size: IntSize,
    sampledGlyphs: List<SampledGlyph> = emptyList(),
    private val interleavedShadows: Boolean = true,
    @get:JvmSynthetic
    internal val nativeWidth: Int = size.width,
    private val preparedTextBounds: Boolean = false,
) {
    private val glyphSize = IntSize(8, 8)
    private val shadowOffset = IntOffset(1, 1)
    private val glyphs: List<PositionedGlyph> = Collections.unmodifiableList(glyphs.toList())
    private val sampledGlyphs: List<SampledGlyph> = Collections.unmodifiableList(sampledGlyphs.toList())

    /**
     * Applies the selected whole-line bounds rejection and emits valid glyph quads with optional shadows.
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
        if (preparedTextBounds && hasPreparedTextBounds(originX, originY).not()) return
        val source = IntRect(0, 0, glyphSize.width, glyphSize.height)
        for (positioned in glyphs) {
            val glyphX = Math.addExact(originX, positioned.x)
            val shadowLeft = Math.addExact(glyphX, shadowOffset.x)
            val shadowTop = Math.addExact(originY, shadowOffset.y)
            positioned.shadow?.let { shadow ->
                scope.blitImage(
                    shadow,
                    source,
                    IntRect(
                        shadowLeft,
                        shadowTop,
                        Math.addExact(shadowLeft, glyphSize.width),
                        Math.addExact(shadowTop, glyphSize.height),
                    ),
                )
            }
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
        if (interleavedShadows.not()) {
            sampledGlyphs.forEach { paintSampled(it, scope, originX, originY, true) }
        }
        sampledGlyphs.forEach { positioned ->
            if (interleavedShadows) paintSampled(positioned, scope, originX, originY, true)
            paintSampled(positioned, scope, originX, originY, false)
        }
    }

    private fun hasPreparedTextBounds(
        originX: Int,
        originY: Int,
    ): Boolean {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (positioned in sampledGlyphs) {
            val glyph = positioned.glyph
            val x = originX + positioned.x
            val rawLeft = if (glyph.orientation.flipX) glyph.right else glyph.left
            val rawRight = if (glyph.orientation.flipX) glyph.left else glyph.right
            val rawTop = if (glyph.orientation.flipY) glyph.bottom else glyph.top
            val rawBottom = if (glyph.orientation.flipY) glyph.top else glyph.bottom
            val shadow = positioned.shadow
            val offset = if (shadow != null && shadow.value != 0) glyph.shadowOffset else 0f
            left = Math.min(left, x + rawLeft)
            top = Math.min(top, originY + rawTop)
            right = Math.max(right, x + rawRight + offset)
            bottom = Math.max(bottom, originY + rawBottom + offset)
        }
        // Native rejection comparisons leave NaN aggregates drawable; reversing this to ordered comparisons changes that behavior.
        return (right <= left || bottom <= top).not()
    }

    private fun paintSampled(
        positioned: SampledGlyph,
        scope: PaintScope,
        originX: Int,
        originY: Int,
        shadow: Boolean,
    ) {
        val tint = if (shadow) positioned.shadow ?: return else positioned.foreground
        val glyph = positioned.glyph
        val image = checkNotNull(glyph.image)
        // Native font atlases inset every glyph UV by one hundredth of a source texel.
        val source = FloatRect(0.01f, 0.01f, image.size.width - 0.01f, image.size.height - 0.01f)
        val offset = if (shadow) glyph.shadowOffset else 0f
        val left = originX + positioned.x + glyph.left + offset
        val top = originY + glyph.top + offset
        val right = originX + positioned.x + glyph.right + offset
        val bottom = originY + glyph.bottom + offset
        if (left.isFinite().not() || top.isFinite().not()) return
        if (right.isFinite().not() || bottom.isFinite().not()) return
        if (right <= left || bottom <= top) return
        if ((right - left).isFinite().not() || (bottom - top).isFinite().not()) return
        scope.sampledImage(image, source, FloatRect(left, top, right, bottom), glyph.orientation, tint)
    }

    /**
     * Returns whether another run has the same literal, natural size, positions, and retained glyph values.
     *
     * @param other run to compare.
     * @return true when an element update is an exact retained no-op.
     */
    @Suppress("unused")
    @JvmSynthetic
    internal fun equivalentTo(other: MinecraftTextRun): Boolean = text == other.text && size == other.size && nativeWidth == other.nativeWidth && glyphs == other.glyphs && sampledGlyphs == other.sampledGlyphs && interleavedShadows == other.interleavedShadows && preparedTextBounds == other.preparedTextBounds

    private data class SampledGlyph(
        val x: Float,
        val glyph: MinecraftFontGlyph,
        val foreground: ArgbColor,
        val shadow: ArgbColor?,
    )

    private data class PositionedGlyph(
        val x: Int,
        val shadow: DrawImage?,
        val foreground: DrawImage,
    )

    /**
     * Supported code-point constants and the validated-run factory.
     */
    companion object {
        /**
         * Resolves one Unicode line with inherited fonts into detached floating-point glyph quads.
         * The engine is borrowed on its owner thread and is never retained by the returned run.
         * Non-finite advances retain native arithmetic and width conversion; paint applies the selected whole-line bounds rejection before omitting quads without valid finite portable geometry.
         *
         * @param text unresolved literal, font wrapper, or concatenation retained for semantics.
         * @param engine owner-thread font resolver.
         * @param font inherited font identifier, overridden by inner font wrappers.
         * @param foreground text shader tint.
         * @param shadow optional shadow tint; null omits shadow drawing.
         * @param logicalOrder bypasses display shaping and reordering, preserving the scalar sequence used by native EditBox.
         * @return immutable positioned text with the native nine-pixel line height and a non-negative projection of its native logical width.
         * @throws IllegalArgumentException for unresolved translations, platform values, malformed Unicode, multiline input, or legacy section-sign formatting.
         */
        @JvmSynthetic
        internal fun createFonts(
            text: UiText,
            engine: MinecraftFontEngine,
            font: ResourceId,
            foreground: ArgbColor,
            shadow: ArgbColor?,
            logicalOrder: Boolean = false,
        ): MinecraftTextRun {
            val value = StringBuilder()
            val fonts = ArrayList<ResourceId>()
            appendText(text, font, value, fonts)
            val logicalText = value.toString()
            val width = engine.compatibility.roundedWidth(logicalWidth(logicalText, fonts, engine))
            val positioned = ArrayList<SampledGlyph>()
            var cursor = 0f
            val ordered = if (logicalOrder) logicalGlyphs(logicalText) else engine.visualGlyphs(logicalText)
            ordered.forEach { visual ->
                require(visual.sourceIndex in fonts.indices) { "Font backend returned a style index outside the logical text." }
                val glyph = engine.glyph(fonts[visual.sourceIndex], visual.codePoint)
                if (glyph.image != null) {
                    positioned.add(SampledGlyph(cursor, glyph, foreground, shadow))
                }
                cursor += glyph.advance
            }
            return MinecraftTextRun(text, emptyList(), IntSize(maxOf(0, width), 9), positioned, engine.compatibility.interleavedShadows, width, engine.compatibility.preparedTextBounds)
        }

        private fun logicalGlyphs(text: String): List<MinecraftVisualGlyph> =
            buildList {
                var index = 0
                while (index < text.length) {
                    val codePoint = text.codePointAt(index)
                    add(MinecraftVisualGlyph(codePoint, index))
                    index += Character.charCount(codePoint)
                }
            }

        private fun logicalWidth(
            text: String,
            fonts: List<ResourceId>,
            engine: MinecraftFontEngine,
        ): Float {
            // Native Font.width measures the logical component before Arabic shaping and bidirectional ordering.
            var width = 0f
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                width += engine.glyph(fonts[index], codePoint).advance
                index += Character.charCount(codePoint)
            }
            return width
        }

        private fun appendText(
            text: UiText,
            inheritedFont: ResourceId,
            output: StringBuilder,
            fonts: MutableList<ResourceId>,
        ) {
            when (text) {
                is UiText.Literal -> {
                    var index = 0
                    while (index < text.value.length) {
                        val codePoint = text.value.codePointAt(index)
                        require((codePoint in 0xD800..0xDFFF).not() && codePoint != 0x0A && codePoint != 0x0D && codePoint != 0xA7) {
                            "Text requires well-formed single-line Unicode without legacy formatting markers."
                        }
                        val count = Character.charCount(codePoint)
                        repeat(count) { fonts.add(inheritedFont) }
                        output.appendCodePoint(codePoint)
                        index += count
                    }
                }

                is UiText.WithFont -> {
                    appendText(text.text, text.font, output, fonts)
                }

                is UiText.Concatenated -> {
                    text.parts.forEach { appendText(it, inheritedFont, output, fonts) }
                }

                is UiText.Translated, is UiText.Platform -> {
                    throw IllegalArgumentException("Common Minecraft text requires resolved literal text.")
                }
            }
        }

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
         * Creates a shadow-free container-label run retaining only 0xFF404040 foreground images.
         *
         * @param text unresolved value supplied by the application.
         * @param glyphAt synchronous lookup for one printable-ASCII glyph.
         * @return a shadow-free container-label text run.
         */
        @JvmSynthetic
        internal fun createContainerLabel(
            text: UiText,
            glyphAt: (Int) -> MinecraftGlyphSnapshot,
        ): MinecraftTextRun =
            create(text, glyphAt) { glyph ->
                PositionedColors(null, glyph.containerForeground)
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
            text: UiText,
            enabled: Boolean,
            glyphAt: (Int) -> MinecraftGlyphSnapshot,
        ): MinecraftTextRun =
            create(text, glyphAt) { glyph ->
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
                requireNotNull(compatibilityLiteral(text)) {
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
            return MinecraftTextRun(text, positioned, IntSize(cursor, lineHeight))
        }

        private fun compatibilityLiteral(
            text: UiText,
            font: ResourceId = MinecraftTextRenderer.defaultFont,
        ): UiText.Literal? =
            when (text) {
                is UiText.Literal -> {
                    require(font == MinecraftTextRenderer.defaultFont) { "Custom fonts require a font-resource snapshot." }
                    text
                }

                is UiText.WithFont -> {
                    compatibilityLiteral(text.text, text.font)
                }

                else -> {
                    null
                }
            }

        private data class PositionedColors(
            val shadow: DrawImage?,
            val foreground: DrawImage,
        )
    }
}
