package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:inventory-screen
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
import dev.s7a.strata.runtime.minecraft.MinecraftSlotBinding
import dev.s7a.strata.runtime.minecraft.MinecraftSlots
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.Slot
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.containerBackground
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds a generic chest-shaped screen whose lower 36 Slots are bound to the active player's inventory.
 *
 * The upper grid remains empty unless [primaryContainerBinding] is supplied by a server-owned container test.
 * The returned definition requires the Fabric version adapter and is not renderable by the portable-only headless host.
 *
 * @param primaryPlayerBinding binding used by the first hotbar cell.
 * @param primaryContainerBinding optional binding used by the first upper Container cell.
 * @return one-shot screen definition used to verify live item rendering and authoritative container input in a loaded client.
 */
internal fun createInventorySlotScreenDefinition(
    primaryPlayerBinding: MinecraftSlotBinding = MinecraftSlots.playerInventory(0),
    primaryContainerBinding: MinecraftSlotBinding? = null,
): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Synchronized inventory") {
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
                            repeat(3) { row ->
                                Row {
                                    repeat(9) { column ->
                                        if (row == 0 && column == 0 && primaryContainerBinding != null) {
                                            Slot(bind = primaryContainerBinding)
                                        } else {
                                            Slot()
                                        }
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
                            repeat(3) { row ->
                                Row {
                                    repeat(9) { column ->
                                        Slot(bind = MinecraftSlots.playerInventory(9 + row * 9 + column))
                                    }
                                }
                            }
                        }
                        Row(modifier = Modifier.Empty.padding(top = 4)) {
                            repeat(9) { column ->
                                Slot(
                                    bind =
                                        if (column == 0) {
                                            primaryPlayerBinding
                                        } else {
                                            MinecraftSlots.playerInventory(column)
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
// showcase-source-end:inventory-screen
