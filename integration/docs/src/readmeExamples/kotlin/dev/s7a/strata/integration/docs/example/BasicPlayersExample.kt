package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the basic stage of the README player-list demonstration using only the public API.
 * Returns an unopened screen borrowing caller-owned players and panel pixels; open it on the host thread.
 */
internal fun basicPlayersScreen(
    players: List<ReadmePlayer>,
    panel: ImageSource,
): ScreenDefinition =
    ScreenDefinition("Players") {
        playerPanel(players, panel)
    }

// readme-demo:start
private fun UiScope.playerPanel(
    players: List<ReadmePlayer>,
    panel: ImageSource,
) {
    Column(
        modifier =
            Modifier.Empty
                .menuBackground()
                .padding(4),
        spacing = 4,
        horizontalAlignment = HorizontalAlignment.Center,
    ) {
        Text("Players (${players.size})")
        Stack(
            modifier =
                Modifier.Empty
                    .height(142)
                    .imageBackground(
                        panel,
                        Insets.all(8),
                        NineSliceCenterMode.Tiled,
                    ).padding(8),
        ) {
            playerList(players)
        }
    }
}

private fun UiScope.playerList(
    players: List<ReadmePlayer>,
) {
    Column(spacing = 6) {
        players.forEach { player ->
            playerRow(player)
        }
    }
}

private fun UiScope.playerRow(player: ReadmePlayer) {
    Row(
        modifier =
            Modifier.Empty
                .background(ArgbColor(0xFF4A4A4A.toInt()))
                .padding(6),
        spacing = 8,
        verticalAlignment = VerticalAlignment.Center,
    ) {
        PlayerHead(player.skin, PlayerHeadScale(3))
        Column(spacing = 4) {
            Text(player.name)
        }
    }
}
// readme-demo:end
