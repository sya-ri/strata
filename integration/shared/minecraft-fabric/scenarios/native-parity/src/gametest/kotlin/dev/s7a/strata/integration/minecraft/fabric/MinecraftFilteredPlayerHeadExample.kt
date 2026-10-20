package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.ui.UiDefinition

/**
 * Builds the loaded Fabric parity case for the deprecated arbitrary-size compatibility path.
 *
 * @param skin immutable normalized skin used by both the Fabric and headless runtimes.
 * @return one-shot definition containing a centered 10 by 10 bilinearly filtered head.
 */
@Suppress("DEPRECATION")
internal fun createFilteredPlayerHeadScreenDefinition(
    skin: PlayerSkinSource,
): UiDefinition =
    UiDefinition("Filtered player head") {
        Stack(
            modifier = Modifier.size(64, 64).background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            PlayerHead(source = skin, size = 10)
        }
    }
