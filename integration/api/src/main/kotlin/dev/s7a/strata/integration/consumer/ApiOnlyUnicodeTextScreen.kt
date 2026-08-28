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
 * Creates a Unicode text screen using a caller-supplied resource-pack font.
 *
 * The returned definition is unevaluated and must be opened or closed once.
 * Its host must use a font-resource profile containing the selected font.
 *
 * @param state caller-owned field state created on the host's owner thread.
 * @param font resource identifier of the font definition supplied by the active pack.
 * @return one-shot screen definition that retains the state without changing its value.
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
