@file:JvmName("FabricMinecraftAssets")
@file:JvmMultifileClass

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.ClientAsset
import java.io.IOException

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

private val playerSkinSize = IntSize(64, 64)
