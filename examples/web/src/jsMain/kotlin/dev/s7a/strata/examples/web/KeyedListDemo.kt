package dev.s7a.strata.examples.web

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Text
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.width
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition

/**
 * Creates a bounded list whose stable keys follow items across order changes and removals.
 */
internal fun keyedListDemo(): UiDefinition {
    val items = mutableStateOf(listOf(1, 2, 3))
    var nextItem = 4
    return UiDefinition("Keyed list") {
        Column(spacing = 12) {
            Text("Items: ${items.value.size} / 5", modifier = Modifier.Empty.width(304))
            Row(spacing = 8) {
                actionButton("Add", enabled = items.value.size < 5) {
                    items.value = items.value + nextItem
                    nextItem += 1
                }
                actionButton("Reverse", enabled = 1 < items.value.size) { items.value = items.value.reversed() }
                actionButton("Reset") {
                    items.value = listOf(1, 2, 3)
                    nextItem = 4
                }
            }
            Column(spacing = 8) {
                if (items.value.isEmpty()) Text("No items. Add one to begin.", modifier = Modifier.Empty.width(304))
                items.value.forEach { item ->
                    Row(key = ElementKey(item), spacing = 16) {
                        Text("Item $item", modifier = Modifier.Empty.width(80))
                        actionButton("Remove $item", width = 112) { items.value = items.value.filterNot { it == item } }
                    }
                }
            }
        }
    }
}
