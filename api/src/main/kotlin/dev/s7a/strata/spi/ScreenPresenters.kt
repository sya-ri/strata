package dev.s7a.strata.spi

import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.screen.ScreenRuntimeUnavailableException
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide bridge between the public screen API and one installed platform runtime.
 *
 * Registration and removal are thread-safe.
 * Exactly one presenter may be installed at a time, while a presentation call executes synchronously through the presenter captured at call entry.
 * Application code must not access this bridge.
 */
@InternalStrataRuntimeApi
public object ScreenPresenters {
    private val current = AtomicReference<Entry?>()

    /**
     * Installs [presenter] for the current platform-runtime lifetime.
     *
     * @param presenter synchronous runtime presenter retained until registration close.
     * @return the unique handle that removes this exact installation.
     * @throws IllegalStateException when a presenter is already installed.
     */
    public fun install(presenter: ScreenPresenter): ScreenPresenterRegistration {
        val entry = Entry(presenter)
        check(current.compareAndSet(null, entry)) { "A Strata screen runtime is already installed." }
        return Registration(entry)
    }

    /**
     * Presents [definition] through the presenter current at call entry.
     *
     * @param definition available one-shot definition.
     * @throws ScreenRuntimeUnavailableException when no presenter is installed; the definition remains caller-owned.
     * @throws Throwable when the presenter rejects or fails the operation.
     */
    public fun present(definition: ScreenDefinition) {
        val presenter = current.get()?.presenter ?: throw ScreenRuntimeUnavailableException()
        presenter.present(definition)
    }

    private class Entry(
        val presenter: ScreenPresenter,
    )

    private class Registration(
        entry: Entry,
    ) : ScreenPresenterRegistration {
        private val entry = AtomicReference(entry)

        override fun close() {
            val installed = entry.getAndSet(null) ?: return
            current.compareAndSet(installed, null)
        }
    }
}
