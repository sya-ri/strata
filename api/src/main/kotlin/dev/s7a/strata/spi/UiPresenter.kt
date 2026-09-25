package dev.s7a.strata.spi

import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession

/**
 * Platform presenter: validate the calling thread before transferring contents, then own every cleanup path.
 */
@InternalStrataRuntimeApi
public fun interface UiPresenter {
    /**
     * Consumes an available definition and returns its owner-thread session handle.
     */
    public fun present(definition: UiDefinition): UiSession
}
