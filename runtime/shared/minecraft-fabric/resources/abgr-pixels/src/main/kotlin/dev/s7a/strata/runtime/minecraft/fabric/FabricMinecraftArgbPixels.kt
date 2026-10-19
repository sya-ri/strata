@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage

/**
 * Copies borrowed native ABGR storage into detached straight-ARGB pixels.
 */
@JvmSynthetic
internal fun copyFabricMinecraftArgbPixels(image: NativeImage): IntArray {
    val width = image.width
    val height = image.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[y * width + x] = swapRedBlue(image.getPixelRGBA(x, y))
        }
    }
    return pixels
}

private fun swapRedBlue(color: Int): Int =
    (color and 0xFF00FF00.toInt()) or
        (color and 0x00FF0000 ushr 16) or
        (color and 0x000000FF shl 16)
