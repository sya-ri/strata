@file:JvmName("MinecraftRuntimeFactories")

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Creates one owner-thread host for a printable-ASCII compatibility profile by atomically consuming a definition.
 *
 * This overload never opens a font backend or discovers fonts from the operating system.
 * A profile declaring resource fonts requires an overload accepting [MinecraftFontBackendFactory].
 * Missing-backend rejection occurs before definition transfer, so the caller may retry with that overload or close [definition].
 * Successful construction leaves the definition empty and transfers metadata and content to the new host.
 *
 * @param definition the available one-shot definition.
 * @param profile complete immutable Minecraft assets with the compatibility glyph table, not a resource-font snapshot.
 * @return a distinct owner-thread host.
 * @throws IllegalArgumentException with "Resource fonts require a CPU font backend factory." when [profile] declares resource fonts.
 * @throws IllegalStateException when profile validation succeeds but [definition] was already transferred or closed.
 */
@InternalStrataRuntimeApi
public fun createMinecraftUiHost(
    definition: ScreenDefinition,
    profile: MinecraftUiProfile,
): MinecraftUiHost = MinecraftHostImplementation.create(definition, profile)

/**
 * Creates a compatibility-profile host with version-specific services by atomically consuming a definition.
 *
 * This overload accepts the printable-ASCII glyph table and never opens or discovers a font backend.
 * A profile declaring resource fonts requires the overload accepting both [platform] and [MinecraftFontBackendFactory].
 * Missing-backend rejection leaves [definition] and [platform] caller-owned, allowing a retry with the backend overload.
 * Successful construction transfers [platform] to the host, which refreshes it before frames and closes it on every terminal path.
 * This privileged overload is for a versioned runtime adapter; application code receives the resulting screen from that adapter instead.
 *
 * @param definition the available one-shot definition.
 * @param profile complete immutable Minecraft assets with the compatibility glyph table, not a resource-font snapshot.
 * @param platform version-specific services transferred to the host.
 * @return a distinct owner-thread host.
 * @throws IllegalArgumentException with "Resource fonts require a CPU font backend factory." when [profile] declares resource fonts.
 * @throws IllegalStateException when profile validation succeeds but [definition] was already transferred or closed.
 * @throws Throwable when construction fails; [platform] remains caller-owned unless host construction completes.
 */
@InternalStrataRuntimeApi
public fun createMinecraftUiHost(
    definition: ScreenDefinition,
    profile: MinecraftUiProfile,
    platform: MinecraftUiPlatform,
): MinecraftUiHost = MinecraftHostImplementation.create(definition, profile, platform)

/**
 * Creates an owner-thread host with independent native font ownership from a shareable resource snapshot.
 *
 * Use this overload when [profile] declares resource fonts; compatibility glyph profiles are also accepted and do not invoke [fontBackend].
 * The backend factory is borrowed only during construction; the opened backend is closed after the tree on every terminal path.
 * Backend initialization precedes definition transfer, so initialization failure leaves [definition] caller-owned for retry or close.
 * Once transfer occurs, the definition remains consumed even if later host construction fails; an opened backend is released on failure.
 * A factory whose open fails must release its own partially allocated resources before propagating the failure.
 * No Minecraft classes, display, graphics context, or operating-system fonts are needed by a CPU backend.
 *
 * @param definition available one-shot definition consumed after successful font initialization.
 * @param profile immutable profile containing resource fonts or compatibility glyphs.
 * @param fontBackend explicit factory for the target release's CPU font backend; no implicit backend lookup occurs.
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
 *
 * Resource-font profiles require this explicit backend overload; compatibility glyph profiles do not invoke [fontBackend].
 * A resource snapshot and its font settings stay pinned for this host's lifetime.
 * Font initialization occurs before definition transfer; initialization failure leaves both [definition] and [platform] caller-owned.
 * After transfer, later construction failure does not restore the definition, but releases an opened font backend.
 * Tree disposal precedes font and platform release; construction failure leaves [platform] caller-owned.
 * A factory whose open fails must release its own partially allocated resources before propagating the failure.
 *
 * @param definition available one-shot screen definition consumed after successful font initialization.
 * @param profile immutable UI and font resources.
 * @param platform version services transferred only when construction succeeds.
 * @param fontBackend explicit factory opening an independent owner-thread backend; no implicit backend lookup occurs.
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
