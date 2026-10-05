package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle.FabricMinecraftNativeImageAccess
import org.lwjgl.system.MemoryUtil

/**
 * Copies straight ARGB into a newly allocated RGBA NativeImage without an intermediate pixel array.
 * The render-thread caller owns the image throughout this synchronous borrow and its later upload fence.
 * [pixel] reads immutable source storage only; it must not close or change the destination.
 */
@JvmSynthetic
internal inline fun uploadFabricMinecraftArgbPixels(
    image: NativeImage,
    size: IntSize,
    pixel: (Int, Int) -> Int,
) {
    check(image.format() == NativeImage.Format.RGBA) { "Portable upload storage must be RGBA." }
    check(image.width == size.width && image.height == size.height) { "Portable upload extents must match their source." }
    writeFabricMinecraftArgbPixels((image as Any as FabricMinecraftNativeImageAccess).strataPixels(), size, pixel)
}

/**
 * Writes exactly [size]'s checked area to live native storage using NativeImage's packed ABGR representation.
 * The caller supplies an allocation of at least four bytes per pixel and owns it until this synchronous copy returns.
 * No pointer, source callback, buffer or copied image is retained; failed validation writes nothing.
 */
@JvmSynthetic
internal inline fun writeFabricMinecraftArgbPixels(
    address: Long,
    size: IntSize,
    pixel: (Int, Int) -> Int,
) {
    require(address != 0L) { "Portable upload storage must be allocated." }
    require(0 < size.width && 0 < size.height) { "Portable upload extents must be positive." }
    val count = Math.multiplyExact(size.width, size.height)
    Math.addExact(address, Math.multiplyExact(count.toLong(), Int.SIZE_BYTES.toLong()))
    var offset = 0L
    for (y in 0 until size.height) {
        for (x in 0 until size.width) {
            val argb = pixel(x, y)
            val abgr = (argb and 0xFF00FF00.toInt()) or ((argb ushr 16) and 0xFF) or ((argb and 0xFF) shl 16)
            MemoryUtil.memPutInt(address + offset, abgr)
            offset += Int.SIZE_BYTES
        }
    }
}
