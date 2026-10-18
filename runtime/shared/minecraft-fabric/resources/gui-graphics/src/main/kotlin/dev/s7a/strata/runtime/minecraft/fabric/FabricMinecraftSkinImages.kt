package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage

/**
 * Copies borrowed client-thread player skins into detached, normalized straight-ARGB images.
 */
internal object FabricMinecraftSkinImages {
    private val playerSkinSize = IntSize(64, 64)

    /**
     * Snapshots the borrowed image without retaining its native pixels.
     */
    @JvmStatic
    fun snapshot(image: NativeImage): DrawImage =
        requireNormalized(
            createDrawImage(IntSize(image.width, image.height), copyFabricMinecraftArgbPixels(image)),
        )

    /**
     * Requires the normalized dimensions established by the selected native skin loader.
     */
    @JvmStatic
    fun requireNormalized(image: DrawImage): DrawImage {
        require(image.size == playerSkinSize) { "Minecraft player skins must normalize to exactly 64 by 64 pixels." }
        return image
    }
}
