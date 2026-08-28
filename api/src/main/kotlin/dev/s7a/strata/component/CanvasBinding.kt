package dev.s7a.strata.component

import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Attachment-scoped presentation state owned exclusively by one retained canvas node.
 *
 * Every method runs on the node's owner thread and must not mutate session state or retain a callback-lifetime paint scope.
 * Source notifications may only enqueue immutable revisions; the two cutoff methods transfer them before declarative frame work.
 * Native production runs at the versioned presenter's final-layout boundary, never in these methods.
 * A method failure propagates unchanged and causes normal retained cleanup; close must disable callbacks before attempting fallible resource cleanup.
 */
@InternalStrataRuntimeApi
public interface CanvasBinding : AutoCloseable {
    /**
     * Takes this binding's pending observation for the current frame without publishing it or invoking external callbacks.
     *
     * The engine captures every participating node before committing any captured observation.
     * Notifications arriving after this call remain pending for the next frame.
     * The default implementation is appropriate for native sources prepared only by the native presenter.
     */
    public fun captureFrame(): Unit = Unit

    /**
     * Commits the observation captured by [captureFrame] after every node has taken its cutoff.
     *
     * @return true only when cached paint output must be regenerated; false preserves the clean frame.
     * @throws Throwable when the captured observation is invalid or commit fails.
     */
    public fun commitFrame(): Boolean = false

    /**
     * Emits immutable portable image data or an immutable native identifier into the current local display list.
     *
     * This callback is cached and may not run in a later presentation.
     * It must never invoke a native producer, acquire a source lease, perform readback, or put handles, closures, nodes, or hosts in a command.
     * The destination is the complete positive logical [PaintScope.size], using whole-image stretch and nearest sampling.
     *
     * @param scope borrowed owner-thread paint scope, valid only until this method returns.
     * @throws Throwable when command emission fails; no partially produced frame is published.
     */
    public fun paint(scope: PaintScope)

    /**
     * Idempotently disables this attachment and releases its owner-thread references.
     *
     * Native resources still referenced by submitted GPU work must instead be transferred to the independent device owner until their completion fence signals.
     * The source itself remains externally owned and is never closed by this method.
     * Cleanup failures propagate after callbacks have been disabled and do not permit another resource acquisition.
     */
    override fun close()
}
