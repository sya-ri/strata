package dev.s7a.strata.integration.docs.skill

import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Scrollbar
import dev.s7a.strata.component.SelectionList
import dev.s7a.strata.component.SelectionListState
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.ui.UiDefinition

/**
 * Derives the list and scrollbar rectangles from the same caller-supplied allocation.
 * The caller retains selection and scroll state independently of this declaration.
 */
public fun selectionViewportScreen(
    viewport: IntSize,
    items: List<String>,
    state: SelectionListState<String>,
): UiDefinition {
    require(12 <= viewport.width)
    val listViewport = IntSize(viewport.width - 12, viewport.height)
    return UiDefinition("Selection viewport") {
        Row(modifier = Modifier.size(viewport.width, viewport.height), spacing = 4) {
            SelectionList(
                items = items,
                keyOf = { it },
                state = state,
                viewportSize = listViewport,
                rowHeight = 28,
            ) { item ->
                Text(item, layout = TextLayout.Multiline(maxLines = 2), modifier = Modifier.size(listViewport.width, 28))
            }
            Scrollbar(state.listState.scrollState, modifier = Modifier.size(8, listViewport.height))
        }
    }
}
