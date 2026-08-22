package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:grid
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Grid
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a self-contained Grid showcase with a complete three-by-three set of Minecraft-profile buttons.
 *
 * @return one-shot definition whose 64 by 64 root is also the complete captured frame.
 */
internal fun createGridShowcaseScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Grid showcase") {
        Grid(
            columns = 3,
            modifier =
                Modifier.Empty
                    .size(64, 64)
                    .background(ArgbColor(0xFF000000.toInt())),
            horizontalSpacing = 2,
            verticalSpacing = 2,
        ) {
            repeat(9) { index ->
                Button((index + 1).toString(), width = 20)
            }
        }
    }
// showcase-source-end:grid
