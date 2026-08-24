package dev.s7a.strata.integration.consumer

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onLeadingItemsRequested
import dev.s7a.strata.modifier.onTrailingItemsRequested
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Creates an API-only dynamic list whose load actions prepend and append caller-owned rows.
 *
 * The caller must invoke and open the returned definition on the thread that created [state].
 * Load callbacks complete each mutation before calling [VirtualListState.refresh], so the retained viewport can preserve its stable-key anchor.
 *
 * @param items mutable owner-thread row source with stable integer values.
 * @param state caller-owned navigation and refresh state.
 * @return one-shot platform-neutral screen definition.
 */
@Suppress("unused") // This externally callable fixture proves dynamic-list authoring against the API-only classpath.
public fun createApiOnlyDynamicVirtualListDefinition(
    items: MutableList<Int>,
    state: VirtualListState<Int>,
): ScreenDefinition =
    ScreenDefinition("API-only dynamic virtual list") {
        val loadModifier =
            Modifier.Empty
                .onLeadingItemsRequested { request ->
                    val first = items.firstOrNull() ?: 0
                    val start = Math.subtractExact(first, request.suggestedCount)
                    items.addAll(0, (start until first).toList())
                    state.refresh()
                }.onTrailingItemsRequested { request ->
                    val next = Math.addExact(items.lastOrNull() ?: -1, 1)
                    val endExclusive = Math.addExact(next, request.suggestedCount)
                    items.addAll(next until endExclusive)
                    state.refresh()
                }
        VirtualList(
            itemCount = { items.size },
            itemAt = items::get,
            keyAt = items::get,
            state = state,
            viewportSize = IntSize(120, 48),
            rowHeight = 12,
            canLoadLeading = true,
            canLoadTrailing = true,
            modifier = loadModifier,
        ) {
            Spacer()
        }
    }
