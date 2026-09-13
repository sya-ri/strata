package dev.s7a.strata.runtime.platform

/**
 * Owner-agent reference-keyed registry with no historical entries; clear releases all retained keys and values.
 * Keys compare by identity without invoking caller equality or hashing. Values returns a detached current snapshot.
 */
internal expect class IdentityMap<K : Any, V>() {
    /**
     * Detached snapshot of current values, with no ordering guarantee.
     */
    val values: List<V>

    /**
     * Reads the value for the exact key identity, or null when absent.
     */
    operator fun get(key: K): V?

    /**
     * Associates the exact key identity with a value, replacing its previous value.
     */
    operator fun set(
        key: K,
        value: V,
    )

    /**
     * Releases the exact key identity and returns its former value, or null.
     */
    fun remove(key: K): V?

    /**
     * Releases every current key and value on the owning agent.
     */
    fun clear()
}
