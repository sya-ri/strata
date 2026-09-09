package dev.s7a.strata.integration.docs.skill

// showcase-source-begin:skill-reactive
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.map

/**
 * A retained API-only screen whose independent clock cannot rebuild its editor or history.
 */
internal fun reactiveScreen(
    clock: StateSource<String>,
    sending: StateSource<Boolean>,
    loading: StateSource<Boolean>,
    history: StateSource<List<String>>,
    draft: TextAreaState,
    onSend: () -> Unit,
): ScreenDefinition {
    val enabled = sending.map { it.not() }
    val sendLabel = sending.map { if (it) "Sending..." else "Send" }
    val historyState = VirtualListState<String>()
    return ScreenDefinition("Conversation") {
        Column(spacing = 4) {
            Text(clock)
            Observe(loading) { active -> if (active) Text("Loading...") }
            VirtualList(items = history, keyOf = { it }, state = historyState, viewportSize = IntSize(160, 60), rowHeight = 12) { Text(it) }
            TextArea(draft, TextAreaViewport.Size(IntSize(160, 40)))
            Button(sendLabel, enabled = enabled, modifier = Modifier.Empty.onActivate(enabled, onSend))
        }
    }
}
// showcase-source-end:skill-reactive
