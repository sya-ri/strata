package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:player-head
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.ui.UiDefinition

/**
 * Builds a self-contained PlayerHead showcase from a caller-selected skin source.
 *
 * @param skin player identity lookup or immutable 64 by 64 skin source rendered by PlayerHead itself.
 * @return one-shot definition containing the complete 24 by 24 face and hat layers inside a minimal canvas.
 */
internal fun createPlayerHeadShowcaseScreenDefinition(
    skin: PlayerSkinSource,
): UiDefinition =
    UiDefinition("Player head") {
        Stack(
            modifier = Modifier.size(64, 64).background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            PlayerHead(source = skin, scale = PlayerHeadScale(3))
        }
    }
// showcase-source-end:player-head
