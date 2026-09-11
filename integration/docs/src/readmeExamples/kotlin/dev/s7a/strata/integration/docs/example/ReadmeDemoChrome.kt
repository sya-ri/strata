package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Shared vanilla-style screen chrome around the changing player-list example.
 * It composes public primitives and the original Social Interactions panel instead of introducing a public component.
 */
internal object ReadmeDemoChrome {
    /**
     * Creates an unopened caller-owned definition with a roster count and original panel.
     * The callback receives immutable active panel and row modifiers on the host thread.
     * The caller owns the detached panel pixels; asset and layout failures propagate through screen evaluation.
     */
    fun screen(
        panel: ImageSource,
        playerCount: Int = 3,
        panelWidth: Int = 236,
        content: UiScope.(Modifier, Modifier) -> Unit,
    ): ScreenDefinition =
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
                Text("Players ($playerCount)")
                val panelModifier =
                    Modifier.Empty
                        .size(panelWidth, 142)
                        .imageBackground(panel, Insets.all(8), NineSliceCenterMode.Tiled)
                        .padding(8)
                val rowModifier = Modifier.Empty.background(ReadmeDemoColors.row).padding(6)
                content(panelModifier, rowModifier)
            }
        }
}
