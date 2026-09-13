package dev.s7a.strata.internal.platform

/**
 * Exposes immutable list snapshots in JavaScript without a mutable collection implementation.
 */
internal actual object Collections {
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
