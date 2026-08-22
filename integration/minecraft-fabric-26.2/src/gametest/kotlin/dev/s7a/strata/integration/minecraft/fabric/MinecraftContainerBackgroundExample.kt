package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:container-background
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.containerBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the draw-command-equivalent unhovered background of an empty three-row Minecraft 26.2 chest screen.
 *
 * Empty unhovered native Slots emit no command, so this component-focused path is pixel-identical to the actual ContainerScreen while retaining only the background and labels that contribute pixels.
 *
 * @return one-shot screen definition reproducing the menu texture, generic container, and native shadow-free labels.
 */
internal fun createContainerBackgroundScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Chest") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(320, 240)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Stack(
                modifier = Modifier.Empty.containerBackground(rows = 3),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier =
                        Modifier.Empty
                            .padding(top = 6)
                            .size(162, 77)
                            .align(Alignment.TopCenter),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Chest",
                        style = TextStyle.ContainerLabel,
                        modifier = Modifier.Empty.padding(left = 1),
                    )
                    Text(
                        "Inventory",
                        style = TextStyle.ContainerLabel,
                        modifier = Modifier.Empty.padding(left = 1),
                    )
                }
            }
        }
    }
// showcase-source-end:container-background
