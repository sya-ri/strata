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
import dev.s7a.strata.modifier.width
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the left stage of the README player-list demonstration using only the public API.
 * The one-shot definition reads caller-owned immutable players on its host thread and propagates layout failures.
 * All child positions come from parent layout; the final stages fix row width and align only the weighted text column.
 *
 * @param players ordered offline presentation data, shared read-only with the definition.
 * @param panel detached original Minecraft Social Interactions panel.
 * @return an unopened definition; the caller owns opening or closing it.
 */
internal fun leftPlayersScreen(
    players: List<ReadmePlayer>,
    panel: ImageSource,
): ScreenDefinition =
    ReadmeDemoChrome.screen(panel) { panelModifier, rowModifier ->
        // readme-demo:start
        Column(modifier = panelModifier, spacing = 6) {
            players.forEach { player ->
                Row(
                    modifier = rowModifier.width(208),
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
