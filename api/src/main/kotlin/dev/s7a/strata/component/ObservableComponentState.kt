package dev.s7a.strata.component

import dev.s7a.strata.internal.platform.currentOwner
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf

/**
 * Owner-confined observable value implementation shared by standard component states.
 */
internal class ObservableComponentState<T : Any>(
    initialValue: T,
    private val validate: (T) -> Unit,
) {
    private val owner = currentOwner()
    private val observers: MutableSet<(T) -> Unit> = LinkedHashSet()
    private val currentValue = mutableStateOf(initialValue)

    init {
        validate(initialValue)
    }

    /**
     * Returns the current value under the execution owner.
     */
    fun get(): T {
        checkOwner()
        return currentValue.value
    }

    /**
     * Validates and publishes [value], returning whether it differed from the current value.
     */
    fun set(value: T): Boolean {
        checkOwner()
        validate(value)
        if (currentValue.update(value).not()) return false
        observers.toList().forEach { observer -> observer(value) }
        return true
    }

    /**
     * Registers one owner-confined observer and returns its idempotent release handle.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    fun observe(callback: (T) -> Unit): ComponentStateSubscription {
        checkOwner()
        check(observers.add(callback)) { "A component state observer was already registered." }
        return ComponentStateSubscription {
            checkOwner()
            observers.remove(callback)
        }
    }

    /**
     * Verifies that the caller is the execution owner that created this state.
     *
     * @throws IllegalStateException when called from another execution owner.
     */
    fun checkOwner() {
        check(currentOwner() == owner) { "Component state requires its execution owner." }
    }
}
