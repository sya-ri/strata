package dev.s7a.strata.spi

import dev.s7a.strata.internal.platform.EvaluationContext
import dev.s7a.strata.internal.platform.currentThreadOwner
import dev.s7a.strata.internal.platform.withValue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Serial ownership for a runtime whose scheduler may move work between physical threads.
 * Adapters must validate native ownership before entering; this scope grants no platform permissions.
 * Create state and sessions inside [run], and enter the same owner for every subsequent operation and cleanup.
 * Concurrent entry fails before executing user code. Nested entry restores the caller's context on every exit.
 * Ordinary callers outside a scope retain their existing construction-thread confinement.
 */
@InternalStrataRuntimeApi
@OptIn(ExperimentalAtomicApi::class)
public class RuntimeExecutionOwner {
    private val entered = AtomicBoolean(false)
    private val identity = ExecutionOwnerId.create()

    /**
     * Executes synchronous work exclusively under this owner, releasing the scope even when it throws.
     * The scope never propagates into asynchronous tasks; their scheduler must explicitly reenter it.
     */
    public fun <T> run(operation: () -> T): T {
        if (active.current === this) return operation()
        check(entered.compareAndSet(expectedValue = false, newValue = true)) { "The runtime owner is already executing." }
        return try {
            active.withValue(this, operation)
        } finally {
            entered.store(false)
        }
    }

    /**
     * Ownership captured by guards in the API, retained core, and transport adapters.
     */
    public companion object {
        private val active = EvaluationContext<RuntimeExecutionOwner>()

        /**
         * Returns the active serial owner, or the physical thread identity outside a runtime scope.
         * Compare identities with value equality; an identity itself cannot authorize entry into its scope.
         */
        public fun current(): ExecutionOwnerId = active.current?.identity ?: currentThreadOwner()
    }
}
