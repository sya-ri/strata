package dev.s7a.strata.integration.docs.skill

// showcase-source-begin:skill-open
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Opens a confirmation screen on the installed runtime's owner thread.
 */
internal fun openConfirmationScreen(onConfirm: () -> Unit) {
    ScreenDefinition("Confirm action") {
        Column(
            modifier =
                Modifier.Empty
                    .size(320, 180)
                    .menuBackground()
                    .padding(12),
            spacing = 8,
            horizontalAlignment = HorizontalAlignment.Center,
        ) {
            Text("Continue with this action?")
            Button(
                "Yes",
                modifier = Modifier.Empty.onActivate(onConfirm),
            )
        }
    }.open()
}
// showcase-source-end:skill-open
