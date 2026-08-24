package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling

/**
 * Constructs Minecraft's unnamed dynamic texture for the caller-owned [image].
 */
internal fun createFabricMinecraftDynamicTexture(image: NativeImage): DynamicTexture = DynamicTexture(image)

/**
 * Copies Minecraft's native RGBA storage into detached straight-ARGB pixels.
 */
internal fun copyFabricMinecraftArgbPixels(image: NativeImage): IntArray {
    val width = image.getWidth()
    val height = image.getHeight()
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[y * width + x] = swapRedBlue(image.getPixelRGBA(x, y))
        }
    }
    return pixels
}

/**
 * Stores one straight-ARGB pixel through Minecraft's ABGR native-image API.
 */
@JvmSynthetic
internal fun setFabricMinecraftArgbPixel(
    image: NativeImage,
    x: Int,
    y: Int,
    argb: Int,
) {
    image.setPixelRGBA(x, y, swapRedBlue(argb))
}

/**
 * Indicates that Minecraft 1.20.5 nine-slice metadata tiles its center.
 */
@get:JvmSynthetic
@Suppress("MayBeConstant")
internal val minecraftTooltipFrameStretchesInner: Boolean = false

/**
 * Reads the center mode represented by Minecraft 1.20.5's fixed tiled nine-slice record.
 */
@JvmSynthetic
@Suppress("FunctionOnlyReturningConstant", "UnusedParameter")
internal fun minecraftNineSliceStretchesInner(scaling: GuiSpriteScaling.NineSlice): Boolean = false

private fun swapRedBlue(color: Int): Int =
    (color and 0xFF00FF00.toInt()) or
        (color and 0x00FF0000 ushr 16) or
        (color and 0x000000FF shl 16)
