package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Caller-owned owner-thread selected state for one Checkbox.
 *
 * Distinct writes synchronously invalidate every attached retained observer.
 * The state owns its boolean value but does not own attached component nodes.
 *
 * @param initialChecked initial selected value.
 */
public class CheckboxState(
    initialChecked: Boolean = false,
) {
    private val observable = ObservableComponentState(initialChecked) { _ -> }

    /**
     * Current selected value.
     */
    public var checked: Boolean
        get() = observable.get()
        set(value) {
            observable.set(value)
        }

    /**
     * Toggles and returns the next selected value.
     */
    public fun toggle(): Boolean {
        val next = checked.not()
        checked = next
        return next
    }

    /**
     * Installs one privileged retained observer.
     */
    @InternalStrataRuntimeApi
    public fun observe(callback: (Boolean) -> Unit): ComponentStateSubscription = observable.observe(callback)
}
