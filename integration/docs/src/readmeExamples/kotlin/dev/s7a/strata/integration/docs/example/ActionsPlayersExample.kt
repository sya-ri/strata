package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.VerticalAlignment.Center
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the actions stage of the README player-list demonstration using only the public API.
 * Returns an unopened screen borrowing caller-owned players and panel pixels; open it on the host thread.
 */
internal fun actionsPlayersScreen(
    players: List<ReadmePlayer>,
    panel: ImageSource,
): ScreenDefinition =
    ReadmeDemoChrome.screen(panel) { rowModifier ->
        // readme-demo:start
        Column(spacing = 6) {
            players.forEach { player ->
                Row(
                    modifier = rowModifier,
                    spacing = 8,
                    verticalAlignment = Center,
                ) {
                    PlayerHead(player.skin, PlayerHeadScale(3))
                    Column(spacing = 4) {
                        Text(player.name)
                        Text(player.role)
                    }
                    Button("Invite", width = 60)
                }
            }
        }
        // readme-demo:end
    }
