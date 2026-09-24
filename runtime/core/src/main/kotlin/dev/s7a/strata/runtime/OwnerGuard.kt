package dev.s7a.strata.runtime

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner

/**
 * Verifies that retained runtime operations run under one execution owner.
 *
 * Construction captures the active serial runtime owner, or the current physical thread.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class OwnerGuard {
    private val owner = RuntimeExecutionOwner.current()

    /**
     * Fails when the current execution context is not the owner.
     *
     * @throws IllegalStateException when called from another execution owner.
     */
    internal fun check() {
        check(RuntimeExecutionOwner.current() == owner) {
            "This runtime object requires its owning execution context."
        }
    }

    /**
     * Returns whether the current execution context owns this guard without throwing.
     */
    internal fun isCurrentOwner(): Boolean = RuntimeExecutionOwner.current() == owner
}
