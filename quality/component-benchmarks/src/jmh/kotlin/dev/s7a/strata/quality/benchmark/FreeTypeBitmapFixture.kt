package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.freetype.FT_Bitmap
import org.lwjgl.util.freetype.FreeType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer

/**
 * Owns a native bitmap and the genuine runtime face, with an identical reflective adapter on both archives.
 * Construction, source patterns and every oracle check remain outside measured converter calls.
 */
@Suppress("TooGenericExceptionCaught") // Cleanup also owns assertion and linkage failures.
internal class FreeTypeBitmapFixture(
    private val axis: Int,
    private val layout: FreeTypeGrayscaleBenchmark.Layout,
) : AutoCloseable {
    private var target: MinecraftTrueTypeFace? = null
    private var source: ByteBuffer? = null
    private var bitmap: FT_Bitmap? = null
    private lateinit var convert: Method
    private val stride = axis + if (layout.padded) 3 else 0
    private val pitch = if (layout.reversed) -stride else stride
    private val seed = listOf(1, 8, 16, 64, 256).indexOf(axis) * 4 + layout.ordinal
    private val logical = IntArray(Math.multiplyExact(axis, axis)) { index -> (index * 73 + index / axis * (axis / 256) * 19 + seed * 29) and 0xff }
    private val original = ByteArray(Math.multiplyExact(stride, axis)) { index -> ((index * 37 + seed * 11 + 101) and 0xff).toByte() }
    private val backend: MinecraftFontBackend = LwjglMinecraftFontBackendFactory.open(FontRasterAssets.compatibility(MinecraftTrueTypeRasterizer.FreeType))

    init {
        try {
            require(axis in setOf(1, 8, 16, 64, 256))
            for (y in 0 until axis) {
                for (x in 0 until axis) {
                    val physical = if (layout.reversed) axis - y - 1 else y
                    original[physical * stride + x] = logical[y * axis + x].toByte()
                }
            }
            val opened = backend.openTrueType(FreeTypeGrayscaleAssets.font(), MinecraftTrueTypeSettings())
            val actual = FreeTypeGrayscaleAssets.delegate(opened)
            target = actual
            convert = actual.javaClass.getDeclaredMethod("pixels", FT_Bitmap::class.java, Int::class.java, Int::class.java).apply { isAccessible = true }
            val buffer = MemoryUtil.memAlloc(original.size)
            source = buffer
            buffer.put(original).flip()
            val native = FT_Bitmap.calloc()
            bitmap = native
            val address = native.address()
            MemoryUtil.memPutInt(address + FT_Bitmap.ROWS, axis)
            MemoryUtil.memPutInt(address + FT_Bitmap.WIDTH, axis)
            MemoryUtil.memPutInt(address + FT_Bitmap.PITCH, pitch)
            MemoryUtil.memPutAddress(address + FT_Bitmap.BUFFER, MemoryUtil.memAddress(buffer))
            MemoryUtil.memPutByte(address + FT_Bitmap.PIXEL_MODE, FreeType.FT_PIXEL_MODE_GRAY.toByte())
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /**
     * Includes reflection, validation, native ByteBuffer wrapping, one owned array and the immutable image bridge.
     * The reflective vararg adapter is identical on both variants and is part of this measured boundary.
     */
    internal fun image(): DrawImage =
        try {
            convert.invoke(checkNotNull(target), checkNotNull(bitmap), axis, axis) as DrawImage
        } catch (failure: InvocationTargetException) {
            throw checkNotNull(failure.cause)
        }

    /**
     * Returns an independent complete ARGB oracle using scalar byte addresses and shifts rather than row conversion.
     */
    internal fun expected(): IntArray =
        IntArray(logical.size) { offset ->
            val y = offset / axis
            val x = offset % axis
            val physical = if (layout.reversed) axis - y - 1 else y
            val value = original[physical * stride + x].toInt() and 0xff
            check(value == logical[offset])
            (value shl 24) or (value shl 16) or (value shl 8) or value
        }

    /**
     * Compares every native byte including poisoned padding, then destroys the borrowed source contents.
     */
    internal fun overwrite() {
        val buffer = checkNotNull(source)
        check(original.contentEquals(ByteArray(original.size) { buffer[it] }))
        MemoryUtil.memSet(MemoryUtil.memAddress(buffer), 0, buffer.capacity().toLong())
    }

    /**
     * Original private converter failures, included as one three-stage rejection control inside timing.
     */
    internal fun malformed(): List<Throwable> {
        val native = checkNotNull(bitmap)
        val address = native.address()
        val failures = ArrayList<Throwable>(3)
        try {
            listOf(FreeType.FT_PIXEL_MODE_MONO, FreeType.FT_PIXEL_MODE_GRAY).forEach { mode ->
                MemoryUtil.memPutByte(address + FT_Bitmap.PIXEL_MODE, mode.toByte())
                MemoryUtil.memPutInt(address + FT_Bitmap.WIDTH, axis + 1)
                failures.add(rejection())
            }
            MemoryUtil.memPutInt(address + FT_Bitmap.WIDTH, axis)
            MemoryUtil.memPutInt(address + FT_Bitmap.PITCH, 0)
            failures.add(rejection())
            return failures
        } finally {
            MemoryUtil.memPutByte(address + FT_Bitmap.PIXEL_MODE, FreeType.FT_PIXEL_MODE_GRAY.toByte())
            MemoryUtil.memPutInt(address + FT_Bitmap.WIDTH, axis)
            MemoryUtil.memPutInt(address + FT_Bitmap.PITCH, pitch)
        }
    }

    private fun rejection(): Throwable =
        try {
            image()
            error("Malformed native bitmap was admitted")
        } catch (failure: IllegalArgumentException) {
            failure
        }

    /**
     * Releases the native descriptor, bytes and backend; detached images remain valid after this returns.
     */
    override fun close() {
        val native = bitmap
        bitmap = null
        native?.free()
        val buffer = source
        source = null
        MemoryUtil.memFree(buffer)
        target = null
        backend.close()
    }
}
