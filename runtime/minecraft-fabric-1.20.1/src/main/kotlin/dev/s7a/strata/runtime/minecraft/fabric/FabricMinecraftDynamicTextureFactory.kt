package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.server.packs.resources.Resource

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
 * Indicates whether this exact client renders widgets from individual GUI sprites.
 */
@get:JvmSynthetic
@Suppress("MayBeConstant")
internal val fabricMinecraftUsesGuiSprites: Boolean = false

/**
 * Indicates whether this exact client's tooltip frame stretches its center.
 */
@get:JvmSynthetic
@Suppress("MayBeConstant")
internal val minecraftTooltipFrameStretchesInner: Boolean = false

/**
 * Rejects GUI sprite metadata because Minecraft 1.20.1 renders its widgets from fixed atlases and code-defined colors.
 *
 * @param resource unused resource borrowed for signature parity with GUI-sprite adapters.
 * @param path logical resource path used in the failure message.
 * @return this function never returns.
 * @throws IllegalArgumentException on every call.
 */
@Suppress("UnusedParameter")
internal fun readFabricMinecraftGuiScaling(
    resource: Resource,
    path: String,
): FabricMinecraftGuiScaling = throw IllegalArgumentException("Minecraft 1.20.1 resource $path has no GUI sprite metadata.")

private fun swapRedBlue(color: Int): Int =
    (color and 0xFF00FF00.toInt()) or
        (color and 0x00FF0000 ushr 16) or
        (color and 0x000000FF shl 16)
