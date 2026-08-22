package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:row
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Row
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a self-contained Row showcase whose complete frame demonstrates horizontal placement.
 *
 * @return one-shot definition containing two Minecraft-profile buttons centered by the Row itself.
 */
internal fun createRowShowcaseScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Row showcase") {
        Row(
            modifier =
                Modifier.Empty
                    .size(136, 64)
                    .background(ArgbColor(0xFF000000.toInt())),
            spacing = 4,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = VerticalAlignment.Center,
        ) {
            Button("Yes", width = 60)
            Button("No", width = 60)
        }
    }
// showcase-source-end:row
