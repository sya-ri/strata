@file:JvmName("FabricMinecraftAssets")
@file:JvmMultifileClass

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import net.minecraft.client.Minecraft
import java.io.IOException

/**
 * Loads one immutable UI image from the active resource manager.
 *
 * The selected resource may come from the application Mod or any higher-priority resource pack.
 * The stream and native image close before return, and the result retains only detached straight-ARGB pixels.
 *
 * @param asset common identifier shared by client and server code.
 * @return immutable pixels with the selected resource's exact dimensions.
 * @throws IllegalArgumentException when the resource is absent or has an empty axis.
 * @throws IllegalStateException when called away from the Minecraft client thread.
 * @throws IOException when the selected resource cannot be decoded.
 */
public fun loadMinecraftUiImage(asset: ResourceId): DrawImage {
    val minecraft = Minecraft.getInstance()
    check(minecraft.isSameThread()) { "Minecraft UI images must be loaded on the client thread." }
    val identifier = MinecraftResourceLocation.fromNamespaceAndPath(asset.namespace, asset.path)
    val resource =
        minecraft
            .getResourceManager()
            .getResource(identifier)
            .orElseThrow { IllegalArgumentException("Missing Minecraft resource: $identifier") }
    val pixels: IntArray
    val size: IntSize
    resource.open().use { stream ->
        NativeImage.read(stream).use { image ->
            size = IntSize(image.getWidth(), image.getHeight())
            require(0 < size.width && 0 < size.height) { "Minecraft UI image dimensions must be positive." }
            pixels = copyFabricMinecraftArgbPixels(image)
        }
    }
    return createDrawImage(size, pixels)
}

/**
 * Copies one normalized native skin into an immutable platform-neutral image.
 *
 * @param image allocated RGBA native image read synchronously without mutation.
 * @return detached straight-ARGB pixels with the same dimensions.
 * @throws IllegalArgumentException when [image] is not exactly 64 by 64.
 */
@JvmSynthetic
internal fun createPlayerSkinSnapshot(image: NativeImage): DrawImage {
    val size = IntSize(image.width, image.height)
    require(size == playerSkinSize) { "Minecraft player skins must normalize to exactly 64 by 64 pixels." }
    return createDrawImage(size, copyFabricMinecraftArgbPixels(image))
}

private val playerSkinSize = IntSize(64, 64)
