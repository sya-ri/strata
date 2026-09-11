package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Scrollbar
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.modifier.width
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Adds a bounded scroll viewport and linked scrollbar around the unchanged player-row composition.
 * Each unopened definition creates its own owner-thread scroll state during evaluation.
 * The caller retains immutable players and panel pixels; layout and resource failures propagate.
 */
internal fun scrollPlayersScreen(
    players: List<ReadmePlayer>,
    panel: ImageSource,
): ScreenDefinition =
    ReadmeDemoChrome.screen(panel, playerCount = players.size, panelWidth = 246) { rowModifier ->
        // readme-demo:start
        val scroll = ScrollState()
        Row(spacing = 4) {
            ScrollArea(
                state = scroll,
                modifier = Modifier.Empty.size(220, 126),
            ) {
                Column(
                    modifier = Modifier.Empty.width(220),
                    spacing = 6,
                ) {
                    players.forEach { player ->
                        playerRow(player, rowModifier)
                    }
                }
            }
            Scrollbar(
                state = scroll,
                modifier = Modifier.Empty.size(6, 126),
            )
        }
        // readme-demo:end
    }
