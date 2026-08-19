package dev.s7a.strata.runtime

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Owner-thread local-state delegate used by [UiSession].
 *
 * The session supplies read and write checks so the delegate cannot bypass lifecycle or content evaluation rules.
 * The delegate marks its owner dirty only when a value changes by equality.
 * Mutable in-place changes that remain equal do not invalidate the session.
 *
 * @param T the local value type.
 * @param initial the initial local value.
 * @param checkReadable validates owner thread and readable lifecycle state.
 * @param checkWritable validates owner thread, writable lifecycle state, and content phase.
 * @param checkWritableAfterEquality revalidates writable state after arbitrary equality code.
 * @param markDirty records a changed value for a future frame.
 * @param beginMutation claims the session-wide mutation guard before equality and writing.
 * @param endMutation releases the session-wide mutation guard after equality and writing.
 */
internal class UiSessionLocalState<T>(
    initial: T,
    private val checkReadable: () -> Unit,
    private val checkWritable: () -> Unit,
    private val checkWritableAfterEquality: () -> Unit,
    private val markDirty: () -> Unit,
    private val beginMutation: () -> Unit,
    private val endMutation: () -> Unit,
) : ReadWriteProperty<Any?, T> {
    private var currentValue: T = initial

    /**
     * Reads the current local value after the session's lifecycle check.
     */
    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T {
        checkReadable()
        return currentValue
    }

    /**
     * Writes the local value and marks the session dirty when equality changes.
     */
    override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: T,
    ) {
        checkWritable()
        beginMutation()
        try {
            val changed = currentValue != value
            checkWritableAfterEquality()
            if (changed) {
                currentValue = value
                markDirty()
            }
        } finally {
            endMutation()
        }
    }
}
