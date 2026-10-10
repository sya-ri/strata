package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftGlyphChannel
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import org.lwjgl.util.freetype.FT_Face
import org.lwjgl.util.freetype.FreeType
import kotlin.math.abs

/**
 * Independent native measurement and scalar byte-address oracle; never invokes the runtime pixel converter.
 * Native calls and complete reference images are untimed and use a separate owner from the sampled face.
 */
internal object FreeTypeScalarOracle {
    /**
     * Requires the proposed scalar to be present, nonempty, grayscale and admitted on the actual native runtime.
     * Returns original Float metrics and every independently addressed source pixel, plus observed native pitch.
     */
    internal fun glyph(fixture: FreeTypeGlyphFixture): Reference =
        LwjglMinecraftFontBackendFactory.open(FontRasterAssets.compatibility(MinecraftTrueTypeRasterizer.FreeType)).use { backend ->
            backend.openTrueType(FreeTypeGrayscaleAssets.font(), fixture.settings).use { managed ->
                val delegate = FreeTypeGrayscaleAssets.delegate(managed)
                val face = delegate.javaClass
                    .getDeclaredField("face")
                    .apply { isAccessible = true }
                    .get(delegate) as FT_Face
                val index = FreeType.FT_Get_Char_Index(face, fixture.scalar.toLong())
                check(index != 0) { "The frozen scalar is absent" }
                check(FreeType.FT_Load_Glyph(face, index, FreeType.FT_LOAD_NO_BITMAP or FreeType.FT_LOAD_BITMAP_METRICS_ONLY) == 0)
                val measured = checkNotNull(face.glyph())
                val advance = measured.advance().x().toFloat() / 64f / fixture.settings.oversample
                val width = measured.bitmap().width()
                val height = measured.bitmap().rows()
                check(0 < width && 0 < height && width <= 256 && height <= 256) { "The frozen real glyph is empty or atlas-rejected" }
                MinecraftFontLoadLimits().requireImageSize(width, height)
                val left = measured.bitmap_left() / fixture.settings.oversample
                val top = 7f - measured.bitmap_top() / fixture.settings.oversample
                val right = left + width / fixture.settings.oversample
                val bottom = top + height / fixture.settings.oversample
                check(FreeType.FT_Load_Glyph(face, index, FreeType.FT_LOAD_BITMAP_METRICS_ONLY) == 0)
                val prospective = checkNotNull(face.glyph()).bitmap()
                MinecraftFontLoadLimits().requireImageSize(prospective.width(), prospective.rows())
                check(prospective.width() == width && prospective.rows() == height)
                check(FreeType.FT_Load_Glyph(face, index, FreeType.FT_LOAD_RENDER) == 0)
                val rendered = checkNotNull(face.glyph()).bitmap()
                check(rendered.pixel_mode().toInt() == FreeType.FT_PIXEL_MODE_GRAY && rendered.width() == width && rendered.rows() == height)
                val pitch = rendered.pitch()
                val stride = abs(pitch.toLong())
                check(width <= stride && stride <= Int.MAX_VALUE)
                val source = checkNotNull(rendered.buffer(Math.multiplyExact(stride.toInt(), height)))
                val pixels =
                    IntArray(Math.multiplyExact(width, height)) { offset ->
                        val row = offset / width
                        val column = offset % width
                        val physical = if (0 <= pitch) row else height - row - 1
                        val value = source[physical * stride.toInt() + column].toInt() and 0xff
                        (value shl 24) or (value shl 16) or (value shl 8) or value
                    }
                Reference(MinecraftFontGlyph(advance, left, top, right, bottom, createDrawImage(IntSize(width, height), pixels), MinecraftGlyphChannel.Intensity), pitch)
            }
        }

    /**
     * Detached oracle output, safe after the independent native bitmap, face and backend have closed.
     *
     * @property glyph exact native reference metrics and complete scalar-converted pixels.
     * @property pitch actual observed native pitch, without a synthetic layout assumption.
     */
    internal data class Reference(
        val glyph: MinecraftFontGlyph,
        val pitch: Int,
    )
}
