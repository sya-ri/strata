package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:text-area
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Scrollbar
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds one complete multiline editor frame with an independently placed scrollbar.
 *
 * The caller creates this definition on the editor's owner thread; the captured state owns committed text and vertical scrolling.
 * The resource-font profile supplies the illustrated Unicode glyphs, while soft wrapping leaves the stored value unchanged.
 * @return one-shot definition containing a 200 by 64 editor and its separately composed scrollbar.
 */
internal fun createTextAreaShowcaseScreenDefinition(): ScreenDefinition {
    val size = IntSize(200, 64)
    val state =
        TextAreaState(
            initialValue =
                "Write multiple lines.\n" +
                    "日本語と 한글\n" +
                    "Emoji: 🙂\n" +
                    "Long sentences wrap within this viewport.\n" +
                    "More text keeps scrolling.\n" +
                    "The scrollbar is optional.\n" +
                    "The original value keeps its newlines.",
            maxLength = 2048,
        )
    return ScreenDefinition("TextArea showcase") {
        Row(
            modifier = Modifier.Empty.size(226, 80).background(ArgbColor(0xFF000000.toInt())),
            spacing = 4,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = VerticalAlignment.Center,
        ) {
            TextArea(state, viewport = TextAreaViewport.Size(size))
            Scrollbar(state.scrollState, modifier = Modifier.Empty.size(6, size.height))
        }
    }
}
// showcase-source-end:text-area
