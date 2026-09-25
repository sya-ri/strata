package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.ResourceManager

@OptIn(InternalStrataRuntimeApi::class)
private val currentProfile = FabricMinecraftProfileCache<MinecraftUiProfile>()

/**
 * Reuses one detached profile for the current resource generation and captured font selections.
 *
 * The caller validates the client thread before entry; the cache rejects publication when the resource generation changes during [extract].
 * GUI scale is absent from the key because profile source pixels and font resources have no presentation density.
 * The cache borrows the callback synchronously and retains no native face, host, or historical profile.
 *
 * @param manager active resource-manager identity, invalidated by its native reload and close hooks.
 * @param compatibility exact compiler-selected font capabilities.
 * @param options copied current font and language options.
 * @param extract synchronous immutable-profile extraction receiving exactly the captured cache-key inputs on a miss.
 * @return shared immutable profile for these inputs.
 * @throws Throwable when extraction fails or its generation is invalidated before publication.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun cachedFabricMinecraftProfile(
    manager: ResourceManager,
    compatibility: MinecraftFontCompatibility,
    options: MinecraftFontOptions,
    extract: (ResourceManager, MinecraftFontCompatibility, MinecraftFontOptions) -> MinecraftUiProfile,
): MinecraftUiProfile = currentProfile.get(manager, compatibility, options) { extract(manager, compatibility, options) }

/**
 * Advances the adapter generation before native resource replacement without touching existing hosts.
 *
 * The active client's manager advances the generation even while empty; foreign managers can only evict their own populated entry.
 * This thread-safe native lifecycle bridge retains only one empty generation token after invalidation.
 * It uses a synthetic, unmangled JVM method so the injected native callback has no Java-visible Strata API dependency.
 *
 * @param manager identity of the native manager beginning resource replacement.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun invalidateFabricMinecraftProfile(manager: Any) {
    currentProfile.invalidate(manager, Minecraft.getInstance().resourceManager === manager)
}

/**
 * Permanently releases the active client's profile cache before native resource shutdown.
 *
 * Later normal opens fail without invoking extraction; existing immutable hosts remain independent.
 * Closing any other manager can only evict that manager's populated entry and cannot terminate the client cache.
 * The current-client identity is borrowed during this call, never stored in an empty or terminal state.
 *
 * @param manager identity of the native manager beginning close.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun closeFabricMinecraftProfile(manager: Any) {
    currentProfile.close(manager, Minecraft.getInstance().resourceManager === manager)
}
