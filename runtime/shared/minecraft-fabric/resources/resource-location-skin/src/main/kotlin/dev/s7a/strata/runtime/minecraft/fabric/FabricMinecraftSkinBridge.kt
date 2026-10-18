@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.PropertyMap
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.render.DrawImage
import net.minecraft.client.Minecraft
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Optional
import java.util.concurrent.CompletableFuture

/**
 * Resolves a native skin descriptor asynchronously; texture reads remain on the client owner.
 */
internal fun lookupFabricMinecraftSkin(
    minecraft: Minecraft,
    source: PlayerSkinSource,
): CompletableFuture<Optional<FabricMinecraftSkinReference>> {
    val profile =
        when (source) {
            PlayerSkinSource.CurrentPlayer -> CompletableFuture.completedFuture(minecraft.gameProfile)
            is PlayerSkinSource.Name -> resolveProfile(ResolvableProfile(Optional.of(source.value), Optional.empty(), PropertyMap()))
            is PlayerSkinSource.Uuid -> resolveProfile(ResolvableProfile(Optional.empty(), Optional.of(source.value), PropertyMap()))
            is PlayerSkinSource.Pixels -> throw IllegalArgumentException("Direct player skin pixels do not require a platform lookup.")
        }
    val skinManager = minecraft.skinManager
    return profile.thenCompose { loadFabricMinecraftSkin(skinManager, it) }
}

/**
 * Copies the selected player's skin on the client thread without retaining its textures.
 */
internal fun snapshotCurrentFabricMinecraftSkin(minecraft: Minecraft): DrawImage {
    val player = checkNotNull(minecraft.player) { "A current Minecraft player is required to load its skin." }
    return fabricMinecraftSkinReference(player.skin).snapshot(minecraft)
}

private fun resolveProfile(profile: ResolvableProfile): CompletableFuture<GameProfile> = profile.resolve().thenApply(ResolvableProfile::gameProfile)
