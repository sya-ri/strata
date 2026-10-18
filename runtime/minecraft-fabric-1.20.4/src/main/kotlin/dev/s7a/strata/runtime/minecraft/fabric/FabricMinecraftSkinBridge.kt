@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.authlib.GameProfile
import com.mojang.authlib.ProfileLookupCallback
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.render.DrawImage
import net.minecraft.Util
import net.minecraft.client.Minecraft
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Resolves profiles through the native repository and snapshots skins only on their client owner.
 */
internal fun lookupFabricMinecraftSkin(
    minecraft: Minecraft,
    source: PlayerSkinSource,
): CompletableFuture<Optional<FabricMinecraftSkinReference>> {
    val profile =
        when (source) {
            PlayerSkinSource.CurrentPlayer -> CompletableFuture.completedFuture(minecraft.gameProfile)
            is PlayerSkinSource.Name -> resolveName(minecraft, source.value)
            is PlayerSkinSource.Uuid -> resolveUuid(minecraft, source.value)
            is PlayerSkinSource.Pixels -> throw IllegalArgumentException("Direct player skin pixels do not require a platform lookup.")
        }
    val skinManager = minecraft.skinManager
    return profile.thenCompose(skinManager::getOrLoad).thenApply { Optional.of(fabricMinecraftSkinReference(it)) }
}

/**
 * Copies the selected player's skin on the client thread without retaining its textures.
 */
internal fun snapshotCurrentFabricMinecraftSkin(minecraft: Minecraft): DrawImage {
    val player = checkNotNull(minecraft.player) { "A current Minecraft player is required to load its skin." }
    return fabricMinecraftSkinReference(player.skin).snapshot(minecraft)
}

private fun resolveName(
    minecraft: Minecraft,
    name: String,
): CompletableFuture<GameProfile> {
    val result = CompletableFuture<GameProfile>()
    Util.ioPool().execute {
        val repository = YggdrasilAuthenticationService(minecraft.proxy).createProfileRepository()
        repository.findProfilesByNames(
            arrayOf(name),
            object : ProfileLookupCallback {
                override fun onProfileLookupSucceeded(profile: GameProfile) {
                    result.complete(profile)
                }

                override fun onProfileLookupFailed(
                    profileName: String,
                    exception: Exception,
                ) {
                    result.completeExceptionally(exception)
                }
            },
        )
    }
    return result.thenCompose { resolveUuid(minecraft, it.id) }
}

private fun resolveUuid(
    minecraft: Minecraft,
    uuid: UUID,
): CompletableFuture<GameProfile> =
    CompletableFuture.supplyAsync({
        requireNotNull(minecraft.minecraftSessionService.fetchProfile(uuid, true)) { "Minecraft did not resolve player profile $uuid." }.profile()
    }, Util.ioPool())
