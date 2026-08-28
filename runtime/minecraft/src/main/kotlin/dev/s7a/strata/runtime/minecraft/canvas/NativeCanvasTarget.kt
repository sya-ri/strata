package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * A version-owned color target and optional depth attachment, exclusively owned by a canvas device.
 *
 * The producer borrows the target only during its capture callback.
 * Native handles never enter a core draw command or a portable presentation snapshot.
 * All access, including physical destruction, is confined to the owning render thread.
 */
@InternalStrataRuntimeApi
public interface NativeCanvasTarget : AutoCloseable {
    /**
     * The immutable positive physical color extent.
     */
    public val size: IntSize

    /**
     * Requests release of the complete target set after its initialization, capture, and GUI work have completed.
     *
     * Success means the release request was accepted, not necessarily that physical storage has been destroyed.
     * The device calls [isDestroyed] after success and keeps the lifetime permit until it acknowledges physical destruction.
     * A successful call is never repeated; a failed call may be retried once during terminal device cleanup.
     * Implementations attempt all owned releases, retain ownership of resources whose release failed, and skip resources already released by an earlier attempt.
     * Release requests are idempotent per resource; the first failure remains primary with later failures suppressed.
     * This owner-thread operation does not wait for a deferred native destruction queue.
     * It may enqueue native destruction but must not issue new GPU rendering, transfer, or initialization work.
     *
     * @throws Throwable when a release request fails.
     */
    override fun close()

    /**
     * Acknowledges physical destruction after [close] has returned successfully.
     *
     * The default is valid only for adapters that destroy every target resource synchronously during [close].
     * Asynchronous adapters override this nonblocking owner-thread probe and return true only after every color, view, depth, and framebuffer resource has been physically destroyed.
     * A true result is permanent; neither the target nor any of its native resources may become usable again.
     * The device never invokes this method before a successful release request.
     *
     * @return whether the target-set lifetime permit may now be released.
     * @throws Throwable when destruction cannot be queried; ownership and the permit remain retained.
     */
    public fun isDestroyed(): Boolean = true
}
