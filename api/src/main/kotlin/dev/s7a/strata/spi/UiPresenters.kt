package dev.s7a.strata.spi

import dev.s7a.strata.screen.ScreenRuntimeUnavailableException
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Process-wide installation of the platform presenter, never of an event's current session.
 */
@InternalStrataRuntimeApi
@OptIn(ExperimentalAtomicApi::class)
public object UiPresenters {
    private val current = AtomicReference<Entry?>(null)

    /**
     * Installs exactly one presenter; the returned idempotent handle removes only this installation.
     */
    public fun install(presenter: UiPresenter): AutoCloseable {
        val entry = Entry(presenter)
        check(current.compareAndSet(null, entry)) { "A Strata UI runtime is already installed." }
        return Registration(entry)
    }

    private class Entry(
        val presenter: UiPresenter,
    )

    private class Registration(
        entry: Entry,
    ) : AutoCloseable {
        private val entry = AtomicReference<Entry?>(entry)

        override fun close() {
            val installed = entry.exchange(null) ?: return
            current.compareAndSet(installed, null)
        }
    }

    /**
     * Calls the presenter synchronously; missing runtime leaves the definition available.
     */
    public fun present(definition: UiDefinition): UiSession = (current.load()?.presenter ?: throw ScreenRuntimeUnavailableException()).present(definition)
}
