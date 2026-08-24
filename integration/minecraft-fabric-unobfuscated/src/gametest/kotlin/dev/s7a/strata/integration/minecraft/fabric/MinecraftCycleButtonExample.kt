package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:cycle-button
import dev.s7a.strata.component.CycleButton
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.Stack
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/** Difficulty options rendered by the typed CycleButton showcase. */
private enum class Difficulty(
    val label: String,
) {
    Peaceful("Peaceful"),
    Easy("Easy"),
    Normal("Normal"),
    Hard("Hard"),
}

/** Builds the complete minimal CycleButton showcase frame. */
internal fun createCycleButtonShowcaseScreenDefinition(): ScreenDefinition {
    val state = CycleButtonState(Difficulty.Normal) { value -> "Difficulty: ${value.label}" }
    return ScreenDefinition("CycleButton showcase") {
        Stack(
            modifier = Modifier.Empty.size(166, 36).background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            CycleButton(state = state)
        }
    }
}
// showcase-source-end:cycle-button
