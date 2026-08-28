package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.freetype.FT_Bitmap
import org.lwjgl.util.freetype.FT_Face
import org.lwjgl.util.freetype.FT_Vector
import org.lwjgl.util.freetype.FreeType
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns a FreeType library, face, and copied font buffer for the later supported Minecraft provider contract.
 * This separate class is loaded only for FreeType targets, so STB-only game classpaths need no FreeType binding.
 * Calls are confined by the backend; glyphs own detached pixels and close releases allocations on success and failure.
 *
 * @param bytes borrowed TrueType bytes copied before native use.
 * @param settings immutable native provider settings.
 * @param limits immutable image-allocation ceilings retained until this face is closed.
 */
@Suppress("TooGenericExceptionCaught")
internal class FreeTypeMinecraftFontFace(
    bytes: ByteArray,
    private val settings: MinecraftTrueTypeSettings,
    private val limits: MinecraftFontLoadLimits = MinecraftFontLoadLimits(),
) : MinecraftTrueTypeFace {
    private var library = 0L
    private var memory: ByteBuffer? = null
    private var face: FT_Face? = null

    init {
        try {
            val copied = MemoryUtil.memAlloc(bytes.size)
            memory = copied
            copied.put(bytes).flip()
            MemoryStack.stackPush().use { stack ->
                val pointer = stack.mallocPointer(1)
                checkError(FreeType.FT_Init_FreeType(pointer), "Initialize FreeType")
                library = pointer[0]
                checkError(FreeType.FT_New_Memory_Face(library, copied, 0L, pointer), "Open TrueType face")
                val opened = FT_Face.create(pointer[0])
                face = opened
                val nativeFormat = FreeType.FT_Get_Font_Format(opened)
                val format = FontFormat.entries.firstOrNull { it.nativeName == nativeFormat }
                require(format == FontFormat.TrueType) { "The ttf provider requires a TrueType font." }
                checkError(FreeType.FT_Select_Charmap(opened, FreeType.FT_ENCODING_UNICODE), "Select Unicode charmap")
                val size = (settings.size * settings.oversample).roundToInt()
                // Native providers deliberately retain the face after a failed pixel-size request.
                FreeType.FT_Set_Pixel_Sizes(opened, size, size)
                val shift =
                    FT_Vector.malloc(stack).set(
                        (settings.shiftX * settings.oversample * 64f).roundToInt().toLong(),
                        (-settings.shiftY * settings.oversample * 64f).roundToInt().toLong(),
                    )
                FreeType.FT_Set_Transform(opened, null, shift)
            }
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override fun glyph(codePoint: Int): MinecraftFontGlyph? {
        val opened = checkNotNull(face) { "Font face is closed." }
        val index = FreeType.FT_Get_Char_Index(opened, codePoint.toLong())
        if (index == 0) return null
        checkError(FreeType.FT_Load_Glyph(opened, index, FreeType.FT_LOAD_NO_BITMAP or FreeType.FT_LOAD_BITMAP_METRICS_ONLY), "Measure glyph")
        val slot = checkNotNull(opened.glyph()) { "FreeType returned no glyph slot." }
        val advance = slot.advance().x().toFloat() / 64f / settings.oversample
        val bitmap = slot.bitmap()
        val width = bitmap.width()
        val height = bitmap.rows()
        if (width <= 0 || height <= 0) return MinecraftFontGlyph(advance, 0f, 0f, 0f, 0f, null)
        val left = slot.bitmap_left() / settings.oversample
        val top = 7f - slot.bitmap_top() / settings.oversample
        val metrics = TrueTypeGlyphMetrics(advance, left, top, left + width / settings.oversample, top + height / settings.oversample, IntSize(width, height))
        return metrics.rasterize(limits) {
            preflightRaster(opened, index, width, height)
            checkError(FreeType.FT_Load_Glyph(opened, index, FreeType.FT_LOAD_RENDER), "Render glyph")
            pixels(checkNotNull(opened.glyph()).bitmap(), width, height)
        }
    }

    private fun preflightRaster(
        opened: FT_Face,
        index: Int,
        width: Int,
        height: Int,
    ) {
        // The final native load may select an embedded strike that the outline measurement ignored.
        // Inspect the same selection without allocating pixels before retaining the original render flags.
        checkError(FreeType.FT_Load_Glyph(opened, index, FreeType.FT_LOAD_BITMAP_METRICS_ONLY), "Inspect glyph raster")
        val prospective = checkNotNull(opened.glyph()).bitmap()
        limits.requireImageSize(prospective.width(), prospective.rows())
        require(prospective.width() == width && prospective.rows() == height) { "FreeType glyph dimensions changed during rasterization." }
    }

    private fun pixels(
        rendered: FT_Bitmap,
        width: Int,
        height: Int,
    ): DrawImage {
        require(rendered.pixel_mode().toInt() == FreeType.FT_PIXEL_MODE_GRAY) { "The ttf provider requires grayscale glyphs." }
        require(rendered.width() == width && rendered.rows() == height) { "FreeType glyph dimensions changed during rasterization." }
        val pitch = rendered.pitch()
        val stride = abs(pitch.toLong())
        require(width <= stride && stride <= Int.MAX_VALUE) { "FreeType returned an invalid glyph stride." }
        val rowStride = stride.toInt()
        val buffer = checkNotNull(rendered.buffer(Math.multiplyExact(rowStride, height))) { "FreeType returned no glyph pixels." }
        val pixels =
            IntArray(Math.multiplyExact(width, height)) { offset ->
                val row = offset / width
                val physicalRow = if (0 <= pitch) row else height - row - 1
                (buffer[physicalRow * rowStride + offset % width].toInt() and 0xff) * 0x01010101
            }
        return createDrawImage(IntSize(width, height), pixels)
    }

    override fun close() {
        val currentFace = face
        val currentLibrary = library
        val currentMemory = memory
        face = null
        library = 0L
        memory = null
        try {
            if (currentFace != null) checkError(FreeType.FT_Done_Face(currentFace), "Release font face")
        } finally {
            try {
                if (currentLibrary != 0L) checkError(FreeType.FT_Done_FreeType(currentLibrary), "Release FreeType library")
            } finally {
                MemoryUtil.memFree(currentMemory)
            }
        }
    }

    private fun checkError(
        error: Int,
        operation: String,
    ) {
        check(error == 0) { "$operation failed with FreeType error $error." }
    }

    private enum class FontFormat(
        val nativeName: String,
    ) {
        TrueType("TrueType"),
    }
}
