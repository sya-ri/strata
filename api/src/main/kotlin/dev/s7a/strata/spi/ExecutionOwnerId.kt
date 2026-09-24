package dev.s7a.strata.spi

import kotlin.jvm.JvmInline

/**
 * Opaque identity of one runtime execution owner or physical thread outside an explicit runtime scope.
 * Equality compares ownership, including after boxing; the identity grants no permission to enter its owner.
 */
@JvmInline
public value class ExecutionOwnerId private constructor(
    private val identity: Any,
) {
    /**
     * Wraps private runtime markers whose equality is by reference.
     */
    internal companion object {
        /**
         * Wraps [identity], retaining the same private reference-equality marker for its owner's lifetime.
         * Omitting the marker creates a distinct owner identity.
         */
        fun create(identity: Any = Any()): ExecutionOwnerId = ExecutionOwnerId(identity)
    }
}
