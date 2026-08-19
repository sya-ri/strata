package dev.s7a.strata.runtime

/**
 * Guards a runtime-created callback scope.
 *
 * The owner thread is checked before the active-lifetime check so callers receive the thread-confinement failure first.
 * Runtime closes the guard in a finally block when the callback returns or throws.
 */
internal class ScopeGuard(
    private val threadGuard: ThreadGuard,
) {
    private var closed: Boolean = false

    /**
     * Verifies that the scope is active on its owner thread.
     */
    fun check() {
        threadGuard.check()
        check(closed.not()) { "The callback scope is no longer active." }
    }

    /**
     * Closes the scope on its owner thread.
     */
    fun close() {
        threadGuard.check()
        closed = true
    }
}
