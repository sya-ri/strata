package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable identity for one portable presenter across frame replacement and detach/reattach.
 *
 * Only [NativeGuiResources.createOwnerId] creates valid identities for its device.
 * Retaining this value from any thread retains no presenter, native resource, or device owner.
 * Its render-thread owner enforces a separate three-set portable-resource bound for this identity.
 */
@InternalStrataRuntimeApi
public class NativeGuiResourceOwnerId internal constructor(
    internal val deviceId: Long,
    internal val value: Long,
)
