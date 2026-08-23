package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Privileged owner-thread observer identity shared with one retained scroll node.
 *
 * The runtime owns the handle and closes it during permanent node disposal.
 * Calls are confined to the thread that owns the originating [ScrollState], and release is idempotent.
 */
@InternalStrataRuntimeApi
public class ScrollStateObserver internal constructor(
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
