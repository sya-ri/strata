@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.authlib.GameProfile
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.ClientAsset
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Optional
import java.util.concurrent.CompletableFuture

/**
 * Resolves native asset-based skins asynchronously without reading client-owned textures on a completion thread.
 */
internal fun lookupFabricMinecraftSkin(
    minecraft: Minecraft,
    source: PlayerSkinSource,
): CompletableFuture<Optional<FabricMinecraftSkinReference>> {
    val profile =
        when (source) {
            PlayerSkinSource.CurrentPlayer -> CompletableFuture.completedFuture(minecraft.gameProfile)
            is PlayerSkinSource.Name -> ResolvableProfile.createUnresolved(source.value).resolveProfile(minecraft.services().profileResolver())
            is PlayerSkinSource.Uuid -> ResolvableProfile.createUnresolved(source.value).resolveProfile(minecraft.services().profileResolver())
            is PlayerSkinSource.Pixels -> throw IllegalArgumentException("Direct player skin pixels do not require a platform lookup.")
        }
    val skinManager = minecraft.skinManager
    return profile.thenCompose(skinManager::get).thenApply { it.map(::fabricMinecraftSkinReference) }
}

/**
 * Copies the selected player's skin on the client thread without retaining its textures.
 */
internal fun snapshotCurrentFabricMinecraftSkin(minecraft: Minecraft): DrawImage {
    val player = checkNotNull(minecraft.player) { "A current Minecraft player is required to load its skin." }
    return fabricMinecraftSkinReference(player.skin).snapshot(minecraft)
}

/**
 * Retains one immutable descriptor until its client-thread snapshot detaches normalized pixels.
 */
internal fun fabricMinecraftSkinReference(skin: PlayerSkin): FabricMinecraftSkinReference =
    FabricMinecraftSkinReference { minecraft ->
        when (val body = skin.body()) {
            is ClientAsset.ResourceTexture -> {
                val identifier = body.texturePath()
                requireNormalizedPlayerSkin(loadMinecraftUiImage(ResourceId(identifier.namespace, identifier.path)))
            }

            is ClientAsset.DownloadedTexture -> {
                val texture = minecraft.textureManager.getTexture(body.texturePath())
                check(texture is DynamicTexture) { "The downloaded player skin is not backed by a dynamic texture." }
                val image = checkNotNull(texture.pixels) { "The downloaded player skin has already been released." }
                createPlayerSkinSnapshot(image)
            }

            else -> {
                throw IllegalArgumentException("Unsupported Minecraft player skin texture kind: ${body.javaClass.name}")
            }
        }
    }
