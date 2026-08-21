package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:slot
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.geometry.Insets
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
        ) {
            Box(
                modifier =
                    Modifier.Empty
                        .padding(left = 72, top = 36)
                        .containerBackground(rows = 3),
            ) {
                Text(
                    "Chest",
                    style = MinecraftTextStyle.ContainerLabel,
                    modifier = Modifier.Empty.padding(Insets(left = 8, top = 6)),
                )
                Text(
                    "Inventory",
                    style = MinecraftTextStyle.ContainerLabel,
                    modifier = Modifier.Empty.padding(Insets(left = 8, top = 74)),
                )
                repeat(3) { row ->
                    repeat(9) { column ->
                        Slot(
                            modifier =
                                Modifier.Empty.padding(
                                    Insets(
                                        left = 7 + column * 18,
                                        top = 17 + row * 18,
                                    ),
                                ),
                        )
                    }
                }
                repeat(3) { row ->
                    repeat(9) { column ->
                        Slot(
                            modifier =
                                Modifier.Empty.padding(
                                    Insets(
                                        left = 7 + column * 18,
                                        top = 84 + row * 18,
                                    ),
                                ),
                        )
                    }
                }
                repeat(9) { column ->
                    Slot(
                        modifier = Modifier.Empty.padding(Insets(left = 7 + column * 18, top = 142)),
                    )
                }
            }
        }
    }
// showcase-source-end:slot

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
        ) {
            Box(
                modifier =
                    Modifier.Empty
                        .padding(left = 72, top = 36)
                        .containerBackground(rows = 3),
            ) {
                Text(
                    "Chest",
                    style = MinecraftTextStyle.ContainerLabel,
                    modifier = Modifier.Empty.padding(left = 8, top = 6),
                )
                Text(
                    "Inventory",
                    style = MinecraftTextStyle.ContainerLabel,
                    modifier = Modifier.Empty.padding(left = 8, top = 74),
                )
                repeat(3) { row ->
                    repeat(9) { column ->
                        val cellModifier = Modifier.Empty.padding(left = 7 + column * 18, top = 17 + row * 18)
                        if (row == 0 && column == 0 && primaryContainerBinding != null) {
                            Slot(bind = primaryContainerBinding, modifier = cellModifier)
                        } else {
                            Slot(modifier = cellModifier)
                        }
                    }
                }
                repeat(3) { row ->
                    repeat(9) { column ->
                        Slot(
                            bind = MinecraftSlots.playerInventory(9 + row * 9 + column),
                            modifier = Modifier.Empty.padding(left = 7 + column * 18, top = 84 + row * 18),
                        )
                    }
                }
                repeat(9) { column ->
                    Slot(
                        bind = if (column == 0) primaryPlayerBinding else MinecraftSlots.playerInventory(column),
                        modifier = Modifier.Empty.padding(left = 7 + column * 18, top = 142),
                    )
                }
            }
        }
    }
