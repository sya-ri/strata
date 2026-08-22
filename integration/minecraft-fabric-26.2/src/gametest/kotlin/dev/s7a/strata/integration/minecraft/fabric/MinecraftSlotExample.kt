package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:slot
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.Slot
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.containerBackground
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds the empty three-row Minecraft 26.2 chest screen used by native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition reproducing the generic container, labels, 63 Slot hit regions, and hovered highlight order.
 */
internal fun createSlotScreenDefinition(): MinecraftScreenDefinition =
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
                Column(
                    modifier = Modifier.Empty.size(162, 156),
                    spacing = 3,
                ) {
                    Column(spacing = 2) {
                        Text(
                            "Chest",
                            style = MinecraftTextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Column {
                            repeat(3) {
                                Row {
                                    repeat(9) {
                                        Slot()
                                    }
                                }
                            }
                        }
                    }
                    Column {
                        Text(
                            "Inventory",
                            style = MinecraftTextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Column(modifier = Modifier.Empty.padding(top = 1)) {
                            repeat(3) {
                                Row {
                                    repeat(9) {
                                        Slot()
                                    }
                                }
                            }
                        }
                        Row(modifier = Modifier.Empty.padding(top = 4)) {
                            repeat(9) {
                                Slot()
                            }
                        }
                    }
                }
            }
        }
    }
// showcase-source-end:slot
