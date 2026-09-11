package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Shows 6 players before a scroll viewport is introduced, deliberately exposing the list overflow.
 * The unopened definition reads caller-owned immutable inputs on its host thread and propagates layout failures.
 */
internal fun sixPlayersScreen(
    players: List<ReadmePlayer>,
    panel: ImageSource,
): ScreenDefinition =
    ReadmeDemoChrome.screen(panel, playerCount = 6) { panelModifier, rowModifier ->
        // readme-demo:start
        Column(modifier = panelModifier, spacing = 6) {
            players.take(6).forEach { player ->
                playerRow(player, rowModifier)
            }
        }
        // readme-demo:end
    }
