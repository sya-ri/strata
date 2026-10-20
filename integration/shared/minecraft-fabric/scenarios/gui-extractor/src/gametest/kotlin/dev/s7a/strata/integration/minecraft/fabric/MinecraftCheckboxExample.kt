package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:checkbox
import dev.s7a.strata.component.Checkbox
import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.Stack
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.ui.UiDefinition

/**
 * Builds the complete minimal Checkbox showcase frame.
 */
internal fun createCheckboxShowcaseScreenDefinition(): UiDefinition {
    val state = CheckboxState(initialChecked = true)
    return UiDefinition("Checkbox showcase") {
        Stack(
            modifier = Modifier.size(166, 36).background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            Checkbox("Allow invites", state)
        }
    }
}
// showcase-source-end:checkbox
