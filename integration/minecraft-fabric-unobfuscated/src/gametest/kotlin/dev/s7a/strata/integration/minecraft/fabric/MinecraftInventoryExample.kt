package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:inventory-screen
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.containerBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

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
    primaryPlayerBinding: SlotBinding = Slots.playerInventory(0),
    primaryContainerBinding: SlotBinding? = null,
): ScreenDefinition =
    ScreenDefinition("Synchronized inventory") {
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
                    modifier = Modifier.Empty.size(162, 156),
                    spacing = 3,
                ) {
                    Column(spacing = 2) {
                        Text(
                            "Chest",
                            style = TextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Grid(columns = 9) {
                            repeat(27) { index ->
                                if (index == 0 && primaryContainerBinding != null) {
                                    Slot(bind = primaryContainerBinding)
                                } else {
                                    Slot()
                                }
                            }
                        }
                    }
                    Column {
                        Text(
                            "Inventory",
                            style = TextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Grid(columns = 9, modifier = Modifier.Empty.padding(top = 1)) {
                            repeat(27) { index ->
                                Slot(bind = Slots.playerInventory(9 + index))
                            }
                        }
                        Grid(columns = 9, modifier = Modifier.Empty.padding(top = 4)) {
                            repeat(9) { index ->
                                Slot(
                                    bind =
                                        if (index == 0) {
                                            primaryPlayerBinding
                                        } else {
                                            Slots.playerInventory(index)
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
