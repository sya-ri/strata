@file:JvmName("MinecraftRuntimeFactories")

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Creates one owner-thread host by atomically consuming a definition.
 *
 * Successful construction leaves the definition empty and transfers metadata and content to the new host.
 *
 * @param definition the available one-shot definition.
 * @param profile the complete immutable Minecraft asset profile.
 * @return a distinct owner-thread host.
 * @throws IllegalStateException when [definition] was already transferred or closed.
 */
@InternalStrataRuntimeApi
public fun createMinecraftUiHost(
    definition: ScreenDefinition,
    profile: MinecraftUiProfile,
): MinecraftUiHost = MinecraftHostImplementation.create(definition, profile)

/**
 * Creates one owner-thread host with version-specific services by atomically consuming a definition.
 *
 * Successful construction transfers [platform] to the host, which refreshes it before frames and closes it on every terminal path.
 * This privileged overload is for a versioned runtime adapter; application code receives the resulting screen from that adapter instead.
 *
 * @param definition the available one-shot definition.
 * @param profile the complete immutable Minecraft asset profile.
 * @param platform version-specific services transferred to the host.
 * @return a distinct owner-thread host.
 * @throws IllegalStateException when [definition] was already transferred or closed.
 * @throws Throwable when construction fails; [platform] remains caller-owned unless transfer completes.
 */
@InternalStrataRuntimeApi
public fun createMinecraftUiHost(
    definition: ScreenDefinition,
    profile: MinecraftUiProfile,
    platform: MinecraftUiPlatform,
): MinecraftUiHost = MinecraftHostImplementation.create(definition, profile, platform)

/**
 * Creates one complete immutable Minecraft UI profile.
 *
 * The callback and builder are confined to the calling thread and the callback's dynamic lifetime.
 * The builder closes in `finally`, so escaped use fails after both success and failure.
 *
 * @param content the profile declaration callback.
 * @return a complete immutable profile with referential identity.
 * @throws IllegalArgumentException when a declared slot is invalid, duplicated, or missing.
 * @throws Throwable when [content] fails; the exact callback failure is propagated unchanged.
 */
@InternalStrataRuntimeApi
public fun createMinecraftUiProfile(content: MinecraftUiProfileBuilder.() -> Unit): MinecraftUiProfile = MinecraftProfileImplementation.create(content)
