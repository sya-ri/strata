package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the roles stage of the README player-list demonstration using only the public API.
 * Returns an unopened screen borrowing caller-owned players and panel pixels; open it on the host thread.
 */
internal fun rolesPlayersScreen(
    players: List<ReadmePlayer>,
    panel: ImageSource,
): ScreenDefinition =
    ReadmeDemoChrome.screen(panel, playerCount = players.size) {
        // readme-demo:start
        val rowColor = ArgbColor(0xFF4A4A4A.toInt())
        Column(spacing = 6) {
            players.forEach { player ->
                Row(
                    modifier =
                        Modifier.Empty
                            .background(rowColor)
                            .padding(6),
                    spacing = 8,
                    verticalAlignment = VerticalAlignment.Center,
                ) {
                    PlayerHead(player.skin, PlayerHeadScale(3))
                    Column(spacing = 4) {
                        Text(player.name)
                        Text(player.role)
                    }
                }
            }
        }
        // readme-demo:end
    }
