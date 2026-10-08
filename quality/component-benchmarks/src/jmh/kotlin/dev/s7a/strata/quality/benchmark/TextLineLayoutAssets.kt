package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.Shape
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
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Prepared immutable resource/metric inputs for one retained layout fixture.
 * Synthetic scalar advances exercise the real engine without a game, graphics context or native face.
 * Backend counters describe current owned resources and never enter a measured operation as an oracle.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class TextLineLayoutAssets(
    private val shape: Shape,
) {
    /**
     * Fixed font identities whose prepared bytes are part of the fixture code-source archive.
     */
    internal val defaultFont = ResourceId("minecraft", "default")

    /**
     * Distinct metric face used by mixed display spans and whole-editor font changes.
     */
    internal val alternateFont = ResourceId("strata_benchmark", "layout_alt")

    /**
     * Actual selected native rounding contract, independently applied to forward reference prefixes.
     */
    internal val compatibility: MinecraftFontCompatibility = ComponentFontAssets.snapshot().compatibility.copy(saturatingCeil = true)

    private val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))

    /**
     * Complete host profile prepared before timing.
     */
    internal val profile: MinecraftUiProfile = profile()

    /**
     * Number of currently open backend owners.
     */
    internal var backends = 0
        private set

    /**
     * Number of currently open faces across this host's prepared fonts.
     */
    internal var faces = 0
        private set

    /**
     * Calls reaching a synthetic face, used only in untimed clean/control validation.
     */
    internal var glyphCalls = 0
        private set

    /**
     * Returns prepared logical content; the caller normalizes editor hard breaks explicitly before timing.
     */
    internal fun value(): String =
        when (shape) {
            Shape.Empty -> ""
            Shape.Short -> "A🙂BZ"
            Shape.Bmp128 -> "AB".repeat(64)
            Shape.Bmp32767, Shape.EllipsisFirst, Shape.EllipsisMiddle, Shape.EllipsisLast -> "A".repeat(32_767)
            Shape.Supplementary32767Scalars -> "🙂".repeat(32_767)
            Shape.Wrapped -> "AB🙂CD ".repeat(4681)
            Shape.HardBreaks -> List(4096) { "AB🙂C" }.joinToString("\r\n")
            Shape.Signed, Shape.ZeroAdvance -> "AB".repeat(16_383) + "A"
            Shape.MixedFonts -> "AB🙂C ".repeat(5461) + "A"
            Shape.ExceptionalMetrics -> "AB🙂CDZ"
            Shape.DisplayOrder -> "אב🙂CD".repeat(16)
        }

    /**
     * Independent original logical advance for a supplied scalar/font, without calling the measured renderer.
     */
    internal fun advance(
        codePoint: Int,
        font: ResourceId,
    ): Float =
        when (shape) {
            Shape.Signed -> if (codePoint == 'B'.code) -2f else 3f
            Shape.ZeroAdvance -> if (codePoint == 'B'.code) 0f else 3f
            Shape.ExceptionalMetrics ->
                when (codePoint) {
                    'A'.code -> 1.25f
                    'B'.code -> -0.1f
                    0x1F642 -> 0f
                    'C'.code -> 3e9f
                    'D'.code -> -3e9f
                    else -> Float.NaN
                }
            else -> if (font == alternateFont) 4f else 3f
        }

    /**
     * Opens one host-owned synthetic backend; font/ordering work remains required and common to both runtime sides.
     */
    internal fun backend(): MinecraftFontBackend {
        backends += 1
        return object : MinecraftFontBackend {
            override fun decodePng(bytes: ByteArray): DrawImage = error("The line-layout fixture has no bitmap provider")

            override fun openTrueType(
                bytes: ByteArray,
                settings: MinecraftTrueTypeSettings,
            ): MinecraftTrueTypeFace {
                faces += 1
                val font = if (settings.size == 4f) alternateFont else defaultFont
                return object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph {
                        glyphCalls += 1
                        val invisible = shape === Shape.ExceptionalMetrics
                        return MinecraftFontGlyph(advance(codePoint, font), 0f, 0f, if (invisible) 0f else 1f, if (invisible) 0f else 1f, if (invisible) null else image)
                    }

                    override fun close() {
                        faces -= 1
                    }
                }
            }

            override fun visualGlyphs(
                text: String,
                rightToLeft: Boolean,
            ): List<MinecraftVisualGlyph> {
                val logical = super.visualGlyphs(text, rightToLeft)
                return if (shape === Shape.DisplayOrder) logical.asReversed() else logical
            }

            override fun close() {
                backends -= 1
            }
        }
    }

    private fun profile(): MinecraftUiProfile {
        val source =
            MinecraftMemoryFontAssetSource(
                "line-layout-metrics-v1",
                mapOf(
                    "assets/minecraft/font/default.json" to """{"providers":[{"type":"ttf","file":"strata_benchmark:layout.ttf","size":3}]}""".toByteArray(Charsets.UTF_8),
                    "assets/strata_benchmark/font/layout_alt.json" to """{"providers":[{"type":"ttf","file":"strata_benchmark:layout.ttf","size":4}]}""".toByteArray(Charsets.UTF_8),
                    "assets/strata_benchmark/font/layout.ttf" to byteArrayOf(1),
                ),
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(source), compatibility)
        check(snapshot.diagnostics.isEmpty())
        return ComponentProfile.create(snapshot)
    }
}
