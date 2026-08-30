package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Externally owned source of image or native drawing output for one or more canvases.
 *
 * A source is an immutable description and does not own a screen, retained node, or attachment.
 * Each attached canvas opens its own binding on the owning UI thread; replacing the source or detaching that session closes the binding without closing the source.
 * Source identity, rather than value equality, determines whether reconciliation replaces an existing binding.
 * Native implementations belong in the matching versioned runtime and must keep their handles and producer callbacks outside draw commands.
 */
public fun interface CanvasSource {
    /**
     * Opens one attachment-scoped binding without evaluating a native producer.
     *
     * @param canvasId immutable node identity retained across source replacement and session reattachment, containing no node or host reference.
     * @return a fresh binding transferred to the canvas node and confined to its owner thread.
     * @throws Throwable when acquisition fails; the source must release anything acquired before it can return a binding.
     */
    @InternalStrataRuntimeApi
    public fun open(canvasId: CanvasId): CanvasBinding
}
