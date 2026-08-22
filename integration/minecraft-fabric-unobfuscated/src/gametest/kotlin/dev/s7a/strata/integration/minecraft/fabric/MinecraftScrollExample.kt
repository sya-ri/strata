package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:scroll
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Scroll
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the deterministic Minecraft selection-list screen used by the native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition reproducing the native list viewport, row geometry, separators, scrollbar, and text.
 */
internal fun createScrollScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Strata Scroll parity") {
        Stack(modifier = Modifier.Empty.size(320, 180).menuBackground()) {
            // Native ObjectSelectionList geometry reserves distinct 33-pixel header and 53-pixel footer bands.
            Scroll(
                modifier = Modifier.Empty.padding(top = 33, bottom = 53).fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier.Empty.size(270, 216),
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    listOf(
                        "Entry 01",
                        "Entry 02",
                        "Entry 03",
                        "Entry 04",
                        "Entry 05",
                        "Entry 06",
                        "Entry 07",
                        "Entry 08",
                        "Entry 09",
                        "Entry 10",
                        "Entry 11",
                        "Entry 12",
                    ).forEach { label ->
                        Text(label, modifier = Modifier.Empty.padding(top = 5, bottom = 4))
                    }
                }
            }
        }
    }
// showcase-source-end:scroll
