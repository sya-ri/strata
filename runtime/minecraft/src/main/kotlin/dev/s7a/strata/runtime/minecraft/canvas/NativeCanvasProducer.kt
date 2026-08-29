package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Attachment-owned native producer created independently for each retained canvas attachment.
 *
 * The source and its authoritative state remain externally owned.
 * Producer construction issues no GPU work; any renderer initialization belongs inside the capture's render callback after target reservation.
 * All calls run on the device thread, outside declaration, measurement, and cached painting.
 * An implementation must not draw into the current GUI or framebuffer.
 */
@InternalStrataRuntimeApi
public interface NativeCanvasProducer : AutoCloseable {
    /**
     * Acquires an immutable-content source lease before issuing any GPU work.
     *
     * The device calls this at most once per actual presentation and only after reserving an available target.
     * A returned capture remains owned by the device until its capture-completion fence signals.
     *
     * @return a capture lease, or null to keep the last committed generation and its snapshot unchanged.
     * @throws Throwable when acquisition fails, without transferring a lease or issuing GPU work.
     */
    public fun capture(): NativeCanvasCapture?

    /**
     * Releases attachment-owned renderer resources after their last GPU use has completed.
     *
     * The external source is not closed.
     * This method is called at most once after the device establishes completion of the producer's final GPU use, including ordinary retirement and successful terminal cleanup.
     * If terminal completion cannot be established, the producer remains quarantined and this method is not called.
     * It may enqueue native destruction but must not issue new GPU work; ordinary retirement follows completed fences and terminal retirement follows successful device completion.
     *
     * @throws Throwable when renderer cleanup fails; other device cleanup still proceeds.
     */
    override fun close()
}
