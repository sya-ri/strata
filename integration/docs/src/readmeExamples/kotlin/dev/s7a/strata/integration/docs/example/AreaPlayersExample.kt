@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxWidth
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.width
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.ui.UiDefinition

/**
 * Adds wheel scrolling and child clipping before introducing an independent scrollbar.
 * Each unopened definition owns fresh host-thread state and reads caller-owned immutable presentation inputs.
 * Resource and layout failures propagate through screen evaluation.
 */
internal fun areaPlayersScreen(
    players: List<ReadmePlayer>,
    panel: ImageSource,
): UiDefinition =
    UiDefinition("Players") {
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
    val scroll = ScrollState()
    ScrollArea(
        state = scroll,
        modifier = Modifier.Empty.height(126),
    ) {
        Column(Modifier.Empty.width(220), spacing = 6) {
            players.forEach { player ->
                playerRow(player)
            }
        }
    }
}

private fun UiScope.playerRow(player: ReadmePlayer) {
    Row(
        modifier =
            Modifier.Empty
                .background(ArgbColor(0xFF4A4A4A.toInt()))
                .padding(6)
                .fillMaxWidth(),
        spacing = 8,
        verticalAlignment = VerticalAlignment.Center,
    ) {
        PlayerHead(player.skin, PlayerHeadScale(3))
        Column(
            modifier = Modifier.Empty.weight(1f),
            spacing = 4,
        ) {
            Text(player.name)
            Text(player.role)
        }
        Button("Invite", width = 60)
    }
}
// readme-demo:end
