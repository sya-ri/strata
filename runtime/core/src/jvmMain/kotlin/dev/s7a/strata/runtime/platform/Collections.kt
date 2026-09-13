package dev.s7a.strata.runtime.platform

import java.util.Collections as JavaCollections

/**
 * Preserves JVM read-only collection wrappers and their mutation failure contract.
 */
internal actual object Collections {
    /**
     * Wraps a caller-created snapshot as an immutable list.
     */
    actual fun <T> unmodifiableList(values: List<T>): List<T> = JavaCollections.unmodifiableList(values)
}
