package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Consumer
import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Shape
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont

/**
 * Compiled deterministic logical structures, source-backed fonts and shaping inputs prepared outside sampling.
 * Glyphs use detached one-pixel white ink and independently declared signed/native numeric advances.
 * No native Minecraft font distribution or native timing behavior is assumed.
 */
internal class TextProvenanceAssets(
    internal val shape: Shape,
    consumer: Consumer,
) {
    /**
     * Typed immutable resource-font families; marker bytes are decoded only at the CPU backend boundary.
     */
    internal enum class Family(
        internal val id: ResourceId,
        internal val advance: Float,
    ) {
        First(ResourceId("provenance", "first"), 3f),
        Second(ResourceId("provenance", "second"), 5f),
    }

    /**
     * Source values are original Unicode; TextArea uses its public normalized LF representation.
     */
    internal val values: List<String> =
        sourceValue().let { source ->
            val initial =
                if (consumer === Consumer.TextArea) {
                    source.replace("\r\n", "\n").map { character -> if (character in listOf('\r', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029')) '\n' else character }.joinToString("")
                } else {
                    source
                }
            listOf(initial, if (initial.isEmpty()) "Z" else initial.substring(0, initial.offsetByCodePoints(initial.length, -1)) + "Z")
        }

    /**
     * Prepared original, edited and font-only replacement compositions; no strings or wrappers are built in input timing.
     */
    internal val texts: List<UiText> = listOf(styled(values[0], false, consumer), styled(values[1], false, consumer), styled(values[0], true, consumer))

    /**
     * Fully resolved synthetic compatibility, shared exactly by both runtime variants.
     */
    internal val compatibility = MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84, saturatingCeil = true)

    /**
     * Shared detached source image, whose pixels remain valid after every fixture backend closes.
     */
    internal val image: DrawImage = createDrawImage(IntSize(1, 1), intArrayOf(-1))

    private val visualInputs = values.associateWith(::visual)

    private val snapshot =
        MinecraftFontSnapshot.load(
            listOf(
                MinecraftMemoryFontAssetSource(
                    "compiled-text-provenance-v1",
                    mapOf(
                        "assets/minecraft/font/default.json" to """{"providers":[{"type":"reference","id":"provenance:first"}]}""".encodeToByteArray(),
                        "assets/provenance/font/first.json" to """{"providers":[{"type":"ttf","file":"provenance:first.ttf","size":3}]}""".encodeToByteArray(),
                        "assets/provenance/font/second.json" to """{"providers":[{"type":"ttf","file":"provenance:second.ttf","size":5}]}""".encodeToByteArray(),
                        "assets/provenance/font/first.ttf" to byteArrayOf(Family.First.ordinal.toByte()),
                        "assets/provenance/font/second.ttf" to byteArrayOf(Family.Second.ordinal.toByte()),
                    ),
                ),
            ),
            compatibility,
        )

    /**
     * Complete real profile with all inherited public component resources.
     */
    internal val profile: MinecraftUiProfile = ComponentProfile.create(snapshot)

    /**
     * Actual source calls and resource counts are inspected only by untimed acceptance.
     */
    internal var glyphCalls: Long = 0
        private set

    /**
     * Number of opened CPU backends, allowing independent host and direct-layout owners.
     */
    internal var backends: Int = 0
        private set

    /**
     * Number of still-owned faces, including failed construction and layout cleanup.
     */
    internal var faces: Int = 0
        private set

    /**
     * Number of terminal backend releases.
     */
    internal var releases: Int = 0
        private set

    /**
     * Creates a separately owned backend; a closed owner never invalidates detached glyphs or source text.
     */
    internal fun backend(): MinecraftFontBackend {
        backends++
        return object : MinecraftFontBackend {
            private var closed = false

            override fun decodePng(bytes: ByteArray): DrawImage = error("The provenance fixture admits only prepared TrueType fonts.")

            override fun visualGlyphs(
                text: String,
                rightToLeft: Boolean,
            ): List<MinecraftVisualGlyph> = visualInputs[text] ?: visual(text)

            override fun openTrueType(
                bytes: ByteArray,
                settings: MinecraftTrueTypeSettings,
            ): MinecraftTrueTypeFace {
                val family = Family.entries[bytes.single().toInt()]
                faces++
                return object : MinecraftTrueTypeFace {
                    private var released = false

                    override fun glyph(codePoint: Int): MinecraftFontGlyph {
                        glyphCalls++
                        return MinecraftFontGlyph(advance(family, codePoint), 0f, 0f, 1f, 1f, image)
                    }

                    override fun close() {
                        if (released) return
                        released = true
                        faces--
                    }
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                releases++
            }
        }
    }

    /**
     * Independent expected scalar metric used by dense provenance and pixel oracles, without target lookups.
     */
    internal fun advance(
        font: ResourceId,
        scalar: Int,
    ): Float = advance(Family.entries.single { it.id == font }, scalar)

    private fun advance(
        family: Family,
        scalar: Int,
    ): Float =
        when (shape) {
            Shape.Signed -> if (scalar == 'B'.code) -family.advance else family.advance
            Shape.Zero -> 0f
            Shape.Exceptional ->
                when (scalar) {
                    'B'.code -> Float.NaN
                    'C'.code -> Float.POSITIVE_INFINITY
                    'D'.code -> Float.NEGATIVE_INFINITY
                    else -> family.advance
                }

            else -> family.advance
        }

    private fun sourceValue(): String =
        when (shape) {
            Shape.Empty -> ""
            Shape.Short -> "A🙂B"
            Shape.Single128, Shape.Sparse128, Shape.Dense128 -> "AB".repeat(64)
            Shape.Single16384, Shape.Sparse16384, Shape.Dense16384 -> "AB".repeat(8192)
            Shape.EqualAdjacent -> "AB🙂日".repeat(128)
            Shape.NestedEmpty -> "A🙂B日".repeat(128)
            Shape.Supplementary -> "🙂".repeat(8192)
            Shape.MixedBreaks -> "A🙂\r\n日\u000B\u000C\u0085\u2028\u2029B\n".repeat(128)
            Shape.Wrapped -> "AB 🙂日 ".repeat(2340) + "ABCD"
            Shape.DisplayIndices -> "A🙂日אבالعربية ".repeat(256)
            Shape.Signed, Shape.Zero -> "AB".repeat(8192)
            Shape.Exceptional -> "A🙂BCD"
        }

    private fun styled(
        value: String,
        flipped: Boolean,
        consumer: Consumer,
    ): UiText {
        if (consumer === Consumer.TextArea) return UiText.Literal(value)
        if (shape === Shape.Single128 || shape === Shape.Single16384 || shape === Shape.Empty || shape === Shape.Short || shape === Shape.Exceptional) {
            return UiText.Literal(value).withFont(selected(Family.First, flipped).id)
        }
        if (shape === Shape.Sparse128 || shape === Shape.Sparse16384) {
            val spans = ArrayList<UiText>()
            var first = 0
            while (first < value.length) {
                val within = first % 512
                val family = if (within < 64) Family.Second else Family.First
                val end = minOf(value.length, first + if (within < 64) 64 - within else 512 - within)
                spans.add(UiText.Literal(value.substring(first, end)).withFont(selected(family, flipped).id))
                first = end
            }
            return UiText.Concatenated(spans)
        }
        val parts = ArrayList<UiText>()
        if (shape === Shape.NestedEmpty) parts.add(UiText.Literal("").withFont(selected(Family.Second, flipped).id))
        var offset = 0
        var scalarIndex = 0
        while (offset < value.length) {
            val scalar = value.codePointAt(offset)
            val next = offset + Character.charCount(scalar)
            val family =
                when (shape) {
                    Shape.Dense128, Shape.Dense16384, Shape.NestedEmpty, Shape.DisplayIndices -> if (scalarIndex % 2 == 0) Family.First else Family.Second
                    Shape.Sparse128, Shape.Sparse16384 -> if (scalarIndex % 512 < 64) Family.Second else Family.First
                    Shape.EqualAdjacent -> Family.First
                    else -> if (scalarIndex % 128 == 0) Family.Second else Family.First
                }
            val id = selected(family, flipped).id
            val identifier = if (shape === Shape.EqualAdjacent) ResourceId(id.namespace, id.path) else id
            val part = UiText.Literal(value.substring(offset, next)).withFont(identifier)
            parts.add(if (shape === Shape.NestedEmpty) UiText.concat(UiText.Literal(""), part, UiText.Literal("").withFont(selected(Family.Second, flipped).id)).withFont(selected(Family.First, flipped).id) else part)
            offset = next
            scalarIndex++
        }
        if (parts.isEmpty()) return UiText.Literal("").withFont(selected(Family.First, flipped).id)
        return UiText.Concatenated(parts)
    }

    private fun selected(
        family: Family,
        flipped: Boolean,
    ): Family = if (flipped) Family.entries.single { it !== family } else family

    /**
     * Returns exact source-index fixtures; every trace is independent of the runtime's provenance query.
     */
    internal fun visual(value: String): List<MinecraftVisualGlyph> {
        val logical =
            buildList {
                var offset = 0
                while (offset < value.length) {
                    add(MinecraftVisualGlyph(value.codePointAt(offset), offset))
                    offset += Character.charCount(value.codePointAt(offset))
                }
            }
        if (shape !== Shape.DisplayIndices || logical.isEmpty()) return logical
        return logical.asReversed() + listOf(logical.first(), logical[logical.size / 2], logical.first())
    }
}
