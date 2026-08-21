package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable Minecraft asset and metric snapshot consumed by the common runtime.
 *
 * A version adapter creates a complete profile from one resource state and may reuse it across owner threads and screen hosts.
 * The profile retains immutable [DrawImage] values, closes no caller resource, and has referential identity.
 */
@InternalStrataRuntimeApi
public sealed interface MinecraftUiProfile
