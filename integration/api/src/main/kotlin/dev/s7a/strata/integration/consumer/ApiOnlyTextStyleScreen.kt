package dev.s7a.strata.integration.consumer

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextShadow
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.ui.UiDefinition

/**
 * Compiles shadowless guidance and independently colored editor states against the public API alone.
 */
internal fun textStyleScreen(
    field: TextFieldState,
    area: TextAreaState,
): UiDefinition {
    val guidance = TextStyle.Inactive.copy(shadow = null)
    val editing =
        TextStyle(
            color = ArgbColor(0xff4080c0.toInt()),
            shadow = TextShadow.Color(ArgbColor(0xff102030.toInt())),
            disabledColor = ArgbColor(0xff808080.toInt()),
        )
    return UiDefinition("Text styles") {
        Column(spacing = 4) {
            Text("Search guidance", style = guidance)
            Text("Secondary help\non another line", TextLayout.Multiline(), style = guidance)
            TextField(field, textStyle = editing)
            TextArea(area, TextAreaViewport.Lines(160, 3), textStyle = editing.copy(shadow = null), enabled = false)
        }
    }
}
