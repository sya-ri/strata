package dev.s7a.strata.examples.paper

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardInputFilter
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.modifier.onPreedit
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition

/**
 * Compiled fixed-policy input subscriptions whose callbacks execute on the server.
 */
public object PaperInputScreens {
    /**
     * Creates independent editor state and subscribes only to Enter, preedit, and secondary presses.
     */
    public fun editor(): UiDefinition {
        val text = TextFieldState("", 128)
        val notice = mutableStateOf("Press Enter to submit.")
        return UiDefinition("Input notifications") {
            Column(spacing = 4) {
                Text(notice.value)
                TextField(
                    text,
                    IntSize(180, 20),
                    modifier =
                        Modifier.Empty
                            .initialFocus()
                            .onKeyPress(InputResult.Consumed, KeyboardInputFilter(setOf(KeyCode.Enter))) {
                                notice.value = "Submitted: ${text.value}"
                            }.onPreedit(InputResult.Ignored) { event ->
                                notice.value = "Composing: ${event.fullText}"
                            }.onPress(InputResult.Ignored, PointerButton.Secondary) { _, position ->
                                notice.value = "Secondary press at ${position.x}, ${position.y}."
                            },
                )
            }
        }
    }
}
