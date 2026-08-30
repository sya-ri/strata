package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Transfers a partially allocated target to its device when allocation rollback cannot finish.
 *
 * A driver throws this carrier only while holding a pre-reserved target-set permit.
 * The device registers an initialization fence, quarantines the target and permit until terminal GPU completion and an acknowledged physical release, and rethrows [failure] as the primary allocation failure.
 * A queued asynchronous release alone never permits the driver to discard this ownership transfer.
 * Construction and all target access belong to the owning render thread; the carrier never enters a frame or snapshot.
 *
 * @param target exclusively transferred partial target with retryable, idempotent cleanup.
 * @param failure original allocation failure, with unsuccessful rollback failures already suppressed.
 * @param releaseRequested whether [target] already accepted a successful close request; the device then only awaits physical acknowledgement and never repeats close.
 */
@InternalStrataRuntimeApi
public class NativeCanvasAllocationFailure(
    public val target: NativeCanvasTarget,
    public val failure: Throwable,
    public val releaseRequested: Boolean = false,
) : RuntimeException("Native canvas allocation rollback did not complete.", failure)
