package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Privileged owner-thread observer identity used by one retained pan-and-zoom viewport.
 *
 * The runtime owns this handle, uses it to suppress feedback from geometry publication, and closes it during detachment or disposal.
 * Release is idempotent and confined to the thread that created the originating [PanZoomState].
 */
@InternalStrataRuntimeApi
public class PanZoomStateObserver internal constructor(
    internal val token: Any,
    private var release: (() -> Unit)?,
) : AutoCloseable {
    /**
     * Releases this observer once on the state owner thread.
     */
    override fun close() {
        release?.invoke()
        release = null
    }
}
