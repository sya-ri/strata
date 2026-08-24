package dev.s7a.strata.screen

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.ScreenPresenters

/**
 * Java facade for presenting platform-neutral screen definitions.
 *
 * Kotlin callers normally use [ScreenDefinition.open].
 */
public object Screens {
    /**
     * Presents [definition] through the runtime installed for the current platform.
     *
     * The operation has the same synchronous ownership, threading, and failure contract as [ScreenDefinition.open].
     *
     * @param definition available one-shot definition retained by the caller until a runtime transfers it.
     * @throws ScreenRuntimeUnavailableException when no platform runtime is installed.
     * @throws ScreenOpenThreadException when the runtime rejects the calling thread before transfer.
     * @throws ScreenDefinitionUnavailableException when [definition] was already transferred or closed.
     * @throws Throwable when the runtime fails during presentation.
     */
    @JvmStatic
    @OptIn(InternalStrataRuntimeApi::class)
    public fun open(definition: ScreenDefinition) {
        ScreenPresenters.present(definition)
    }
}
