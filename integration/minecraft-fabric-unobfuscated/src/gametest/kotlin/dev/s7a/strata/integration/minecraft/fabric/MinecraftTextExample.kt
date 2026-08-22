package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:text
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a self-contained literal Text showcase.
 *
 * @return one-shot definition whose complete frame centers one Minecraft-profile text component.
 */
internal fun createTextShowcaseScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Text showcase") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(120, 64)
                    .background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            Text("Hello, Strata!")
        }
    }
// showcase-source-end:text
