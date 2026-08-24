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
        checkOwnerThread()
        return currentValue
    }

    /**
     * Validates and publishes [value], returning whether it differed from the current value.
     */
    fun set(value: T): Boolean {
        checkOwnerThread()
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
        checkOwnerThread()
        check(observers.add(callback)) { "A component state observer was already registered." }
        return ComponentStateSubscription {
            checkOwnerThread()
            observers.remove(callback)
        }
    }

    /**
     * Verifies that the caller is the thread that created this state.
     *
     * @throws IllegalStateException when called from another thread.
     */
    fun checkOwnerThread() {
        check(Thread.currentThread() === ownerThread) { "Component state requires its creator thread." }
    }
}
