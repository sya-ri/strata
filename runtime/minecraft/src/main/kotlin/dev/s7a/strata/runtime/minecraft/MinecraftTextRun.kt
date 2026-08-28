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
 * @property verticalMetrics immutable raw vertical extrema for conservative actual-origin line selection.
 */
internal class MinecraftTextRun private constructor(
    @get:JvmSynthetic
    internal val text: UiText,
    glyphs: List<PositionedGlyph>,
    @get:JvmSynthetic
    internal val size: IntSize,
    sampledGlyphs: List<SampledGlyph> = emptyList(),
    @get:JvmSynthetic
    internal val nativeWidth: Int = size.width,
    private val paintPolicy: PaintPolicy = PaintPolicy(),
    @get:JvmSynthetic
    internal val verticalMetrics: MinecraftTextVerticalMetrics? = null,
) {
    private val glyphSize = IntSize(8, 8)
    private val shadowOffset = IntOffset(1, 1)
    private val glyphs: List<PositionedGlyph> = Collections.unmodifiableList(glyphs.toList())
    private val sampledGlyphs: List<SampledGlyph> = Collections.unmodifiableList(sampledGlyphs.toList())
    private val sampledIndex: SampledIndex? = if (paintPolicy.forwardAdvances) SampledIndex.create(this.sampledGlyphs) else null
    private val sampledShadows: Boolean = this.sampledGlyphs.any { it.shadow != null }

    /**
     * Returns a conservative vertical ink interval at the actual paint origin in constant time.
     *
     * Unlike [inkBounds], this ignores horizontal collapse and prepared-text rejection, which may change with the origin.
     * Sampled extrema retain the exact float addition order; legacy positions use exact double integer arithmetic.
     * An infinite envelope is conservative and never by itself excludes a line.
     *
     * @param originY current local top of the line box, including scrolling and padding before float conversion.
     * @return possible vertical ink interval, or null when this run cannot submit a vertical quad at this origin.
     */
    @JvmSynthetic
    internal fun verticalInkAt(originY: Int): MinecraftTextVerticalBounds? = verticalMetrics?.at(originY)

    /**
     * Returns conservative bounds of the finite quads submitted by this run, including its shadow overhang.
     *
     * Transparent texels may enlarge these bounds; no image scan, native lookup, or profile retention occurs.
     * Prepared-text rejection and invalid portable geometry use the same conditions as [paint].
     *
     * @return local bounds independent of the logical advance, or null when no quad is submitted.
     */
    @JvmSynthetic
    internal fun inkBounds(): MinecraftTextInkBounds? {
        if (paintPolicy.preparedTextBounds && hasPreparedTextBounds(0, 0).not()) return null
        var result: MinecraftTextInkBounds? = null

        fun include(
            left: Double,
            top: Double,
            right: Double,
            bottom: Double,
        ) {
            val next = MinecraftTextInkBounds(left, top, right, bottom)
            result = result?.union(next) ?: next
        }

        fun includeSampled(bounds: FloatRect?) {
            if (bounds != null) include(bounds.left.toDouble(), bounds.top.toDouble(), bounds.right.toDouble(), bounds.bottom.toDouble())
        }
        for (positioned in glyphs) {
            val x = positioned.x.toDouble()
            include(x, 0.0, x + 8.0, 8.0)
            if (positioned.shadow != null) include(x + 1.0, 1.0, x + 9.0, 9.0)
        }
        for (positioned in sampledGlyphs) {
            includeSampled(sampledDestination(positioned, 0, 0, false))
            if (positioned.shadow != null) includeSampled(sampledDestination(positioned, 0, 0, true))
        }
        return result
    }

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
        if (paintPolicy.preparedTextBounds && hasPreparedTextBounds(originX, originY).not()) return
        val source = IntRect(0, 0, glyphSize.width, glyphSize.height)
        for (positioned in glyphs) {
            paintLegacy(positioned, scope, source, originX, originY, null)
        }
        if (paintPolicy.interleavedShadows.not()) {
            sampledGlyphs.forEach { paintSampled(it, scope, originX, originY, true) }
        }
        sampledGlyphs.forEach { positioned ->
            if (paintPolicy.interleavedShadows) paintSampled(positioned, scope, originX, originY, true)
            paintSampled(positioned, scope, originX, originY, false)
        }
    }

    /**
     * Emits only glyph quads intersecting an existing local clip, preserving their original geometry and order.
     *
     * Positive advances and strictly increasing positions permit binary candidate selection from this run's immutable glyph list.
     * Nonpositive or non-finite advances and unsafe horizontal envelopes conservatively scan the current run.
     * No viewport, callback, historical range, or additional glyph array is retained.
     * The caller owns the actual clip; partial quads keep their original source and destination rectangles.
     *
     * @param scope active node-local paint collector, borrowed only for this call on its owner thread.
     * @param originX horizontal origin in the same local coordinates as [viewport].
     * @param originY top of the nine-pixel line box in those coordinates.
     * @param viewport half-open local clip; an empty clip emits nothing.
     * @return candidate and prepared-bounds glyph visits, including a second visit for a separate shadow pass; binary comparisons are excluded.
     * @throws ArithmeticException when an emitted integer quad cannot be represented.
     * @throws IllegalStateException when the borrowed scope is outside its callback lifetime or owner thread.
     */
    @JvmSynthetic
    internal fun paintVisible(
        scope: PaintScope,
        originX: Int,
        originY: Int,
        viewport: IntRect,
    ): Int {
        if (viewport.width == 0 || viewport.height == 0) return 0
        val legacy = legacyRange(originX, viewport)
        val range = sampledRange(originX, viewport)
        if (legacy.isEmpty() && range.isEmpty()) return 0
        var visited = 0
        if (paintPolicy.preparedTextBounds && hasPreparedTextBounds(originX, originY) { visited++ }.not()) return visited
        val source = IntRect(0, 0, glyphSize.width, glyphSize.height)
        for (index in legacy) {
            paintLegacy(glyphs[index], scope, source, originX, originY, viewport)
            visited++
        }
        if (paintPolicy.interleavedShadows.not() && sampledShadows) {
            for (index in range) {
                paintSampled(sampledGlyphs[index], scope, originX, originY, true, viewport)
                visited++
            }
        }
        for (index in range) {
            val positioned = sampledGlyphs[index]
            if (paintPolicy.interleavedShadows) paintSampled(positioned, scope, originX, originY, true, viewport)
            paintSampled(positioned, scope, originX, originY, false, viewport)
            visited++
        }
        return visited
    }

    private fun legacyRange(
        originX: Int,
        viewport: IntRect,
    ): IntRange {
        if (paintPolicy.forwardAdvances.not()) return glyphs.indices
        val start = Visibility.firstMatching(glyphs.size) { viewport.left.toLong() < originX.toLong() + glyphs[it].x + 9L }
        val end = Visibility.firstMatching(glyphs.size) { viewport.right.toLong() <= originX.toLong() + glyphs[it].x }
        return start until end
    }

    private fun sampledRange(
        originX: Int,
        viewport: IntRect,
    ): IntRange {
        val index = sampledIndex ?: return sampledGlyphs.indices
        // Preserve the painter's float operation order. Monotone extreme bearings and offsets enclose every actual quad.
        val start =
            Visibility.firstMatching(sampledGlyphs.size) {
                viewport.left.toDouble() < (originX.toFloat() + sampledGlyphs[it].x + index.maximumRight + index.maximumShadow).toDouble()
            }
        val end =
            Visibility.firstMatching(sampledGlyphs.size) {
                viewport.right.toDouble() <= (originX.toFloat() + sampledGlyphs[it].x + index.minimumLeft + 0f).toDouble()
            }
        return start until end
    }

    private fun paintLegacy(
        positioned: PositionedGlyph,
        scope: PaintScope,
        source: IntRect,
        originX: Int,
        originY: Int,
        viewport: IntRect?,
    ) {
        val glyphX = originX.toLong() + positioned.x
        val shadowLeft = glyphX + shadowOffset.x
        val shadowTop = originY.toLong() + shadowOffset.y
        if (viewport == null) {
            Math.toIntExact(glyphX)
            Math.toIntExact(shadowLeft)
            Math.toIntExact(shadowTop)
        }
        positioned.shadow?.let { shadow ->
            val right = shadowLeft + glyphSize.width
            val bottom = shadowTop + glyphSize.height
            if (viewport == null || Visibility.intersects(shadowLeft.toDouble(), shadowTop.toDouble(), right.toDouble(), bottom.toDouble(), viewport)) {
                val destination = IntRect(Math.toIntExact(shadowLeft), Math.toIntExact(shadowTop), Math.toIntExact(right), Math.toIntExact(bottom))
                scope.blitImage(shadow, source, destination)
            }
        }
        val right = glyphX + glyphSize.width
        val bottom = originY.toLong() + glyphSize.height
        if (viewport == null || Visibility.intersects(glyphX.toDouble(), originY.toDouble(), right.toDouble(), bottom.toDouble(), viewport)) {
            val destination = IntRect(Math.toIntExact(glyphX), originY, Math.toIntExact(right), Math.toIntExact(bottom))
            scope.blitImage(positioned.foreground, source, destination)
        }
    }

    private inline fun hasPreparedTextBounds(
        originX: Int,
        originY: Int,
        onVisit: () -> Unit = {},
    ): Boolean {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (positioned in sampledGlyphs) {
            onVisit()
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
            // Finite extrema can only expand; later NaN values cannot make native rejection comparisons succeed.
            if (left < right && top < bottom) return true
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
        viewport: IntRect? = null,
    ) {
        val tint = if (shadow) positioned.shadow ?: return else positioned.foreground
        val glyph = positioned.glyph
        val image = checkNotNull(glyph.image)
        // Native font atlases inset every glyph UV by one hundredth of a source texel.
        val source = FloatRect(0.01f, 0.01f, image.size.width - 0.01f, image.size.height - 0.01f)
        val destination = sampledDestination(positioned, originX, originY, shadow) ?: return
        if (viewport != null && Visibility.intersects(destination.left.toDouble(), destination.top.toDouble(), destination.right.toDouble(), destination.bottom.toDouble(), viewport).not()) return
        scope.sampledImage(image, source, destination, glyph.orientation, tint)
    }

    private fun sampledDestination(
        positioned: SampledGlyph,
        originX: Int,
        originY: Int,
        shadow: Boolean,
    ): FloatRect? {
        val glyph = positioned.glyph
        val offset = if (shadow) glyph.shadowOffset else 0f
        val left = originX + positioned.x + glyph.left + offset
        val top = originY + glyph.top + offset
        val right = originX + positioned.x + glyph.right + offset
        val bottom = originY + glyph.bottom + offset
        if (left.isFinite().not() || top.isFinite().not()) return null
        if (right.isFinite().not() || bottom.isFinite().not()) return null
        if (right <= left || bottom <= top) return null
        if ((right - left).isFinite().not() || (bottom - top).isFinite().not()) return null
        return FloatRect(left, top, right, bottom)
    }

    /**
     * Returns whether another run has the same literal, natural size, positions, and retained glyph values.
     *
     * @param other run to compare.
     * @return true when an element update is an exact retained no-op.
     */
    @Suppress("unused")
    @JvmSynthetic
    internal fun equivalentTo(other: MinecraftTextRun): Boolean = text == other.text && size == other.size && nativeWidth == other.nativeWidth && glyphs == other.glyphs && sampledGlyphs == other.sampledGlyphs && paintPolicy == other.paintPolicy

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

    private data class PaintPolicy(
        val interleavedShadows: Boolean = true,
        val preparedTextBounds: Boolean = false,
        val forwardAdvances: Boolean = true,
    )

    private object Visibility {
        /**
         * Finds the first true value of a monotonic predicate without retaining the borrowed callback.
         */
        inline fun firstMatching(
            size: Int,
            predicate: (Int) -> Boolean,
        ): Int {
            var low = 0
            var high = size
            while (low < high) {
                val middle = low + (high - low) / 2
                if (predicate(middle)) high = middle else low = middle + 1
            }
            return low
        }

        /**
         * Tests exact integer clip edges against a conservative double-precision glyph rectangle.
         */
        fun intersects(
            left: Double,
            top: Double,
            right: Double,
            bottom: Double,
            viewport: IntRect,
        ): Boolean {
            val horizontal = left < viewport.right.toDouble() && viewport.left.toDouble() < right
            val vertical = top < viewport.bottom.toDouble() && viewport.top.toDouble() < bottom
            return horizontal && vertical
        }
    }

    private data class SampledIndex(
        val minimumLeft: Float,
        val maximumRight: Float,
        val maximumShadow: Float,
    ) {
        companion object {
            /**
             * Reduces the current positioned glyphs to finite horizontal extrema without retaining the input list.
             */
            fun create(glyphs: List<SampledGlyph>): SampledIndex? {
                var minimumLeft = Float.MAX_VALUE
                var maximumRight = -Float.MAX_VALUE
                var maximumShadow = 0f
                for (positioned in glyphs) {
                    val glyph = positioned.glyph
                    if (glyph.left.isFinite().not() || glyph.right.isFinite().not()) return null
                    minimumLeft = minOf(minimumLeft, glyph.left)
                    maximumRight = maxOf(maximumRight, glyph.right)
                    if (positioned.shadow != null) maximumShadow = maxOf(maximumShadow, glyph.shadowOffset)
                }
                return SampledIndex(minimumLeft, maximumRight, maximumShadow)
            }
        }
    }

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
            val content = MinecraftTextContent.create(text, font)
            val logicalText = content.value
            val width = engine.compatibility.roundedWidth(logicalWidth(content, engine))
            val positioned = ArrayList<SampledGlyph>()
            val vertical = MinecraftTextVerticalMetrics.Builder()
            var cursor = 0f
            var forwardAdvances = true
            val ordered = if (logicalOrder) logicalGlyphs(logicalText) else engine.visualGlyphs(logicalText)
            ordered.forEach { visual ->
                require(visual.sourceIndex in logicalText.indices) { "Font backend returned a style index outside the logical text." }
                val glyph = engine.glyph(content.fontAt(visual.sourceIndex), visual.codePoint)
                if (glyph.image != null) {
                    positioned.add(SampledGlyph(cursor, glyph, foreground, shadow))
                    vertical.add(glyph, shadow != null)
                }
                val next = cursor + glyph.advance
                if (glyph.advance <= 0f || next.isFinite().not() || (cursor < next).not()) forwardAdvances = false
                cursor = next
            }
            val policy = PaintPolicy(engine.compatibility.interleavedShadows, engine.compatibility.preparedTextBounds, forwardAdvances)
            return MinecraftTextRun(text, emptyList(), IntSize(maxOf(0, width), 9), positioned, width, policy, vertical.build())
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
            content: MinecraftTextContent,
            engine: MinecraftFontEngine,
        ): Float {
            // Native Font.width measures the logical component before Arabic shaping and bidirectional ordering.
            var width = 0f
            var index = 0
            while (index < content.value.length) {
                val codePoint = content.value.codePointAt(index)
                width += engine.glyph(content.fontAt(index), codePoint).advance
                index += Character.charCount(codePoint)
            }
            return width
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
            val vertical = MinecraftTextVerticalMetrics.Builder()
            var cursor = 0
            var forwardAdvances = true
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
                    vertical.addLegacy(if (selected.shadow == null) 8 else 9)
                    if (glyph.advance <= 0) forwardAdvances = false
                    cursor = Math.addExact(cursor, glyph.advance)
                }
            }
            return MinecraftTextRun(text, positioned, IntSize(cursor, lineHeight), paintPolicy = PaintPolicy(forwardAdvances = forwardAdvances), verticalMetrics = vertical.build())
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
