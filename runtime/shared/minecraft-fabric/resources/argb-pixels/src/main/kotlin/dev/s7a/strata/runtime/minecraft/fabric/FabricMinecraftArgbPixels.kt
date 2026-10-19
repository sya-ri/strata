@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage

/**
 * Copies borrowed native straight-ARGB storage without retaining its native pixels.
 */
@JvmSynthetic
internal fun copyFabricMinecraftArgbPixels(image: NativeImage): IntArray = image.pixels
