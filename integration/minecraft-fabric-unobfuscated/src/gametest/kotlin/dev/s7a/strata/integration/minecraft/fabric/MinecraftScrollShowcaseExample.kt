package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:scroll
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Scrollbar
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a self-contained Scroll showcase whose content is taller than its complete captured viewport.
 *
 * @return one-shot definition demonstrating clipping, separators, and a visible Minecraft-profile scrollbar.
 */
internal fun createScrollShowcaseScreenDefinition(): ScreenDefinition {
    val state = ScrollState()
    return ScreenDefinition("Scroll showcase") {
        Row(
            spacing = 8,
            modifier =
                Modifier.Empty
                    .size(160, 64)
                    .background(ArgbColor(0xFF000000.toInt())),
        ) {
            ScrollArea(state = state, modifier = Modifier.Empty.size(146, 64)) {
                Column(
                    modifier = Modifier.Empty.size(132, 108),
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    repeat(6) { index ->
                        Text("Entry " + (index + 1))
                    }
                }
            }
            Scrollbar(state = state, modifier = Modifier.Empty.size(6, 64))
        }
    }
}
// showcase-source-end:scroll
