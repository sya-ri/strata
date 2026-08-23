package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling

/**
 * Transfers ownership of [pixels] to the named dynamic texture shared by Minecraft 1.21.6 and later remapped releases.
 *
 * The caller owns the returned texture and remains responsible for closing [pixels] if construction fails.
 * This function must run on the Minecraft client render thread and propagates native allocation failures.
 *
 * @param pixels Native pixels prepared for the retained frame layer.
 * @return A dynamic texture that owns [pixels].
 */
internal fun createFabricMinecraftDynamicTexture(pixels: NativeImage): DynamicTexture = DynamicTexture({ "Strata runtime frame layer" }, pixels)

/**
 * Copies Minecraft's native image into detached straight-ARGB pixels.
 */
@JvmSynthetic
internal fun copyFabricMinecraftArgbPixels(image: NativeImage): IntArray = image.getPixels()

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

/**
 * Indicates that the supported tooltip frame metadata stretches its center.
 *
 * This remains a property so the internal adapter bridge does not expose a Java constant field.
 */
@get:JvmSynthetic
@Suppress("MayBeConstant")
internal val minecraftTooltipFrameStretchesInner: Boolean = true

/**
 * Reads the explicit center mode from Minecraft's nine-slice metadata.
 */
@JvmSynthetic
internal fun minecraftNineSliceStretchesInner(scaling: GuiSpriteScaling.NineSlice): Boolean = scaling.stretchInner()
