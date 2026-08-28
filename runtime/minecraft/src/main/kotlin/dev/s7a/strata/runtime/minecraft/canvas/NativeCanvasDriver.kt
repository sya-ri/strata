package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Version-owned GPU operations borrowed by one render-thread canvas device.
 *
 * Every call runs on the device construction thread; implementations must not retain a screen or a retained node.
 * Targets contain ordinary nearest-sampled RGBA8 straight-alpha color, normalized to a top-left origin.
 * A returned target remains owned by the device until physical destruction.
 * A partial allocation whose rollback fails is transferred with [NativeCanvasAllocationFailure], retaining its reserved permit.
 * No operation performs a CPU readback.
 */
@InternalStrataRuntimeApi
public interface NativeCanvasDriver {
    /**
     * Allocates one target set after its lifetime permit has already been reserved.
     *
     * @param physicalSize positive physical pixel extent, validated with checked arithmetic by the caller.
     * @param depth whether to allocate a depth attachment alongside color.
     * @return a new exclusively owned target with the requested extent.
     * @throws NativeCanvasAllocationFailure when rollback has not acknowledged physical destruction, transferring the incomplete target and reserved permit, including an accepted asynchronous release request.
     * @throws Throwable when allocation fails without remaining native storage or pending uses of partially allocated resources.
     */
    public fun createTarget(
        physicalSize: IntSize,
        depth: Boolean,
    ): NativeCanvasTarget

    /**
     * Submits a fence after all GPU work issued so far on the device queue.
     *
     * An initialization fence protects work issued by target allocation, a capture fence protects a source lease, and a distinct fence issued after GUI consumption protects target reuse.
     * Implementations issue no explicit completion wait and never depend on unconsumed GUI commands becoming submitted.
     * The native backend may apply its own queue backpressure to already submitted work during submission.
     *
     * @return an exclusively owned nonblocking completion probe.
     * @throws Throwable when submission fails; the caller quarantines potentially used resources until device shutdown.
     */
    public fun fence(): NativeCanvasFence

    /**
     * Completes already submitted GPU work during terminal device shutdown only.
     *
     * The caller must first discard or consume every outstanding GUI queue.
     * Ordinary frames, detachment, and resource reload never call this blocking operation.
     *
     * @throws Throwable when GPU completion cannot be established; resources must then remain quarantined.
     */
    public fun finish()

    /**
     * Drains deferred native destruction during terminal device shutdown after submitted work has completed and resources have been closed.
     *
     * The device invokes this once after attempting every independent target, lease, fence, and renderer release, then queries target destruction acknowledgements.
     * Synchronous adapters need no additional work and inherit this default.
     * Asynchronous adapters finish their native destruction queues without depending on future GUI frames or unconsumed commands.
     * This owner-thread operation is never used during ordinary polling, screen cleanup, or resource reload.
     *
     * @throws Throwable when deferred destruction fails; independent acknowledgements are still attempted and unresolved permits remain retained.
     */
    public fun drainRetirements() {}
}
