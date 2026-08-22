package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:spacer
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a self-contained Spacer showcase that reserves visible space between two siblings.
 *
 * @return one-shot definition in which the Spacer, rather than parent spacing or child padding, owns the 16-pixel gap.
 */
internal fun createSpacerShowcaseScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Spacer showcase") {
        Row(
            modifier =
                Modifier.Empty
                    .size(160, 64)
                    .background(ArgbColor(0xFF000000.toInt())),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = VerticalAlignment.Center,
        ) {
            Button("Left", width = 60)
            Spacer(modifier = Modifier.Empty.size(16, 20))
            Button("Right", width = 60)
        }
    }
// showcase-source-end:spacer
