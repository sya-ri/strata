package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxWidth
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.modifier.width
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the fixed stage of the README player-list demonstration using only the public API.
 * Returns an unopened screen borrowing caller-owned players and panel pixels; open it on the host thread.
 */
internal fun fixedPlayersScreen(
    players: List<ReadmePlayer>,
    panel: ImageSource,
): ScreenDefinition =
    // readme-demo:start
    ScreenDefinition("Players") {
        Column(
            modifier =
                Modifier.Empty
                    .size(256, 192)
                    .menuBackground()
                    .padding(4),
            spacing = 4,
            horizontalAlignment = HorizontalAlignment.Center,
        ) {
            Text("Players (${players.size})")
            Stack(
                modifier =
                    Modifier.Empty
                        .size(236, 142)
                        .imageBackground(
                            panel,
                            Insets.all(8),
                            NineSliceCenterMode.Tiled,
                        ).padding(8),
            ) {
                val rowColor = ArgbColor(0xFF4A4A4A.toInt())
                Column(Modifier.Empty.width(220), spacing = 6) {
                    players.forEach { player ->
                        Row(
                            modifier =
                                Modifier.Empty
                                    .background(rowColor)
                                    .padding(6)
                                    .fillMaxWidth(),
                            spacing = 8,
                            verticalAlignment = VerticalAlignment.Center,
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
            }
        }
    }
// readme-demo:end
