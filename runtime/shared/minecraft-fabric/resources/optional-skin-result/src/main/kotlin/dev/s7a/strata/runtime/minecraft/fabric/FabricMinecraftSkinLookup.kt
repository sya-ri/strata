@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.authlib.GameProfile
import net.minecraft.client.resources.SkinManager
import java.util.Optional
import java.util.concurrent.CompletableFuture

/**
 * Preserves an absent native skin while adapting resolved descriptors for owner-thread snapshots.
 */
internal fun loadFabricMinecraftSkin(
    manager: SkinManager,
    profile: GameProfile,
): CompletableFuture<Optional<FabricMinecraftSkinReference>> = manager.getOrLoad(profile).thenApply { it.map(::fabricMinecraftSkinReference) }
