package dev.s7a.strata.component

import dev.s7a.strata.internal.platform.currentOwner

/**
 * Caller-owned selection and navigation state for a generic SelectionList, confined to its construction execution owner.
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
    private val owner = currentOwner()
    private var currentSelection: K? = initialSelection

    /**
     * Current selected key, or null when no row is selected.
     */
    public val selectedKey: K?
        get() {
            checkOwner()
            return currentSelection
        }

    /**
     * Selects [key] and returns whether the value changed.
     */
    public fun select(key: K): Boolean {
        checkOwner()
        if (currentSelection == key) return false
        currentSelection = key
        listState.refresh()
        return true
    }

    /**
     * Clears the current selection and returns whether a value was removed.
     */
    public fun clearSelection(): Boolean {
        checkOwner()
        if (currentSelection == null) return false
        currentSelection = null
        listState.refresh()
        return true
    }

    private fun checkOwner() {
        check(currentOwner() == owner) { "SelectionListState requires its execution owner." }
    }
}
