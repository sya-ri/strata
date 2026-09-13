package dev.s7a.strata.runtime.platform

/**
 * Exposes immutable list snapshots in JavaScript without a mutable collection implementation.
 */
internal actual object Collections {
    /**
     * Exposes a detached map through immutable entries and read-only collection views.
     */
    actual fun <K, V> unmodifiableMap(values: Map<K, V>): Map<K, V> = buildMap { putAll(values) }

    /**
     * Wraps a caller-created snapshot as an immutable list.
     */
    actual fun <T> unmodifiableList(values: List<T>): List<T> {
        val snapshot = values.toList()
        return object : AbstractList<T>() {
            override val size: Int get() = snapshot.size

            override fun get(index: Int): T = snapshot[index]
        }
    }
}
