package dev.s7a.strata.integration.consumer

// showcase-source-begin:multiline-text
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Scrollbar
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.width
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText

/**
 * Creates message and notes editors while compiling against the API artifact alone.
 *
 * Both editor states belong to the caller and must be distinct and created on the host's owner thread.
 * Scrollbars are independent siblings sharing each editor's vertical position.
 * The returned definition remains unevaluated until a compatible runtime transfers or closes it.
 *
 * @param message caller-owned multiline message being composed.
 * @param notes independent caller-owned notes value.
 * @param font resource-pack font available in the eventual runtime profile.
 * @return a one-shot definition that retains neither native fonts nor an attached editor.
 */
internal fun multilineTextScreen(
    message: TextAreaState,
    notes: TextAreaState,
    font: ResourceId,
): ScreenDefinition =
    ScreenDefinition("Multiline text") {
        Column(spacing = 6) {
            Text(UiText.Literal("日本語\n한국어 🙂"), TextLayout.Multiline(), modifier = Modifier.Empty.width(240))
            Text("Message composition", TextLayout.SingleLine, font)
            Row(spacing = 4) {
                TextArea(message, TextAreaViewport.Lines(width = 240, lines = 4), font)
                Scrollbar(message.scrollState, modifier = Modifier.Empty.height(44))
            }
            Text(UiText.Literal("Notes\nメモ"), TextLayout.Multiline(maxLines = 2, overflow = TextOverflow.Ellipsis), font)
            Text("Independent scrolling", TextLayout.SingleLine)
            Row(spacing = 4) {
                TextArea(notes, TextAreaViewport.Size(IntSize(240, 96)), wrap = TextWrap.None, lineSpacing = 1)
                Scrollbar(notes.scrollState, modifier = Modifier.Empty.height(96))
            }
        }
    }
// showcase-source-end:multiline-text
