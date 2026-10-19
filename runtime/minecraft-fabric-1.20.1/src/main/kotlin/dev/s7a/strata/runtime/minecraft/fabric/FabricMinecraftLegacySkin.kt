@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.render.DrawImage

/**
 * Consumes decoded legacy pixels, expanding mirrored limbs and normalizing alpha before detaching the result.
 * Every native image is closed on both success and failure.
 */
@JvmSynthetic
internal fun snapshotLegacyFabricMinecraftSkin(image: NativeImage): DrawImage =
    image.use { decoded ->
        require(decoded.width == 64 && (decoded.height == 32 || decoded.height == 64)) {
            "Minecraft player skins must decode to 64 by 32 or 64 by 64 pixels."
        }
        if (decoded.height == 64) return@use finishSkin(decoded, legacy = false)
        NativeImage(64, 64, true).use { expanded ->
            expanded.copyFrom(decoded)
            expandLegacySkin(expanded)
            finishSkin(expanded, legacy = true)
        }
    }

private fun expandLegacySkin(image: NativeImage) {
    image.fillRect(0, 32, 64, 32, 0)
    image.copyRect(4, 16, 16, 32, 4, 4, true, false)
    image.copyRect(8, 16, 16, 32, 4, 4, true, false)
    image.copyRect(0, 20, 24, 32, 4, 12, true, false)
    image.copyRect(4, 20, 16, 32, 4, 12, true, false)
    image.copyRect(8, 20, 8, 32, 4, 12, true, false)
    image.copyRect(12, 20, 16, 32, 4, 12, true, false)
    image.copyRect(44, 16, -8, 32, 4, 4, true, false)
    image.copyRect(48, 16, -8, 32, 4, 4, true, false)
    image.copyRect(40, 20, 0, 32, 4, 12, true, false)
    image.copyRect(44, 20, -8, 32, 4, 12, true, false)
    image.copyRect(48, 20, -16, 32, 4, 12, true, false)
    image.copyRect(52, 20, -8, 32, 4, 12, true, false)
}

private fun finishSkin(
    image: NativeImage,
    legacy: Boolean,
): DrawImage {
    setNoAlpha(image, 0, 0, 32, 16)
    if (legacy) applyLegacyHatTransparency(image)
    setNoAlpha(image, 0, 16, 64, 32)
    setNoAlpha(image, 16, 48, 48, 64)
    return createPlayerSkinSnapshot(image)
}

private fun applyLegacyHatTransparency(image: NativeImage) {
    for (x in 32 until 64) {
        for (y in 0 until 32) {
            if ((image.getPixelRGBA(x, y) ushr 24 and 255) < 128) return
        }
    }
    for (x in 32 until 64) {
        for (y in 0 until 32) image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) and 0x00FFFFFF)
    }
}

private fun setNoAlpha(
    image: NativeImage,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
) {
    for (x in left until right) {
        for (y in top until bottom) image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) or 0xFF000000.toInt())
    }
}
