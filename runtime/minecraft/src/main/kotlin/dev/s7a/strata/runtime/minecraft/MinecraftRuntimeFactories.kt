@file:JvmName("MinecraftRuntimeFactories")

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
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
 * Creates an owner-thread host with independent native font ownership from a shareable resource snapshot.
 * The backend factory is borrowed only during construction; the opened backend is closed after the tree on every terminal path.
 * No Minecraft classes, display, graphics context, or operating-system fonts are needed by a CPU backend.
 *
 * @param definition available one-shot definition transferred on successful host construction.
 * @param profile immutable profile containing resource fonts or compatibility glyphs.
 * @param fontBackend factory for the target release's CPU font backend.
 * @return distinct owner-thread host.
 * @throws Throwable when transfer or font initialization fails; opened native resources are released.
 */
@InternalStrataRuntimeApi
public fun createMinecraftUiHost(
    definition: ScreenDefinition,
    profile: MinecraftUiProfile,
    fontBackend: MinecraftFontBackendFactory,
): MinecraftUiHost = MinecraftHostImplementation.create(definition, profile, fontBackend = fontBackend)

/**
 * Creates a versioned host owning both its native font backend and platform services.
 * A resource snapshot and its font settings stay pinned for this host's lifetime.
 * Tree disposal precedes font and platform release; construction failure leaves [platform] caller-owned.
 *
 * @param definition available one-shot screen definition.
 * @param profile immutable UI and font resources.
 * @param platform version services transferred only when construction succeeds.
 * @param fontBackend factory opening an independent owner-thread backend.
 * @return distinct owner-thread host.
 * @throws Throwable when transfer or font initialization fails; opened font resources are released.
 */
@InternalStrataRuntimeApi
public fun createMinecraftUiHost(
    definition: ScreenDefinition,
    profile: MinecraftUiProfile,
    platform: MinecraftUiPlatform,
    fontBackend: MinecraftFontBackendFactory,
): MinecraftUiHost = MinecraftHostImplementation.create(definition, profile, platform, fontBackend)

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
