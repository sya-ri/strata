package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling

/**
 * Constructs Minecraft's unnamed dynamic texture for the caller-owned [image].
 */
internal fun createFabricMinecraftDynamicTexture(image: NativeImage): DynamicTexture = DynamicTexture(image)

/**
 * Copies Minecraft's native image into detached straight-ARGB pixels.
 */
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
