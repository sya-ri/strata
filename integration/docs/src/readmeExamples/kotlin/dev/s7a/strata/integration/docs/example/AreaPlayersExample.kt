package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.width
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Adds wheel scrolling and child clipping before introducing an independent scrollbar.
 * Each unopened definition owns fresh host-thread state and reads caller-owned immutable presentation inputs.
 * Resource and layout failures propagate through screen evaluation.
 */
internal fun areaPlayersScreen(
    players: List<ReadmePlayer>,
    panel: ImageSource,
): ScreenDefinition =
    ReadmeDemoChrome.screen(panel, playerCount = players.size) { rowModifier ->
        // readme-demo:start
        val scroll = ScrollState()
        ScrollArea(
            state = scroll,
            modifier = Modifier.Empty.height(126),
        ) {
            Column(Modifier.Empty.width(220), spacing = 6) {
                players.forEach { player ->
                    playerRow(player, rowModifier)
                }
            }
        }
        // readme-demo:end
    }
