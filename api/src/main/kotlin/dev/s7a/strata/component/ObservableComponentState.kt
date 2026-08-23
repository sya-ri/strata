package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owner-thread observable value implementation shared by standard component states.
 */
internal class ObservableComponentState<T : Any>(
    initialValue: T,
    private val validate: (T) -> Unit,
) {
    private val ownerThread = Thread.currentThread()
    private val observers: MutableSet<(T) -> Unit> = LinkedHashSet()
    private var currentValue = initialValue

    init {
        validate(initialValue)
    }

    /**
     * Returns the current value on the owning thread.
     */
    fun get(): T {
        checkThread()
        return currentValue
    }

    /**
     * Validates and publishes [value], returning whether it differed from the current value.
     */
    fun set(value: T): Boolean {
        checkThread()
        validate(value)
        if (currentValue == value) return false
        currentValue = value
        observers.toList().forEach { observer -> observer(value) }
        return true
    }

    /**
     * Registers one owner-thread observer and returns its idempotent release handle.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    fun observe(callback: (T) -> Unit): ComponentStateSubscription {
        checkThread()
        check(observers.add(callback)) { "A component state observer was already registered." }
        return ComponentStateSubscription {
            checkThread()
            observers.remove(callback)
        }
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) { "Component state requires its creator thread." }
    }
}
