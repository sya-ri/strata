package dev.s7a.strata.component

/**
 * Caller-owned owner-thread selection and navigation state for a generic SelectionList.
 *
 * A nullable key represents no selection, while distinct writes reconstruct only the currently materialized rows.
 *
 * @param K stable item key type.
 * @property listState virtual navigation and shared Scrollbar state.
 * @param initialSelection optional initially selected key.
 */
public class SelectionListState<K : Any>(
    public val listState: VirtualListState<K> = VirtualListState(),
    initialSelection: K? = null,
) {
    private val ownerThread = Thread.currentThread()
    private var currentSelection: K? = initialSelection

    /**
     * Current selected key, or null when no row is selected.
     */
    public val selectedKey: K?
        get() {
            checkThread()
            return currentSelection
        }

    /**
     * Selects [key] and returns whether the value changed.
     */
    public fun select(key: K): Boolean {
        checkThread()
        if (currentSelection == key) return false
        currentSelection = key
        listState.refresh()
        return true
    }

    /**
     * Clears the current selection and returns whether a value was removed.
     */
    public fun clearSelection(): Boolean {
        checkThread()
        if (currentSelection == null) return false
        currentSelection = null
        listState.refresh()
        return true
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) { "SelectionListState requires its creator thread." }
    }
}
