package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTruetype
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.IntBuffer
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Owns copied sfnt bytes and an STB face using the earlier supported Minecraft provider's metrics.
 * Calls are confined by the owning backend; glyph pixels are detached before returning and closure is idempotent.
 * Construction frees every allocation if initialization fails.
 * Glyph lookup rejects non-finite or out-of-range native integer conversions before entering STB's undefined conversion domain.
 * This safety rejection does not claim parity for numeric inputs whose C conversion is undefined.
 *
 * @param bytes borrowed TrueType bytes copied before native use.
 * @param settings immutable native provider settings.
 */
@Suppress("TooGenericExceptionCaught")
internal class StbMinecraftFontFace(
    bytes: ByteArray,
    private val settings: MinecraftTrueTypeSettings,
) : MinecraftTrueTypeFace {
    private var memory: ByteBuffer? = null
    private var font: STBTTFontinfo? = null
    private val shiftX = settings.shiftX * settings.oversample
    private val shiftY = settings.shiftY * settings.oversample
    private val pointScale: Float
    private val ascent: Float

    init {
        try {
            require(12 <= bytes.size) { "TrueType data is truncated." }
            val copied = MemoryUtil.memAlloc(bytes.size)
            memory = copied
            copied.put(bytes).flip()
            val info = STBTTFontinfo.malloc()
            font = info
            require(STBTruetype.stbtt_InitFont(info, copied)) { "Cannot initialize TrueType font." }
            pointScale = STBTruetype.stbtt_ScaleForPixelHeight(info, settings.size * settings.oversample)
            ascent =
                MemoryStack.stackPush().use { stack ->
                    val value = stack.mallocInt(1)
                    STBTruetype.stbtt_GetFontVMetrics(info, value, null, null)
                    value[0] * pointScale
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
        val info = checkNotNull(font) { "Font face is closed." }
        val index = STBTruetype.stbtt_FindGlyphIndex(info, codePoint)
        if (index == 0) return null
        return MemoryStack.stackPush().use { stack ->
            val advance = stack.mallocInt(1)
            val bearing = stack.mallocInt(1)
            val x0 = stack.mallocInt(1)
            val y0 = stack.mallocInt(1)
            val x1 = stack.mallocInt(1)
            val y1 = stack.mallocInt(1)
            STBTruetype.stbtt_GetGlyphHMetrics(info, index, advance, bearing)
            bitmapBox(info, index, x0, y0, x1, y1)
            val width = x1[0] - x0[0]
            val height = y1[0] - y0[0]
            val cursorAdvance = advance[0] * pointScale / settings.oversample
            if (width <= 0 || height <= 0) return@use MinecraftFontGlyph(cursorAdvance, 0f, 0f, 0f, 0f, null)
            val left = (bearing[0] * pointScale + x0[0] + shiftX) / settings.oversample
            val nativeTop = (ascent + y0[0] + shiftY) / settings.oversample
            val nativeBottom = nativeTop + height / settings.oversample
            val metrics = TrueTypeGlyphMetrics(cursorAdvance, left, nativeTop - 3f, left + width / settings.oversample, nativeBottom - 3f, IntSize(width, height))
            metrics.rasterize {
                Math.addExact(x0[0], width)
                Math.addExact(y0[0], height)
                val bitmap = MemoryUtil.memAlloc(Math.multiplyExact(width, height))
                try {
                    STBTruetype.stbtt_MakeGlyphBitmapSubpixel(info, bitmap, width, height, width, pointScale, pointScale, shiftX, shiftY, index)
                    createDrawImage(IntSize(width, height), IntArray(bitmap.remaining()) { (bitmap[it].toInt() and 0xff) * 0x01010101 })
                } finally {
                    MemoryUtil.memFree(bitmap)
                }
            }
        }
    }

    private fun bitmapBox(
        info: STBTTFontinfo,
        index: Int,
        x0: IntBuffer,
        y0: IntBuffer,
        x1: IntBuffer,
        y1: IntBuffer,
    ) {
        if (STBTruetype.stbtt_GetGlyphBox(info, index, x0, y0, x1, y1)) {
            checkCoordinate(x0[0] * pointScale + shiftX)
            checkCoordinate(-y1[0] * pointScale + shiftY)
            checkCoordinate(x1[0] * pointScale + shiftX)
            checkCoordinate(-y0[0] * pointScale + shiftY)
        }
        STBTruetype.stbtt_GetGlyphBitmapBoxSubpixel(info, index, pointScale, pointScale, shiftX, shiftY, x0, y0, x1, y1)
    }

    private fun checkCoordinate(value: Float) {
        require(value.isFinite() && Int.MIN_VALUE <= floor(value.toDouble()) && ceil(value.toDouble()) <= Int.MAX_VALUE) {
            "TrueType raster coordinates must be finite and fit native integer bounds."
        }
    }

    override fun close() {
        val currentFont = font
        val currentMemory = memory
        font = null
        memory = null
        try {
            currentFont?.free()
        } finally {
            MemoryUtil.memFree(currentMemory)
        }
    }
}
