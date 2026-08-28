package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont

/**
 * Immutable logical Unicode text and original UTF-16 font provenance shared by line layout and glyph shaping.
 *
 * Construction resolves only literal composition, never translations or platform payloads.
 * Every scalar keeps its innermost font selection; slicing cannot split a surrogate pair or change that selection.
 * The value owns no renderer, profile, native resource, or historical text.
 *
 * @property text complete original value retained for semantics, including hard breaks and omitted lines.
 * @property value flattened logical text before shaping or hard-break normalization.
 */
internal class MinecraftTextContent private constructor(
    @get:JvmSynthetic
    internal val text: UiText,
    @get:JvmSynthetic
    internal val value: String,
    private val fonts: List<ResourceId>,
    private val inheritedFont: ResourceId,
) {
    /**
     * Returns the original font at a UTF-16 scalar boundary, or the innermost effective wrapper font for an empty value.
     * A fully empty concatenation inherits the effective selection of its first logical part.
     *
     * @param offset valid character offset, with zero accepted for empty text.
     * @return immutable resource identifier; no resource lookup occurs.
     * @throws IllegalArgumentException when the offset is not a scalar boundary within the value.
     */
    @JvmSynthetic
    internal fun fontAt(offset: Int): ResourceId {
        require(offset == scalarBoundary(offset) && (offset < value.length || value.isEmpty())) {
            "A font offset must identify an original Unicode scalar."
        }
        return if (value.isEmpty()) inheritedFont else fonts[offset]
    }

    /**
     * Compares complete semantic text and effective original font provenance, including an empty value's inherited selection.
     *
     * @param other detached current content from a replacement description.
     * @return true when reusing measured presentation and semantics is safe.
     */
    @JvmSynthetic
    internal fun equivalentTo(other: MinecraftTextContent): Boolean = text == other.text && value == other.value && fonts == other.fonts && inheritedFont == other.inheritedFont

    /**
     * Clamps an offset to the preceding scalar boundary of this logical value.
     *
     * @param offset possibly out-of-range UTF-16 offset.
     * @return a valid insertion position from zero through the string length.
     */
    @JvmSynthetic
    internal fun scalarBoundary(offset: Int): Int {
        val bounded = offset.coerceIn(0, value.length)
        return if (bounded < value.length && 0 < bounded && value[bounded].isLowSurrogate()) bounded - 1 else bounded
    }

    /**
     * Copies one scalar-aligned logical range into a minimal font-preserving text composition.
     *
     * @param start inclusive original UTF-16 offset.
     * @param end exclusive original UTF-16 offset.
     * @return detached literal or font composition without a reference to the original full text.
     * @throws IllegalArgumentException when a bound is invalid or splits a scalar.
     */
    @JvmSynthetic
    internal fun slice(
        start: Int,
        end: Int,
    ): UiText {
        require(start == scalarBoundary(start) && end == scalarBoundary(end) && start <= end) {
            "Text slices require ordered Unicode scalar boundaries."
        }
        if (start == end) {
            val offset = if (start == value.length && value.isNotEmpty()) value.offsetByCodePoints(start, -1) else start
            return UiText.Literal("").withFont(fontAt(offset))
        }
        val parts = ArrayList<UiText>()
        var first = start
        while (first < end) {
            val font = fonts[first]
            var last = first + Character.charCount(value.codePointAt(first))
            while (last < end && fonts[last] == font) last += Character.charCount(value.codePointAt(last))
            val literal = UiText.Literal(value.substring(first, last))
            parts.add(if (font == MinecraftTextRenderer.defaultFont) literal else literal.withFont(font))
            first = last
        }
        return if (parts.size == 1) parts[0] else UiText.Concatenated(parts)
    }

    /**
     * Factories and mandatory Unicode line-break recognition used by all runtime text layouts.
     */
    companion object {
        /**
         * Validates and flattens one value while retaining original scalar font provenance.
         *
         * @param text literal, nested font wrapper, or concatenation.
         * @param font font inherited by literals without an inner wrapper.
         * @param multiline permits mandatory Unicode hard breaks when true.
         * @return detached immutable content without any font-resource ownership.
         * @throws IllegalArgumentException for malformed Unicode, legacy formatting, unresolved text, or disallowed hard breaks.
         */
        @JvmSynthetic
        internal fun create(
            text: UiText,
            font: ResourceId = MinecraftTextRenderer.defaultFont,
            multiline: Boolean = false,
        ): MinecraftTextContent {
            val value = StringBuilder()
            val fonts = ArrayList<ResourceId>()
            append(text, font, multiline, value, fonts)
            return MinecraftTextContent(text, value.toString(), fonts.toList(), emptyFont(text, font))
        }

        /**
         * Recognizes Unicode mandatory line breaks; callers consume CRLF as a single break.
         *
         * @param codePoint decoded Unicode scalar.
         * @return true for LF, VT, FF, CR, NEL, LS, or PS.
         */
        @JvmSynthetic
        internal fun isHardBreak(codePoint: Int): Boolean =
            when (codePoint) {
                0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029 -> true
                else -> false
            }

        private fun emptyFont(
            text: UiText,
            inherited: ResourceId,
        ): ResourceId =
            when (text) {
                is UiText.WithFont -> emptyFont(text.text, text.font)
                is UiText.Concatenated -> emptyFont(text.parts.first(), inherited)
                else -> inherited
            }

        private fun append(
            text: UiText,
            font: ResourceId,
            multiline: Boolean,
            value: StringBuilder,
            fonts: MutableList<ResourceId>,
        ) {
            when (text) {
                is UiText.Literal -> {
                    var offset = 0
                    while (offset < text.value.length) {
                        val codePoint = text.value.codePointAt(offset)
                        require((codePoint in 0xD800..0xDFFF).not() && codePoint != 0xA7 && (multiline || isHardBreak(codePoint).not())) {
                            "Text requires well-formed Unicode without legacy formatting; single-line text also rejects hard breaks."
                        }
                        val count = Character.charCount(codePoint)
                        value.appendCodePoint(codePoint)
                        repeat(count) { fonts.add(font) }
                        offset += count
                    }
                }

                is UiText.WithFont -> {
                    append(text.text, text.font, multiline, value, fonts)
                }

                is UiText.Concatenated -> {
                    text.parts.forEach { append(it, font, multiline, value, fonts) }
                }

                is UiText.Translated, is UiText.Platform -> {
                    throw IllegalArgumentException("Common Minecraft text requires resolved literal text.")
                }
            }
        }
    }
}
