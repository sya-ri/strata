@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.authlib.GameProfile
import net.minecraft.client.resources.SkinManager
import java.util.Optional
import java.util.concurrent.CompletableFuture

/**
 * Wraps the native direct result in the binding's optional skin-reference contract.
 */
internal fun loadFabricMinecraftSkin(
    manager: SkinManager,
    profile: GameProfile,
): CompletableFuture<Optional<FabricMinecraftSkinReference>> = manager.getOrLoad(profile).thenApply { Optional.of(fabricMinecraftSkinReference(it)) }
