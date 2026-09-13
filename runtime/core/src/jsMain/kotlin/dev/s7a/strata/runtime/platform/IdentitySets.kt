@file:Suppress("UnusedParameter") // The JavaScript array intrinsic consumes its Kotlin parameter.

package dev.s7a.strata.runtime.platform

/**
 * Creates a set backed by JavaScript object identity.
 */
internal actual fun <T : Any> identitySet(): MutableSet<T> = JsIdentitySet()

private class JsIdentitySet<T : Any> : AbstractMutableSet<T>() {
    private val values: dynamic = js("new Set()")
    private var revision = 0
    override val size: Int get() = values.size.unsafeCast<Int>()

    override fun contains(element: T): Boolean = values.has(element).unsafeCast<Boolean>()

    override fun add(element: T): Boolean {
        if (contains(element)) return false
        values.add(element)
        revision += 1
        return true
    }

    override fun remove(element: T): Boolean {
        if (values.delete(element).unsafeCast<Boolean>().not()) return false
        revision += 1
        return true
    }

    override fun clear() {
        if (isEmpty()) return
        values.clear()
        revision += 1
    }

    override fun iterator(): MutableIterator<T> {
        val snapshot = snapshot(values).unsafeCast<Array<T>>()
        return object : MutableIterator<T> {
            private var expectedRevision = revision
            private var index = 0
            private var removable = false

            private fun checkRevision() {
                if (expectedRevision != revision) throw ConcurrentModificationException()
            }

            override fun hasNext(): Boolean {
                checkRevision()
                return index < snapshot.size
            }

            override fun next(): T {
                checkRevision()
                if (snapshot.size <= index) throw NoSuchElementException()
                removable = true
                return snapshot[index++]
            }

            override fun remove() {
                checkRevision()
                check(removable) { "Iterator removal requires a preceding next." }
                this@JsIdentitySet.remove(snapshot[index - 1])
                expectedRevision = revision
                removable = false
            }
        }
    }

    private fun snapshot(value: dynamic): dynamic = js("Array.from(value)")
}
