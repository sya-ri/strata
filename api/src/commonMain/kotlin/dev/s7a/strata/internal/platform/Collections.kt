package dev.s7a.strata.internal.platform

/**
 * Returns platform read-only list snapshots without changing their JVM mutation contract.
 */
internal expect object Collections {
    /**
     * Wraps a caller-created snapshot as an immutable list.
     */
    fun <T> unmodifiableList(values: List<T>): List<T>
}
