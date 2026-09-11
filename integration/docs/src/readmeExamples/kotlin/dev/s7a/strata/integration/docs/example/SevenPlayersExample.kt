package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.modifier.Modifier
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
                playerRow(player, rowModifier)
            }
        }
        // readme-demo:end
    }
