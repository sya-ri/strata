package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.HorizontalAlignment.Start
import dev.s7a.strata.layout.VerticalAlignment.Center
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxWidth
import dev.s7a.strata.modifier.width
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Shows 7 players before a scroll viewport is introduced, deliberately exposing the list overflow.
 * The unopened definition reads caller-owned immutable inputs on its host thread and propagates layout failures.
 */
internal fun sevenPlayersScreen(
    players: List<ReadmePlayer>,
    panel: ImageSource,
): ScreenDefinition =
    ReadmeDemoChrome.screen(panel, playerCount = 7) { rowModifier ->
        // readme-demo:start
        Column(Modifier.Empty.width(220), spacing = 6) {
            players.take(7).forEach { player ->
                Row(
                    modifier = rowModifier.fillMaxWidth(),
                    spacing = 8,
                    verticalAlignment = Center,
                ) {
                    PlayerHead(player.skin, PlayerHeadScale(3))
                    Column(
                        modifier = Modifier.Empty.weight(1f),
                        spacing = 4,
                        horizontalAlignment = Start,
                    ) {
                        Text(player.name)
                        Text(player.role)
                    }
                    Button("Invite", width = 60)
                }
            }
        }
        // readme-demo:end
    }
