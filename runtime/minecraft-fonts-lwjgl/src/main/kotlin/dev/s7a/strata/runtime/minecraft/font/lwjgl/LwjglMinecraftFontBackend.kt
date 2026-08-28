package dev.s7a.strata.runtime.minecraft.font.lwjgl

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.text.ArabicShaping
import com.ibm.icu.text.Bidi
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import org.lwjgl.stb.STBImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

/**
 * Owns the native faces opened by one engine on the construction thread.
 * Decoding and shaping retain no input strings or buffers, and close releases all still-open faces even after a cleanup failure.
 *
 * @param rasterizer target release's native TrueType contract.
 */
@Suppress("TooGenericExceptionCaught")
internal class LwjglMinecraftFontBackend(
    private val rasterizer: MinecraftTrueTypeRasterizer,
) : MinecraftFontBackend {
    private val owner = Thread.currentThread()
    private val faces = LinkedHashSet<ManagedFace>()
    private var closed = false

    override fun decodePng(bytes: ByteArray): DrawImage {
        requireOpen()
        val encoded = MemoryUtil.memAlloc(bytes.size)
        try {
            encoded.put(bytes).flip()
            return MemoryStack.stackPush().use { stack ->
                val width = stack.mallocInt(1)
                val height = stack.mallocInt(1)
                val channels = stack.mallocInt(1)
                val decoded = STBImage.stbi_load_from_memory(encoded, width, height, channels, 4)
                requireNotNull(decoded) { "Cannot decode font PNG: ${STBImage.stbi_failure_reason()}" }
                try {
                    val size = IntSize(width[0], height[0])
                    val pixels =
                        IntArray(Math.multiplyExact(size.width, size.height)) { index ->
                            val offset = index * 4
                            val red = decoded[offset].toInt() and 0xff
                            val green = decoded[offset + 1].toInt() and 0xff
                            val blue = decoded[offset + 2].toInt() and 0xff
                            val alpha = decoded[offset + 3].toInt() and 0xff
                            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                        }
                    createDrawImage(size, pixels)
                } finally {
                    STBImage.stbi_image_free(decoded)
                }
            }
        } finally {
            MemoryUtil.memFree(encoded)
        }
    }

    override fun visualOrder(
        text: String,
        rightToLeft: Boolean,
    ): String = buildString { visualGlyphs(text, rightToLeft).forEach { appendCodePoint(it.codePoint) } }

    override fun visualGlyphs(
        text: String,
        rightToLeft: Boolean,
    ): List<MinecraftVisualGlyph> {
        requireOpen()
        val shaped =
            try {
                ArabicShaping(ArabicShaping.LETTERS_SHAPE).shape(text)
            } catch (_: Exception) {
                text
            }
        val bidi = Bidi(shaped, if (rightToLeft) Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT else Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        bidi.reorderingMode = Bidi.REORDER_DEFAULT.toInt()
        val result = ArrayList<MinecraftVisualGlyph>()
        repeat(bidi.countRuns()) { runIndex ->
            val run = bidi.getVisualRun(runIndex)
            if (run.isOddRun) {
                var index = run.limit
                while (run.start < index) {
                    val codePoint = shaped.codePointBefore(index)
                    index -= Character.charCount(codePoint)
                    result.add(MinecraftVisualGlyph(UCharacter.getMirror(codePoint), index))
                }
            } else {
                var index = run.start
                while (index < run.limit) {
                    val codePoint = shaped.codePointAt(index)
                    result.add(MinecraftVisualGlyph(codePoint, index))
                    index += Character.charCount(codePoint)
                }
            }
        }
        return result
    }

    override fun openTrueType(
        bytes: ByteArray,
        settings: MinecraftTrueTypeSettings,
    ): MinecraftTrueTypeFace {
        requireOpen()
        val delegate =
            when (rasterizer) {
                MinecraftTrueTypeRasterizer.Stb -> StbMinecraftFontFace(bytes, settings)
                MinecraftTrueTypeRasterizer.FreeType -> FreeTypeMinecraftFontFace(bytes, settings)
            }
        return ManagedFace(delegate).also(faces::add)
    }

    override fun close() {
        requireOwner()
        if (closed) return
        closed = true
        var failure: Throwable? = null
        faces.toList().forEach { face ->
            try {
                face.close()
            } catch (caught: Throwable) {
                val primary = failure
                if (primary == null) {
                    failure = caught
                } else if (primary !== caught) {
                    primary.addSuppressed(caught)
                }
            }
        }
        faces.clear()
        failure?.let { throw it }
    }

    private fun requireOwner() {
        check(Thread.currentThread() === owner) { "Font backends are confined to their opening thread." }
    }

    private fun requireOpen() {
        requireOwner()
        check(closed.not()) { "Font backend is closed." }
    }

    private inner class ManagedFace(
        private var delegate: MinecraftTrueTypeFace?,
    ) : MinecraftTrueTypeFace {
        override fun glyph(codePoint: Int): MinecraftFontGlyph? {
            requireOpen()
            return checkNotNull(delegate) { "Font face is closed." }.glyph(codePoint)
        }

        override fun close() {
            requireOwner()
            val current = delegate ?: return
            delegate = null
            faces.remove(this)
            current.close()
        }
    }
}
