package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:button
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Stack
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a self-contained Button showcase with shared pointer and keyboard activation attached through its modifier.
 *
 * @return one-shot definition containing the complete normal Button frame and no surrounding application screen.
 */
internal fun createButtonShowcaseScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Button showcase") {
        val enabled = true
        Stack(
            modifier =
                Modifier.Empty
                    .size(166, 64)
                    .background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                "Continue",
                enabled = enabled,
                modifier =
                    Modifier.Empty
                        .onActivate(enabled) {}
                        .onHover {},
            )
        }
    }
// showcase-source-end:button
