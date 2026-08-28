package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:flow-row
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a self-contained FlowRow showcase with four differently sized Minecraft-profile buttons.
 *
 * @return one-shot definition whose 168 by 60 root captures two independently centered rows without synthetic row parents.
 */
internal fun createFlowRowShowcaseScreenDefinition(): ScreenDefinition =
    ScreenDefinition("FlowRow showcase") {
        FlowRow(
            modifier =
                Modifier.Empty
                    .size(168, 60)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .padding(8),
            horizontalSpacing = 4,
            verticalSpacing = 4,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = VerticalAlignment.Center,
        ) {
            Button("Continue", width = 72)
            Button("Back", width = 56)
            Button("Options", width = 92)
            Button("Done", width = 52)
        }
    }
// showcase-source-end:flow-row
