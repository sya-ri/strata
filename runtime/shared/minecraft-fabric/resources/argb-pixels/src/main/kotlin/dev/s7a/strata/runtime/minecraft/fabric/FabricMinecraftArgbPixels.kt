@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage

/**
 * Copies borrowed native straight-ARGB storage without retaining its native pixels.
 */
@JvmSynthetic
internal fun copyFabricMinecraftArgbPixels(image: NativeImage): IntArray = image.pixels

/**
 * Stores one straight-ARGB pixel through Minecraft's native-image API.
 */
@JvmSynthetic
internal fun setFabricMinecraftArgbPixel(
    image: NativeImage,
    x: Int,
    y: Int,
    argb: Int,
) {
    image.setPixel(x, y, argb)
}
