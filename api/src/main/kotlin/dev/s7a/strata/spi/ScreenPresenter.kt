package dev.s7a.strata.spi

import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.screen.ScreenOpenThreadException

/**
 * Privileged platform boundary that presents one API-owned screen definition.
 *
 * Implementations execute synchronously on the calling thread.
 * They must reject an invalid platform thread with [ScreenOpenThreadException] before transferring [ScreenDefinition], and must release all transferred state on every terminal failure or close path.
 * Application code must not implement or invoke this SPI.
 */
@InternalStrataRuntimeApi
public fun interface ScreenPresenter {
    /**
     * Presents [definition] or fails while preserving its ownership contract.
     *
     * @param definition available one-shot definition owned by the caller until transfer.
     * @throws ScreenOpenThreadException when the calling thread is invalid; implementations must throw before transfer.
     * @throws Throwable when presentation fails; after transfer the implementation owns all cleanup.
     */
    public fun present(definition: ScreenDefinition)
}
