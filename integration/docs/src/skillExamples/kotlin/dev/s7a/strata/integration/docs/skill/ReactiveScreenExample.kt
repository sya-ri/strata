package dev.s7a.strata.integration.docs.skill

// showcase-source-begin:skill-reactive
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextInputAppearance
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
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
    // Retain immutable frame pixels and the appearance beside the editor state.
    val frame = ImageSource.Pixels(createDrawImage(IntSize(3, 3), IntArray(9) { 0xFFF1F3F4.toInt() }))
    val focused =
        ImageSource.Pixels(
            createDrawImage(
                IntSize(3, 3),
                intArrayOf(
                    0xFF008040.toInt(),
                    0xFF008040.toInt(),
                    0xFF008040.toInt(),
                    0xFF008040.toInt(),
                    0xFFF1F3F4.toInt(),
                    0xFF008040.toInt(),
                    0xFF008040.toInt(),
                    0xFF008040.toInt(),
                    0xFF008040.toInt(),
                ),
            ),
        )
    val appearance = TextInputAppearance.Custom(frame, focused, ArgbColor(0xFF203020.toInt()))
    return ScreenDefinition("Conversation") {
        Column(spacing = 4) {
            Text(clock)
            Observe(loading) { active -> if (active) Text("Loading...") }
            VirtualList(items = history, keyOf = { it }, state = historyState, viewportSize = IntSize(160, 60), rowHeight = 12) { Text(it) }
            TextArea(draft, appearance, TextAreaViewport.Size(IntSize(160, 40)), textStyle = TextStyle.ContainerLabel)
            Button(sendLabel, enabled = enabled, modifier = Modifier.Empty.onActivate(enabled, onSend))
        }
    }
}
// showcase-source-end:skill-reactive
