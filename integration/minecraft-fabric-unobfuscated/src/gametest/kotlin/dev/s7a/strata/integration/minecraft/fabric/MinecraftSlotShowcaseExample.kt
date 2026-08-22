package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:slot
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.Stack
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a deterministic unbound Slot showcase without depending on a player or container menu.
 *
 * The capture pointer targets the centered Slot so its profile highlight layers make the otherwise-empty hit region visible.
 *
 * @return one-shot definition containing one empty, highlightable Slot centered in the complete frame.
 */
internal fun createSlotShowcaseScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Slot showcase") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(64, 64)
                    .background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            Slot()
        }
    }
// showcase-source-end:slot
