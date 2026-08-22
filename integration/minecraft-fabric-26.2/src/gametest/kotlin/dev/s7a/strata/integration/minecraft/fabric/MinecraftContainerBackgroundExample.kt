package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:container-background
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.containerBackground
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds the draw-command-equivalent unhovered background of an empty three-row Minecraft 26.2 chest screen.
 *
 * Empty unhovered native Slots emit no command, so this component-focused path is pixel-identical to the actual ContainerScreen while retaining only the background and labels that contribute pixels.
 *
 * @return one-shot screen definition reproducing the menu texture, generic container, and native shadow-free labels.
 */
internal fun createContainerBackgroundScreenDefinition(): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Chest") {
        Box(
            modifier =
                Modifier.Empty
                    .size(320, 240)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.Empty.containerBackground(rows = 3),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.Empty.size(162, 156)) {
                    Column(
                        modifier = Modifier.Empty.size(162, 77),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Chest",
                            style = MinecraftTextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Text(
                            "Inventory",
                            style = MinecraftTextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                    }
                }
            }
        }
    }
// showcase-source-end:container-background
