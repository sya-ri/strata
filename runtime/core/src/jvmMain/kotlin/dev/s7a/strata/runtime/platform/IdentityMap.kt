package dev.s7a.strata.runtime.platform

import java.util.IdentityHashMap

/**
 * Owner-thread reference-keyed JVM registry; clearing releases its entire current contents.
 */
internal actual class IdentityMap<K : Any, V> actual constructor() {
    private val entries = IdentityHashMap<K, V>()

    /**
     * Detached snapshot of current values, with no ordering guarantee.
     */
    actual val values: List<V> get() = entries.values.toList()

    /**
     * Reads a value without invoking caller equality or hashing.
     */
    actual operator fun get(key: K): V? = entries[key]

    /**
     * Replaces the value associated with the exact key identity.
     */
    actual operator fun set(
        key: K,
        value: V,
    ) {
        entries[key] = value
    }

    /**
     * Releases the exact key and returns its former value, or null.
     */
    actual fun remove(key: K): V? = entries.remove(key)

    /**
     * Releases all currently registered keys and values.
     */
    actual fun clear() {
        entries.clear()
    }
}
