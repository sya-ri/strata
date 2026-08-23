@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.component

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.VirtualListElement
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.actionDispatcher
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Emits a fixed-row virtual viewport backed by a known finite indexed source.
 *
 * Only visible rows plus one overscan row on each edge are constructed and retained.
 * [state] supports index and key jumps and exposes its [ScrollState] for a separately placed optional Scrollbar.
 * Data access and row construction are owner-thread operations performed immediately before a dirty measure pass.
 *
 * @param T immutable row model type.
 * @param K stable row key type.
 * @receiver active owner-thread UI scope.
 * @param itemCount non-negative known source size.
 * @param itemAt indexed item accessor called only for materialized rows.
 * @param keyAt stable key accessor called only for materialized rows and key validation.
 * @param indexOfKey optional efficient key resolver; the default scans keys without materializing row content.
 * @param state caller-owned navigation and shared scroll state.
 * @param viewportSize exact positive viewport size.
 * @param rowHeight positive fixed logical row height.
 * @param scrollRate positive wheel multiplier.
 * @param canLoadLeading whether upward boundary input emits a leading load request through [modifier].
 * @param canLoadTrailing whether downward boundary input emits a trailing load request through [modifier].
 * @param modifier active viewport behavior.
 * @param key optional stable sibling identity.
 * @param content deferred callback that emits exactly one row root.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun <T : Any, K : Any> UiScope.VirtualList(
    itemCount: Int,
    itemAt: (Int) -> T,
    keyAt: (Int) -> K,
    state: VirtualListState<K>,
    viewportSize: IntSize,
    rowHeight: Int,
    indexOfKey: (K) -> Int? = { target -> (0 until itemCount).firstOrNull { index -> keyAt(index) == target } },
    scrollRate: Int = 10,
    canLoadLeading: Boolean = false,
    canLoadTrailing: Boolean = false,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    content: UiScope.(T) -> Unit,
) {
    checkUsable()
    val runtime = ComponentRuntimeBridge.currentOrNull()
    val factory: (Any) -> Element = { raw ->
        @Suppress("UNCHECKED_CAST")
        val item = raw as T
        if (runtime == null) {
            buildComponentTree { content(item) }
        } else {
            ComponentRuntimeBridge.evaluate(runtime) { content(item) }
        }
    }
    @Suppress("UNCHECKED_CAST")
    element(
        VirtualListElement(
            state = state as VirtualListState<Any>,
            itemCount = itemCount,
            itemAt = { index -> itemAt(index) },
            keyAt = { index -> keyAt(index) },
            indexOfKey = { raw -> indexOfKey(raw as K) },
            itemContent = factory,
            viewportSize = viewportSize,
            rowHeight = rowHeight,
            scrollRate = scrollRate,
            canLoadLeading = canLoadLeading,
            canLoadTrailing = canLoadTrailing,
            actions = modifier.actionDispatcher(),
            key = key,
            modifier = modifier,
        ),
    )
}

/**
 * Emits a fixed-row virtual viewport backed by an immutable in-memory list.
 *
 * @param T immutable row model type.
 * @param K stable row key type.
 * @receiver active owner-thread UI scope.
 * @param items immutable source snapshot; callers must replace rather than mutate it while attached.
 * @param keyOf stable row key mapping.
 * @param state caller-owned navigation and shared scroll state.
 * @param viewportSize exact positive viewport size.
 * @param rowHeight positive fixed logical row height.
 * @param scrollRate positive wheel multiplier.
 * @param canLoadLeading whether upward boundary input emits a leading load request through [modifier].
 * @param canLoadTrailing whether downward boundary input emits a trailing load request through [modifier].
 * @param modifier active viewport behavior.
 * @param key optional stable sibling identity.
 * @param content deferred callback that emits exactly one row root.
 */
public fun <T : Any, K : Any> UiScope.VirtualList(
    items: List<T>,
    keyOf: (T) -> K,
    state: VirtualListState<K>,
    viewportSize: IntSize,
    rowHeight: Int,
    scrollRate: Int = 10,
    canLoadLeading: Boolean = false,
    canLoadTrailing: Boolean = false,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    content: UiScope.(T) -> Unit,
) {
    val snapshot = items.toList()
    VirtualList(
        itemCount = snapshot.size,
        itemAt = snapshot::get,
        keyAt = { index -> keyOf(snapshot[index]) },
        state = state,
        viewportSize = viewportSize,
        rowHeight = rowHeight,
        indexOfKey = { target -> snapshot.indexOfFirst { item -> keyOf(item) == target }.takeIf { index -> 0 <= index } },
        scrollRate = scrollRate,
        canLoadLeading = canLoadLeading,
        canLoadTrailing = canLoadTrailing,
        modifier = modifier,
        key = key,
        content = content,
    )
}
