package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections
import kotlin.enums.enumEntries

/**
 * Caller-owned selected value and display conversion for one generic CycleButton.
 *
 * The immutable nonempty option snapshot defines forward and backward wraparound order.
 * Every value must be unique by equality, and writes outside the option set fail without mutation.
 * The primary list constructor uses [Any.toString], while the collection constructor and enum factory retain their supplied conversion for the state lifetime.
 * Selection, observation, and display conversion are confined to the thread that creates the state.
 * The display conversion runs synchronously when called directly or when a CycleButton snapshots its labels, is not used for option identity, and propagates its exceptions unchanged.
 *
 * @param T immutable option type.
 * @param values nonempty unique option order.
 * @param initialValue initially selected member of [values].
 * @throws IllegalArgumentException when [values] is empty or contains equal members, or when [initialValue] is outside [values].
 */
public class CycleButtonState<T : Any>(
    values: List<T>,
    initialValue: T,
) {
    public val values: List<T> = Collections.unmodifiableList(values.toList())
    private var valueToString: (T) -> String = { value -> value.toString() }
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
     * Creates state from one caller-owned collection snapshot and explicit display conversion.
     *
     * Collection iteration order defines cycling order.
     * The collection is copied before validation and is not retained.
     * The conversion is retained for the state lifetime, runs only on the creating thread, and propagates its exceptions unchanged when invoked.
     *
     * @param values nonempty unique option order.
     * @param initialValue initially selected member of [values].
     * @param toString synchronous display conversion used by [format].
     * @throws IllegalArgumentException when [values] is empty or contains equal members, or when [initialValue] is outside [values].
     */
    public constructor(
        values: Collection<T>,
        initialValue: T,
        toString: (T) -> String,
    ) : this(values.toList(), initialValue) {
        valueToString = toString
    }

    /**
     * Creates state selecting the first member of a validated nonempty [values] list.
     *
     * @param values nonempty unique option order formatted through [Any.toString].
     * @throws IllegalArgumentException when [values] is empty or contains equal members.
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
     * Formats one option through the conversion supplied at construction.
     *
     * The canonical member from the immutable option snapshot is passed to the conversion.
     *
     * @param value option equal to one member of [values].
     * @return display string produced synchronously on the state-owning thread.
     * @throws IllegalArgumentException when [value] is outside [values].
     * @throws IllegalStateException when called from a thread other than the creating thread.
     */
    public fun format(value: T): String {
        observable.checkOwnerThread()
        val index = values.indexOf(value)
        require(0 <= index) { "CycleButton formatted value must belong to its values." }
        return valueToString(values[index])
    }

    /**
     * Formats a canonical option already obtained from the immutable [values] snapshot.
     *
     * This bounded internal path avoids another membership scan while a CycleButton snapshots every label.
     * The caller must supply the canonical snapshot member rather than an arbitrary equal object.
     * Conversion exceptions propagate unchanged.
     *
     * @param value canonical member obtained from [values].
     * @return display string produced synchronously on the state-owning thread.
     * @throws IllegalStateException when called from a thread other than the creating thread.
     */
    internal fun formatKnownMember(value: T): String {
        observable.checkOwnerThread()
        return valueToString(value)
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

    /**
     * Type-specific state factories.
     */
    public companion object {
        /**
         * Creates state containing every constant of [E] in declaration order.
         *
         * @param E enum option type inferred from [initialValue].
         * @param initialValue initially selected enum constant.
         * @param toString synchronous display conversion retained for the state lifetime, defaulting to [Enum.name].
         * @return caller-owned state over the complete enum constant set, confined to the calling thread.
         */
        public inline operator fun <reified E : Enum<E>> invoke(
            initialValue: E,
            noinline toString: (E) -> String = { value -> value.name },
        ): CycleButtonState<E> = CycleButtonState(enumEntries<E>(), initialValue, toString)

        private fun <T : Any> requireFirst(values: List<T>): T {
            require(values.isNotEmpty()) { "CycleButton values must not be empty." }
            return values.first()
        }
    }
}
