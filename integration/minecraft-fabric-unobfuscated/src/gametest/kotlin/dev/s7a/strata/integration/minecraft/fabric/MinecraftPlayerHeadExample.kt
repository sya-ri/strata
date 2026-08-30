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
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a self-contained PlayerHead showcase from a caller-selected skin source.
 *
 * @param skin player identity lookup or immutable 64 by 64 skin source rendered by PlayerHead itself.
 * @return one-shot definition containing the complete 24 by 24 face and hat layers inside a minimal canvas.
 */
internal fun createPlayerHeadShowcaseScreenDefinition(
    skin: PlayerSkinSource,
): ScreenDefinition =
    ScreenDefinition("Player head") {
        Stack(
            modifier = Modifier.Empty.size(64, 64).background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            PlayerHead(source = skin, scale = PlayerHeadScale(3))
        }
    }
// showcase-source-end:player-head

/**
 * Builds the player-head parity screen used by existing loaded-client checks.
 *
 * @param skin player identity lookup or immutable 64 by 64 skin source.
 * @return one-shot definition with the same geometry as [createPlayerHeadShowcaseScreenDefinition].
 */
internal fun createPlayerHeadScreenDefinition(
    skin: PlayerSkinSource = PlayerSkinSource.Name("Player0"),
): ScreenDefinition = createPlayerHeadShowcaseScreenDefinition(skin)

/**
 * Builds the loaded Fabric parity case for the deprecated arbitrary-size compatibility path.
 *
 * @param skin immutable normalized skin used by both the Fabric and headless runtimes.
 * @return one-shot definition containing a centered 10 by 10 bilinearly filtered head.
 */
@Suppress("DEPRECATION")
internal fun createFilteredPlayerHeadScreenDefinition(
    skin: PlayerSkinSource,
): ScreenDefinition =
    ScreenDefinition("Filtered player head") {
        Stack(
            modifier = Modifier.Empty.size(64, 64).background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            PlayerHead(source = skin, size = 10)
        }
    }
