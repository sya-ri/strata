package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * One render-thread-owned completion fence, independent of screens and presentation snapshots.
 *
 * The driver inserts it into the same ordered GPU queue as the protected work.
 * The device owns its lifetime and never waits for it during a frame or screen cleanup.
 */
@InternalStrataRuntimeApi
public interface NativeCanvasFence : AutoCloseable {
    /**
     * Tests completion without blocking or submitting unconsumed GUI work.
     *
     * @return true only when every preceding protected GPU operation has completed.
     * @throws Throwable when completion cannot be queried; protected resources remain retained.
     */
    public fun isSignalled(): Boolean

    /**
     * Releases this completion probe on its owning render thread without waiting.
     *
     * A later fence on the same ordered queue may supersede an unsignalled probe; releasing the probe must not cancel or invalidate the protected GPU work.
     * It may enqueue native destruction but must not issue new GPU work.
     *
     * @throws Throwable when releasing the native probe fails.
     */
    override fun close()
}
