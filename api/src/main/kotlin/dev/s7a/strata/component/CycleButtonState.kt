package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections

/**
 * Caller-owned owner-thread selected value for one generic CycleButton.
 *
 * The immutable nonempty option snapshot defines forward and backward wraparound order.
 * Every value must be unique by equality, and writes outside the option set fail without mutation.
 *
 * @param T immutable option type.
 * @param values nonempty unique option order.
 * @param initialValue initially selected member of [values].
 */
public class CycleButtonState<T : Any>(
    values: List<T>,
    initialValue: T,
) {
    public val values: List<T> = Collections.unmodifiableList(values.toList())
    private val observable: ObservableComponentState<T>

    init {
        require(this.values.isNotEmpty()) { "CycleButton values must not be empty." }
        require(this.values.distinct().size == this.values.size) { "CycleButton values must be unique." }
        require(initialValue in this.values) { "CycleButton initial value must belong to its values." }
        observable =
            ObservableComponentState(initialValue) { value ->
                require(value in this.values) { "CycleButton value must belong to its values." }
            }
    }

    /**
     * Creates state selecting the first member of a validated nonempty [values] list.
     */
    public constructor(values: List<T>) : this(values, requireFirst(values))

    /**
     * Current selected option.
     */
    public var value: T
        get() = observable.get()
        set(value) {
            observable.set(value)
        }

    /**
     * Selects and returns the next option with wraparound.
     */
    public fun next(): T = move(1)

    /**
     * Selects and returns the previous option with wraparound.
     */
    public fun previous(): T = move(-1)

    /**
     * Installs one privileged retained observer.
     */
    @InternalStrataRuntimeApi
    public fun observe(callback: (T) -> Unit): ComponentStateSubscription = observable.observe(callback)

    private fun move(delta: Int): T {
        val index = values.indexOf(value)
        val next = values[Math.floorMod(index + delta, values.size)]
        value = next
        return next
    }

    private companion object {
        fun <T : Any> requireFirst(values: List<T>): T {
            require(values.isNotEmpty()) { "CycleButton values must not be empty." }
            return values.first()
        }
    }
}
