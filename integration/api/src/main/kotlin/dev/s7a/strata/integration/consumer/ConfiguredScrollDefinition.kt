package dev.s7a.strata.integration.consumer

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.DragScrollOptions
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.ui.UiDefinition

/**
 * Compiles the public examples for disabled dragging, disabled inertia, and tuned virtual-list physics.
 * Navigation state is retained outside reevaluated content and shared through the existing component parameters.
 */
public fun createConfiguredScrollDefinition(): UiDefinition {
    val wheelOnly = ScrollState(dragScroll = DragScrollOptions(enabled = false))
    val dragOnly = ScrollState(dragScroll = DragScrollOptions(inertiaEnabled = false))
    val list =
        VirtualListState<Int>(
            scrollState = ScrollState(dragScroll = DragScrollOptions(dragThreshold = 8.0, deceleration = 12.0)),
        )
    return UiDefinition("Configured scrolling") {
        Column(spacing = 8) {
            ScrollArea(wheelOnly, Modifier.size(160, 60)) {
                Column {
                    repeat(10) { Button("Item $it", width = 140) }
                }
            }
            ScrollArea(dragOnly, Modifier.size(160, 60)) {
                Column {
                    repeat(10) { Button("Item $it", width = 140) }
                }
            }
            VirtualList(100, { it }, { it }, list, IntSize(160, 60), 20) {
                Button("Item $it", width = 140)
            }
        }
    }
}
