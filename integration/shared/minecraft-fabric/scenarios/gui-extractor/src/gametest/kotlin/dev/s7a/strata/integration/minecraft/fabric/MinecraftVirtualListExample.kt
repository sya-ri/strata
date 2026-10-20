package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:virtual-list
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.ui.UiDefinition

/**
 * Builds a finite VirtualList while materializing only visible rows.
 */
internal fun createVirtualListShowcaseScreenDefinition(): UiDefinition {
    val items = (1..100).map { index -> "Log entry $index" }
    val state = VirtualListState<String>()
    return UiDefinition("VirtualList showcase") {
        VirtualList(items, { item -> item }, state, IntSize(120, 48), rowHeight = 16) { item ->
            Stack(
                modifier = Modifier.size(120, 16).background(ArgbColor(0xFF202020.toInt())),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(item)
            }
        }
    }
}
// showcase-source-end:virtual-list
