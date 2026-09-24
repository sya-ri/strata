package dev.s7a.strata.runtime

/**
 * Guards a runtime-created callback scope.
 *
 * The execution owner is checked before the active-lifetime check so callers receive the ownership failure first.
 * Runtime closes the guard in a finally block when the callback returns or throws.
 */
internal class ScopeGuard(
    private val ownerGuard: OwnerGuard,
) {
    private var closed: Boolean = false

    /**
     * Verifies that the scope is active under its execution owner.
     */
    fun check() {
        ownerGuard.check()
        check(closed.not()) { "The callback scope is no longer active." }
    }

    /**
     * Closes the scope under its execution owner.
     */
    fun close() {
        ownerGuard.check()
        closed = true
    }
}
