@file:JvmName("FabricMinecraftAssets")

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.ClientAsset
import net.minecraft.resources.Identifier
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
    val identifier = Identifier.fromNamespaceAndPath(asset.namespace, asset.path)
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
            pixels = image.getPixels()
        }
    }
    return createDrawImage(size, pixels)
}

/**
 * Snapshots the current client's selected 64 by 64 player skin for portable PlayerHead rendering.
 *
 * Resource-backed default skins are resolved through the active resource manager, so a higher-priority resource pack may replace them.
 * Downloaded skins are copied from Minecraft's registered dynamic texture after its skin pipeline has normalized legacy dimensions.
 * The returned image retains neither the player, texture manager, resource, stream, nor native image.
 *
 * @return immutable detached straight-ARGB pixels for the current selected skin.
 * @throws IllegalArgumentException when the selected texture kind or normalized dimensions are unsupported.
 * @throws IllegalStateException when no current player exists, the downloaded texture is unavailable, or the function is called away from the Minecraft client thread.
 * @throws IOException when a selected resource-backed skin cannot be decoded.
 */
public fun loadCurrentMinecraftPlayerSkin(): DrawImage {
    val minecraft = Minecraft.getInstance()
    check(minecraft.isSameThread()) { "Minecraft player skins must be loaded on the client thread." }
    val player = checkNotNull(minecraft.player) { "A current Minecraft player is required to load its skin." }
    return when (val body = player.skin.body()) {
        is ClientAsset.ResourceTexture -> {
            loadMinecraftUiImage(
                ResourceId(body.texturePath().namespace, body.texturePath().path),
            )
        }

        is ClientAsset.DownloadedTexture -> {
            val texture = minecraft.textureManager.getTexture(body.texturePath())
            check(texture is DynamicTexture) { "The selected downloaded player skin is not backed by a dynamic texture." }
            val image = checkNotNull(texture.pixels) { "The selected downloaded player skin has already been released." }
            createPlayerSkinSnapshot(image)
        }

        else -> {
            throw IllegalArgumentException("Unsupported Minecraft player skin texture kind: ${body.javaClass.name}")
        }
    }.also { skin ->
        require(skin.size == playerSkinSize) { "Minecraft player skins must normalize to exactly 64 by 64 pixels." }
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
    return createDrawImage(size, image.pixels)
}

private val playerSkinSize = IntSize(64, 64)
