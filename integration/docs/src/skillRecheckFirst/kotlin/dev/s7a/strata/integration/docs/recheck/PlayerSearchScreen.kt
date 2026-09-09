package dev.s7a.strata.integration.docs.recheck

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.map
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow

/**
 * Returns an API-only, one-shot player search screen with independently observed elapsed time, actions, and results.
 *
 * Create and present the definition on the query state's owner thread; input actions execute on that thread.
 * Sources and the query remain caller-owned, and the caller publishes immutable result snapshots with unique IDs.
 * The definition retains list navigation outside every observed callback and keeps the result viewport attached while searching.
 * Source and action failures propagate through the installed runtime's ordinary cleanup contract.
 * The 200 by 180 panel has four-pixel padding and four gaps: 12 + 20 + 20 + 12 + 92 + 16 + 8 = 180.
 *
 * @param elapsedSeconds non-negative elapsed counter, displayed as completed minutes.
 * @param searching whether the search button is disabled and the status line displays a progress indication.
 * @param results immutable player snapshots; stable IDs preserve the visible anchor across insertions.
 * @param query caller-owned single-line value and editing state, left editable during searches.
 * @param onSearch owner-thread action invoked only while the search button is enabled.
 * @return unopened screen definition; call its public open method through an installed matching runtime.
 */
public fun playerSearchScreen(
    elapsedSeconds: StateSource<Long>,
    searching: StateSource<Boolean>,
    results: StateSource<List<PlayerEntry>>,
    query: TextFieldState,
    onSearch: () -> Unit,
): ScreenDefinition {
    val elapsedLabel = elapsedSeconds.map { seconds -> "Elapsed: ${seconds / 60} min" }
    val searchEnabled = searching.map { active -> active.not() }
    val searchLabel = searching.map { active -> if (active) "Searching..." else "Search" }
    val resultsEmpty = results.map { entries -> entries.isEmpty() }
    val resultState = VirtualListState<String>()
    val lineLayout = TextLayout.Multiline(maxLines = 1, overflow = TextOverflow.Ellipsis)

    return ScreenDefinition("Player search") {
        Column(
            modifier =
                Modifier.Empty
                    .size(200, 180)
                    .menuBackground()
                    .padding(4),
            spacing = 4,
        ) {
            Text(elapsedLabel, layout = lineLayout, modifier = Modifier.Empty.size(192, 12))
            TextField(state = query, size = IntSize(192, 20))
            Button(
                label = searchLabel,
                width = 192,
                enabled = searchEnabled,
                modifier = Modifier.Empty.onActivate(searchEnabled, onSearch),
            )
            Observe(searching, resultsEmpty, modifier = Modifier.Empty.size(192, 12)) { active, empty ->
                Text(
                    text =
                        when {
                            active -> "Searching..."
                            empty -> "No results"
                            else -> "Results"
                        },
                    layout = lineLayout,
                )
            }
            VirtualList(
                items = results,
                keyOf = { entry -> entry.id },
                state = resultState,
                viewportSize = IntSize(192, 92),
                rowHeight = 12,
            ) { entry ->
                Text(
                    text = entry.name,
                    layout = lineLayout,
                    modifier = Modifier.Empty.size(192, 12),
                )
            }
        }
    }
}
