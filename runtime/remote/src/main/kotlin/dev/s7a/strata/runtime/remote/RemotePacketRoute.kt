package dev.s7a.strata.runtime.remote

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable validated routing metadata, detached from native input arrays, buffers and adapter callback objects.
 * Inspection shares the public native decoder's complete outer validation and leaves the inner bytes opaque.
 * This result retains only kind/address metadata; owned endpoint decoding and inbox snapshots remain separate.
 */
@InternalStrataRuntimeApi
public sealed interface RemotePacketRoute {
    /**
     * Valid native discovery, with no trailing bytes.
     */
    public data object Discovery : RemotePacketRoute

    /**
     * Valid outer frame address; no payload storage or native input reference is retained.
     */
    public class Frame internal constructor(
        public val address: RemoteAddress,
    ) : RemotePacketRoute
}
