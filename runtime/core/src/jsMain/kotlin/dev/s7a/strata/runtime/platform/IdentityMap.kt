package dev.s7a.strata.runtime.platform

/**
 * Owner-agent native JavaScript registry comparing object keys by identity.
 * Clearing releases all current keys and values; no historical references are retained.
 */
internal actual class IdentityMap<K : Any, V> actual constructor() {
    private val entries: dynamic = js("new Map()")

    /**
     * Detached snapshot of current values, with no ordering guarantee.
     */
    actual val values: List<V> get() = js("Array.from")(entries.values()).unsafeCast<Array<V>>().toList()

    /**
     * Reads a value without invoking caller equality or hashing.
     */
    actual operator fun get(key: K): V? = if (entries.has(key) as Boolean) entries.get(key).unsafeCast<V>() else null

    /**
     * Replaces the value associated with the exact key identity.
     */
    actual operator fun set(
        key: K,
        value: V,
    ) {
        entries.set(key, value)
    }

    /**
     * Releases the exact key and returns its former value, or null.
     */
    actual fun remove(key: K): V? {
        val previous = get(key)
        entries.delete(key)
        return previous
    }

    /**
     * Releases all currently registered keys and values.
     */
    actual fun clear() {
        entries.clear()
    }
}
