package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.text.UiText
import kotlin.math.abs

/**
 * Host-owned text service borrowing either immutable compatibility glyphs or an independently owned font engine.
 * Runs detach the glyphs they need and do not retain this service or an entire UI profile.
 * The owner calls close after disposing the tree; closed services reject further text construction.
 */
@Suppress("TooManyFunctions") // Literal measurements share this owner's glyph lifetime and native rounding contract.
internal class MinecraftTextRenderer private constructor(
    private var legacyGlyphs: Map<Int, MinecraftGlyphSnapshot>?,
    private var engine: MinecraftFontEngine?,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var closed = false

    /**
     * Creates one immutable run using the same metrics and glyphs as rendering.
     *
     * @param text unresolved literal or font composition.
     * @param style selected vanilla color and shadow treatment.
     * @param enabled selects the enabled TextField tint when applicable.
     * @param font inherited font identifier, overridden by inner wrappers.
     * @param logicalOrder bypasses display shaping and reordering for the native EditBox scalar-order contract.
     * @return detached immutable text run.
     * @throws IllegalStateException after close or from another thread.
     * @throws IllegalArgumentException when compatibility glyphs cannot represent the requested text or font.
     */
    @JvmSynthetic
    internal fun create(
        text: UiText,
        style: TextStyle,
        enabled: Boolean = true,
        font: ResourceId = defaultFont,
        logicalOrder: Boolean = false,
    ): MinecraftTextRun {
        check(Thread.currentThread() === owner && closed.not()) { "Text renderer is closed or accessed from another thread." }
        val currentEngine = engine
        if (currentEngine != null) {
            val foreground =
                when (style) {
                    TextStyle.Normal -> 0xffffff
                    TextStyle.Inactive -> 0xa0a0a0
                    TextStyle.ContainerLabel -> 0x404040
                    TextStyle.TextField -> if (enabled) 0xe0e0e0 else 0x707070
                }
            val shadow = if (style == TextStyle.ContainerLabel) null else ArgbColor(0xff000000.toInt() or ((foreground and 0xfcfcfc) ushr 2))
            return MinecraftTextRun.createFonts(text, currentEngine, font, ArgbColor(0xff000000.toInt() or foreground), shadow, logicalOrder)
        }
        require(font == defaultFont) { "Custom fonts require a font-resource snapshot." }
        val glyphs = checkNotNull(legacyGlyphs)
        return when (style) {
            TextStyle.Normal -> MinecraftTextRun.createNormal(text, glyphs::getValue)
            TextStyle.Inactive -> MinecraftTextRun.createInactive(text, glyphs::getValue)
            TextStyle.ContainerLabel -> MinecraftTextRun.createContainerLabel(text, glyphs::getValue)
            TextStyle.TextField -> MinecraftTextRun.createTextField(text, enabled, glyphs::getValue)
        }
    }

    /**
     * Measures one logical scalar with the same provider and floating-point advance used by glyph rendering.
     *
     * @param font original font selection before shaping.
     * @param codePoint validated Unicode scalar, excluding hard breaks.
     * @return native signed advance without integer rounding.
     * @throws IllegalStateException after close or from another thread.
     * @throws IllegalArgumentException when a compatibility profile cannot represent the font or scalar.
     */
    @JvmSynthetic
    internal fun advance(
        font: ResourceId,
        codePoint: Int,
    ): Float {
        check(Thread.currentThread() === owner && closed.not()) { "Text renderer is closed or accessed from another thread." }
        engine?.let { return it.glyph(font, codePoint).advance }
        require(font == defaultFont && codePoint in 0x20..0x7E) { "Compatibility fonts require default-font printable ASCII." }
        return if (codePoint == 0x20) 4f else checkNotNull(legacyGlyphs).getValue(codePoint).advance.toFloat()
    }

    /**
     * Applies this profile's native integer width conversion to a logical floating-point width.
     *
     * @param width accumulated signed native advances, including native exceptional values.
     * @return native signed integer metric; layout separately projects it to a non-negative extent.
     * @throws IllegalStateException after close or from another thread.
     */
    @JvmSynthetic
    internal fun roundedWidth(width: Float): Int {
        check(Thread.currentThread() === owner && closed.not()) { "Text renderer is closed or accessed from another thread." }
        return engine?.compatibility?.roundedWidth(width) ?: width.toInt()
    }

    /**
     * Measures a literal scalar range without preparing positioned glyphs or copying the substring.
     * Font widths retain forward floating-point accumulation and native signed rounding; legacy widths retain exact integer addition.
     * The caller supplies Unicode scalar boundaries within [text].
     */
    @JvmSynthetic
    internal fun literalWidth(
        text: String,
        font: ResourceId,
        start: Int,
        end: Int,
    ): Int {
        check(Thread.currentThread() === owner && closed.not()) { "Text renderer is closed or accessed from another thread." }
        require(start in 0..end && end <= text.length)
        val currentEngine = engine
        var position = start
        if (currentEngine != null) {
            var width = 0f
            while (position < end) {
                val codePoint = text.codePointAt(position)
                width += currentEngine.glyph(font, codePoint).advance
                position += Character.charCount(codePoint)
            }
            return currentEngine.compatibility.roundedWidth(width)
        }
        require(font == defaultFont) { "Custom fonts require a font-resource snapshot." }
        val glyphs = checkNotNull(legacyGlyphs)
        var width = 0
        while (position < end) {
            val codePoint = text[position].code
            require(codePoint in 0x20..0x7E) { "Common Minecraft text supports only U+0020 through U+007E." }
            width = Math.addExact(width, if (codePoint == 0x20) 4 else glyphs.getValue(codePoint).advance)
            position += 1
        }
        require(0 <= width)
        return width
    }

    /**
     * Returns the last scalar boundary before the first prefix that exceeds [maximumWidth].
     * One forward scan preserves literal-range rounding and first-overflow behavior, including zero or negative advances.
     * The caller supplies a scalar boundary at [start]; ownership and font requirements match [literalWidth].
     */
    @JvmSynthetic
    internal fun literalEndWithin(
        text: String,
        font: ResourceId,
        start: Int,
        maximumWidth: Int,
    ): Int {
        check(Thread.currentThread() === owner && closed.not()) { "Text renderer is closed or accessed from another thread." }
        require(start in 0..text.length && 0 <= maximumWidth)
        val currentEngine = engine
        var end = start
        if (currentEngine != null) {
            var width = 0f
            while (end < text.length) {
                val codePoint = text.codePointAt(end)
                width += currentEngine.glyph(font, codePoint).advance
                if (maximumWidth < currentEngine.compatibility.roundedWidth(width)) break
                end += Character.charCount(codePoint)
            }
            return end
        }
        require(font == defaultFont) { "Custom fonts require a font-resource snapshot." }
        val glyphs = checkNotNull(legacyGlyphs)
        var width = 0
        while (end < text.length) {
            val codePoint = text[end].code
            require(codePoint in 0x20..0x7E) { "Common Minecraft text supports only U+0020 through U+007E." }
            width = Math.addExact(width, if (codePoint == 0x20) 4 else glyphs.getValue(codePoint).advance)
            require(0 <= width)
            if (maximumWidth < width) break
            end += 1
        }
        return end
    }

    /**
     * Keeps the ordinary integral-metric path within its original fixed-unit proof and two scalar scans.
     * An absolute sum at most 2^24 proves every intermediate Float sum exact, including signed advances.
     * Other metrics return null for the generalized proof without changing native rounding or suffix order.
     * The caller supplies validated single-line Unicode and a scalar boundary at [end].
     */
    @JvmSynthetic
    internal fun literalIntegralStartWithin(
        text: String,
        font: ResourceId,
        end: Int,
        maximumWidth: Int,
    ): Int? {
        check(Thread.currentThread() === owner && closed.not()) { "Text renderer is closed or accessed from another thread." }
        require(end in 0..text.length && 0 <= maximumWidth)
        var position = 0
        var absoluteWidth = 0L
        var width = 0
        while (position < end) {
            val advance = advance(font, text.codePointAt(position))
            val integral = advance.toInt()
            if (advance.isFinite().not() || advance != integral.toFloat()) return null
            absoluteWidth += abs(integral.toLong())
            if ((1L shl 24) < absoluteWidth) return null
            width += integral
            position += Character.charCount(text.codePointAt(position))
        }
        var start = 0
        while (start < end && maximumWidth < width) {
            val codePoint = text.codePointAt(start)
            width -= advance(font, codePoint).toInt()
            start += Character.charCount(codePoint)
        }
        return start
    }

    /**
     * Finds the first fitting suffix in two scalar scans when all forward Float sums are exact.
     * The range of exact prefix sums in their common power-of-two unit bounds every suffix intermediate to 2^24 units.
     * Returns null outside that bound so the caller retains native rounding and scalar-order behavior.
     * The caller supplies validated single-line Unicode and a scalar boundary at [end].
     */
    @JvmSynthetic
    internal fun literalExactStartWithin(
        text: String,
        font: ResourceId,
        end: Int,
        maximumWidth: Int,
    ): Int? {
        check(Thread.currentThread() === owner && closed.not()) { "Text renderer is closed or accessed from another thread." }
        require(end in 0..text.length && 0 <= maximumWidth)
        var position = 0
        var units = 0.0
        var quantumPower = Int.MAX_VALUE
        var previousBits = -1
        var exactWidth = 0.0
        var minimum = 0.0
        var maximum = 0.0
        while (position < end) {
            val codePoint = text.codePointAt(position)
            val advance = advance(font, codePoint)
            if (advance.isFinite().not()) return null
            val bits = advance.toRawBits() and Int.MAX_VALUE
            if (bits != 0 && bits != previousBits) {
                val exponent = bits ushr 23
                val significand = (bits and 0x7FFFFF) or if (exponent == 0) 0 else (1 shl 23)
                val power = (if (exponent == 0) -149 else exponent - 150) + Integer.numberOfTrailingZeros(significand)
                // Only a finer quantum can tighten this invocation's exact-sum bound.
                if (power < quantumPower) {
                    quantumPower = power
                    units = Math.scalb(1.0, -power)
                }
            }
            previousBits = bits
            exactWidth += advance.toDouble()
            minimum = minOf(minimum, exactWidth)
            maximum = maxOf(maximum, exactWidth)
            val range = maximum - minimum
            if ((1 shl 24).toDouble() < range * units || Float.MAX_VALUE.toDouble() < range) return null
            position += Character.charCount(codePoint)
        }
        var width = exactWidth.toFloat()
        var start = 0
        while (start < end && maximumWidth < roundedWidth(width)) {
            val codePoint = text.codePointAt(start)
            width -= advance(font, codePoint)
            start += Character.charCount(codePoint)
        }
        return start
    }

    /**
     * Preserves exact forward suffix accumulation for exceptional metrics with one glyph scan.
     * Attained Float extrema summarize candidate starts; a monotone existence search finds the first fitting scalar.
     * At most O(n log n) Float additions and O(n) temporary storage belong to this call, without text or font history.
     */
    @JvmSynthetic
    internal fun literalScalarStartWithin(
        text: String,
        font: ResourceId,
        end: Int,
        maximumWidth: Int,
    ): Int {
        check(Thread.currentThread() === owner && closed.not()) { "Text renderer is closed or accessed from another thread." }
        require(end in 0..text.length && 0 <= maximumWidth)
        val advances = FloatArray(end)
        val offsets = IntArray(Math.addExact(end, 1))
        val minima = FloatArray(offsets.size)
        val maxima = FloatArray(offsets.size)
        var count = 0
        var position = 0
        var firstWidth = 0f
        while (position < end) {
            offsets[count] = position
            val codePoint = text.codePointAt(position)
            val value = advance(font, codePoint)
            advances[count] = value
            firstWidth += value
            val minimum = minOf(minima[count], 0f) + value
            val maximum = maxOf(maxima[count], 0f) + value
            // A NaN at either attained endpoint proves that an actual suffix remains NaN through every continuation.
            minima[count + 1] = if (maximum.isNaN()) Float.NaN else minimum
            maxima[count + 1] = maximum
            count += 1
            position += Character.charCount(codePoint)
        }
        offsets[count] = end
        if (roundedWidth(firstWidth) <= maximumWidth) return 0
        if (hasFittingWidth(minima[count], maxima[count], maximumWidth).not()) return end
        var first = 1
        var last = count - 1
        while (first < last) {
            val middle = first + (last - first) / 2
            if (hasFittingSuffix(advances, minima[middle + 1], maxima[middle + 1], middle + 1, count, maximumWidth)) last = middle else first = middle + 1
        }
        return offsets[first]
    }

    /**
     * Continues exact attained prefix extrema through the remaining advances without admitting later starts.
     */
    private fun hasFittingSuffix(
        advances: FloatArray,
        initialMinimum: Float,
        initialMaximum: Float,
        nextIndex: Int,
        count: Int,
        maximumWidth: Int,
    ): Boolean {
        var minimum = initialMinimum
        var maximum = initialMaximum
        var next = nextIndex
        while (next < count && minimum.isNaN().not() && maximum.isNaN().not()) {
            minimum += advances[next]
            maximum += advances[next]
            next += 1
        }
        return hasFittingWidth(minimum, maximum, maximumWidth)
    }

    /**
     * Tests exact attained extrema, including native NaN and legacy positive-width wrapping.
     * Float addition preserves the order of non-NaN values; an endpoint NaN witnesses a permanently fitting candidate.
     * Both rounding modes are monotone below the legacy overflow jump, whose fitting upper region contains the maximum.
     */
    private fun hasFittingWidth(
        minimum: Float,
        maximum: Float,
        maximumWidth: Int,
    ): Boolean {
        if (minimum.isNaN() || maximum.isNaN()) return true
        return roundedWidth(minimum) <= maximumWidth || roundedWidth(maximum) <= maximumWidth
    }

    /**
     * Returns the first scalar whose rounded prefix midpoint lies after [localX], or the text endpoint.
     * One forward scan preserves the native signed midpoint order without copying or remeasuring prefixes.
     * The caller supplies validated single-line Unicode; ownership and font requirements match [literalWidth].
     */
    @JvmSynthetic
    internal fun literalPositionAt(
        text: String,
        font: ResourceId,
        localX: Int,
    ): Int {
        check(Thread.currentThread() === owner && closed.not()) { "Text renderer is closed or accessed from another thread." }
        if (localX <= 0) return 0
        val currentEngine = engine
        var position = 0
        var previous = 0L
        if (currentEngine != null) {
            var width = 0f
            while (position < text.length) {
                val codePoint = text.codePointAt(position)
                width += currentEngine.glyph(font, codePoint).advance
                val next = currentEngine.compatibility.roundedWidth(width).toLong()
                if (localX.toLong() < previous + Math.floorDiv(next - previous + 1L, 2L)) return position
                previous = next
                position += Character.charCount(codePoint)
            }
        } else {
            require(font == defaultFont) { "Custom fonts require a font-resource snapshot." }
            val glyphs = checkNotNull(legacyGlyphs)
            var width = 0
            while (position < text.length) {
                val codePoint = text[position].code
                require(codePoint in 0x20..0x7E) { "Common Minecraft text supports only U+0020 through U+007E." }
                width = Math.addExact(width, if (codePoint == 0x20) 4 else glyphs.getValue(codePoint).advance)
                require(0 <= width)
                val next = width.toLong()
                if (localX.toLong() < previous + Math.floorDiv(next - previous + 1L, 2L)) return position
                previous = next
                position += 1
            }
        }
        return text.length
    }

    override fun close() {
        check(Thread.currentThread() === owner) { "Text renderer is confined to its owner thread." }
        if (closed) return
        closed = true
        legacyGlyphs = null
        val retained = engine
        engine = null
        retained?.close()
    }

    /**
     * Constructs independent owner-thread text services.
     */
    companion object {
        /**
         * Immutable default resource font requested when a component has no explicit wrapper.
         */
        @get:JvmSynthetic
        internal val defaultFont: ResourceId = ResourceId("minecraft", "default")

        /**
         * Creates a distinct owner-thread compatibility service borrowing immutable glyphs and no UI profile.
         * The caller owns the returned service and must close it after disposing its tree.
         *
         * @param glyphs complete immutable compatibility table, retained without modification until close.
         * @return a new service that initializes no native resources.
         */
        @JvmSynthetic
        internal fun legacy(glyphs: Map<Int, MinecraftGlyphSnapshot>): MinecraftTextRenderer = MinecraftTextRenderer(glyphs, null)

        /**
         * Transfers an independently opened font engine to a new text service on the current owner thread.
         * The caller must close the returned service, which closes the engine after the retained tree is disposed.
         *
         * @param engine exclusively transferred font engine opened on this thread.
         * @return a new service with terminal ownership of the engine.
         */
        @JvmSynthetic
        internal fun fonts(engine: MinecraftFontEngine): MinecraftTextRenderer = MinecraftTextRenderer(null, engine)
    }
}
