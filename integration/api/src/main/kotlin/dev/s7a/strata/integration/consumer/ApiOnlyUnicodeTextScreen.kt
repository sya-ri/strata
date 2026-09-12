package dev.s7a.strata.integration.consumer

// showcase-source-begin:unicode-text
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont

/**
 * Creates a one-shot screen with caller-owned [state] created on the host thread.
 * The active resource pack must supply [font].
 */
internal fun unicodeTextScreen(
    state: TextFieldState,
    font: ResourceId = ResourceId("example", "body"),
): ScreenDefinition {
    val heading = UiText.Literal("日本語 한국어 🙂").withFont(font)
    return ScreenDefinition("Unicode text") {
        Column(spacing = 6) {
            Text(heading)
            Text("同じフォント / 같은 글꼴", font = font)
            TextField(state, font = font)
        }
    }
}
// showcase-source-end:unicode-text
