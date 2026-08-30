package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Opaque immutable identity for one complete portable prepared-list generation.
 *
 * The token is safe to retain from any thread and owns no native resource, callback, presenter, or device.
 * Only its creating [NativeGuiResources] may resolve it on the device render thread while it remains live.
 * Foreign or expired tokens fail before initialization or drawing; releasing an already retired owned token is harmless.
 */
@InternalStrataRuntimeApi
public class NativeGuiResourceSet internal constructor(
    internal val deviceId: Long,
    internal val value: Long,
)
