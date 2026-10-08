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
import java.io.InputStream

/**
 * Loads one immutable UI image from the active resource manager.
 *
 * The selected resource may come from the application Mod or any higher-priority resource pack.
 * The current stream is read on every call and closes before return.
 * Exact encoded-byte equality may reuse one bounded private decoded payload, but every result is a fresh detached straight-ARGB snapshot.
 * Native reload and shutdown release that payload without changing images already held by hosts.
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
    val identifier = minecraftResourceLocation(asset.namespace, asset.path)
    val manager = minecraft.getResourceManager()
    return currentImageDecode.load(manager) {
        manager
            .getResource(identifier)
            .orElseThrow { IllegalArgumentException("Missing Minecraft resource: $identifier") }
            .open()
    }
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

private val currentImageDecode = FabricMinecraftImageDecodeCache(::decodeFabricMinecraftUiImage)

private fun decodeFabricMinecraftUiImage(stream: InputStream): FabricMinecraftImageDecodeCache.Decoded =
    NativeImage.read(stream).use { image ->
        val size = IntSize(image.getWidth(), image.getHeight())
        require(0 < size.width && 0 < size.height) { "Minecraft UI image dimensions must be positive." }
        FabricMinecraftImageDecodeCache.Decoded(size, copyFabricMinecraftArgbPixels(image))
    }

/**
 * Releases derived decoding state through the existing native resource-generation hook.
 */
@JvmSynthetic
internal fun invalidateFabricMinecraftImageDecode(
    manager: Any,
    activeClient: Boolean,
) {
    currentImageDecode.invalidate(manager, activeClient)
}

/**
 * Releases derived decoding state through the existing native resource-close hook.
 */
@JvmSynthetic
internal fun closeFabricMinecraftImageDecode(
    manager: Any,
    activeClient: Boolean,
) {
    currentImageDecode.close(manager, activeClient)
}
