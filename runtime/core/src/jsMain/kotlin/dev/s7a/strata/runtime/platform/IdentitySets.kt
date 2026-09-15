package dev.s7a.strata.runtime.platform

/**
 * Creates a set backed by JavaScript object identity.
 */
internal actual fun <T : Any> identitySet(): MutableSet<T> = JsIdentitySet()

private class JsIdentitySet<T : Any> : AbstractMutableSet<T>() {
    private val values: dynamic = js("new Set()")
    override val size: Int get() = values.size.unsafeCast<Int>()

    override fun contains(element: T): Boolean = values.has(element).unsafeCast<Boolean>()

    override fun add(element: T): Boolean {
        if (contains(element)) return false
        values.add(element)
        return true
    }

    override fun remove(element: T): Boolean = values.delete(element).unsafeCast<Boolean>()

    override fun clear() {
        values.clear()
    }

    override fun iterator(): MutableIterator<T> {
        val snapshot = js("Array.from")(values).unsafeCast<Array<T>>().iterator()
        return object : MutableIterator<T> {
            private var current: T? = null

            override fun hasNext(): Boolean = snapshot.hasNext()

            override fun next(): T = snapshot.next().also { current = it }

            override fun remove() {
                this@JsIdentitySet.remove(checkNotNull(current) { "Iterator removal requires a preceding next." })
                current = null
            }
        }
    }
}
