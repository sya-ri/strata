@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.component

import dev.s7a.strata.action.ComponentActions
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.StackElement
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.actionDispatcher
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.semantics.Semantics

/**
 * Emits a selectable fixed-row virtual list backed by an immutable in-memory snapshot.
 *
 * Selection is caller-owned, rows remain keyed, and only visible rows are constructed.
 * A successful primary press updates [state] before emitting [ComponentActions.SelectionChange] through [modifier].
 *
 * @param T immutable row model type.
 * @param K stable row key type.
 * @receiver active owner-thread UI scope.
 * @param items immutable source snapshot.
 * @param keyOf stable row key mapping.
 * @param state caller-owned selection and navigation state.
 * @param viewportSize exact positive viewport size.
 * @param rowHeight positive fixed row height.
 * @param canLoadLeading whether upward boundary input requests prepended rows.
 * @param canLoadTrailing whether downward boundary input requests appended rows.
 * @param scrollRate positive wheel multiplier.
 * @param modifier active viewport and typed action behavior.
 * @param key optional stable sibling identity.
 * @param content deferred callback that emits exactly one row root.
 */
public fun <T : Any, K : Any> UiScope.SelectionList(
    items: List<T>,
    keyOf: (T) -> K,
    state: SelectionListState<K>,
    viewportSize: IntSize,
    rowHeight: Int,
    canLoadLeading: Boolean = false,
    canLoadTrailing: Boolean = false,
    scrollRate: Int = 10,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    content: UiScope.(T) -> Unit,
) {
    val actions = modifier.actionDispatcher()
    VirtualList(
        items = items,
        keyOf = keyOf,
        state = state.listState,
        viewportSize = viewportSize,
        rowHeight = rowHeight,
        canLoadLeading = canLoadLeading,
        canLoadTrailing = canLoadTrailing,
        scrollRate = scrollRate,
        modifier = modifier,
        key = key,
    ) { item ->
        val itemKey = keyOf(item)
        val row = buildComponentTree { content(item) }
        val rowModifier =
            Modifier.Empty
                .semantics(Semantics(selected = state.selectedKey == itemKey))
                .onPress {
                    if (state.select(itemKey)) actions.dispatch(ComponentActions.SelectionChange, itemKey)
                }
        element(
            StackElement(
                contentAlignment = Alignment.TopStart,
                key = null,
                children = listOf(row),
                modifier = rowModifier,
            ),
        )
    }
}
