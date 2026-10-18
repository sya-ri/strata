@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.authlib.Agent
import com.mojang.authlib.GameProfile
import com.mojang.authlib.ProfileLookupCallback
import com.mojang.authlib.minecraft.InsecurePublicKeyException
import com.mojang.authlib.minecraft.MinecraftProfileTexture
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.core.UUIDUtil
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Resolves legacy profiles and downloads normalized pixels on the native IO executor.
 */
internal fun lookupFabricMinecraftSkin(
    minecraft: Minecraft,
    source: PlayerSkinSource,
): CompletableFuture<Optional<FabricMinecraftSkinReference>> {
    val profile =
        when (source) {
            PlayerSkinSource.CurrentPlayer -> CompletableFuture.completedFuture(minecraft.user.gameProfile)
            is PlayerSkinSource.Name -> resolveName(minecraft, source.value)
            is PlayerSkinSource.Uuid -> resolveUuid(minecraft, source.value)
            is PlayerSkinSource.Pixels -> throw IllegalArgumentException("Direct player skin pixels do not require a platform lookup.")
        }
    return profile.thenApplyAsync({ Optional.of(resolveSkin(minecraft, it)) }, Util.ioPool())
}

/**
 * Copies the selected player's legacy skin on the client owner.
 */
internal fun snapshotCurrentFabricMinecraftSkin(minecraft: Minecraft): DrawImage {
    val player = checkNotNull(minecraft.player) { "A current Minecraft player is required to load its skin." }
    return resourceSkin(player.skinTextureLocation).snapshot(minecraft)
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
            Agent.MINECRAFT,
            object : ProfileLookupCallback {
                override fun onProfileLookupSucceeded(profile: GameProfile) {
                    result.complete(profile)
                }

                override fun onProfileLookupFailed(
                    profile: GameProfile,
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
): CompletableFuture<GameProfile> = CompletableFuture.supplyAsync({ minecraft.minecraftSessionService.fillProfileProperties(GameProfile(uuid, null), true) }, Util.ioPool())

private fun resolveSkin(
    minecraft: Minecraft,
    profile: GameProfile,
): FabricMinecraftSkinReference {
    val textures =
        try {
            minecraft.minecraftSessionService.getTextures(profile, true)
        } catch (failure: InsecurePublicKeyException) {
            throw CompletionException(failure)
        }
    val skin = textures[MinecraftProfileTexture.Type.SKIN] ?: return resourceSkin(DefaultPlayerSkin.getDefaultSkin(UUIDUtil.getOrCreatePlayerUUID(profile)))
    val image = downloadSkin(minecraft, skin.url)
    return FabricMinecraftSkinReference { image }
}

private fun downloadSkin(
    minecraft: Minecraft,
    url: String,
): DrawImage =
    try {
        val connection = URL(url).openConnection(minecraft.proxy) as HttpURLConnection
        try {
            connection.doInput = true
            connection.doOutput = false
            connection.connect()
            val response = connection.responseCode
            if (response / 100 != 2) throw IOException("Minecraft skin download returned HTTP $response.")
            snapshotLegacyFabricMinecraftSkin(connection.inputStream.use(NativeImage::read))
        } finally {
            connection.disconnect()
        }
    } catch (failure: IOException) {
        throw CompletionException(failure)
    }

private fun resourceSkin(location: MinecraftResourceLocation): FabricMinecraftSkinReference =
    FabricMinecraftSkinReference { minecraft ->
        when (val texture = minecraft.textureManager.getTexture(location)) {
            is DynamicTexture -> createPlayerSkinSnapshot(checkNotNull(texture.pixels) { "The downloaded player skin has already been released." })
            else -> requireNormalizedPlayerSkin(loadMinecraftUiImage(ResourceId(location.namespace, location.path)))
        }
    }
