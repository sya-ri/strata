package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies native Canvas/Slot ordering in the caller-owned integrated world.
 *
 * The runner thread transports the original stack without inspecting or mutating it; seeding and restoration occur on the server thread.
 * Screenshots and pixels are checked by the Canvas fixture and independent item oracle.
 * Cleanup closes any surviving Strata screen and restores the original server stack even when the scene fails.
 *
 * @param context Fabric coordinator already connected to a temporary integrated world.
 * @param profile immutable UI profile extracted from the active native resources.
 * @param output contained build directory for the Canvas/Slot evidence.
 * @throws Throwable preserving the primary scene failure while attempting independent screen and inventory cleanup.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun runMinecraftCanvasInventoryOrderingTest(
    context: MinecraftLoadedTestContext,
    profile: MinecraftUiProfile,
    output: Path,
) {
    val test = InventorySlotTestContext(context)
    val previous = AtomicReference<ItemStack?>()
    val result =
        runCatching {
            test.onServer { player ->
                previous.set(player.inventory.getItem(test.playerInventoryIndex).copy())
                player.inventory.setItem(test.playerInventoryIndex, ItemStack(Items.DIRT, test.itemCount))
                player.inventoryMenu.broadcastChanges()
            }
            test.waitForPlayerItem(test.playerInventoryIndex, Items.DIRT)
            runMinecraftCanvasSlotTest(context, profile, output, test.playerInventoryIndex)
        }
    runCanvasTestCleanup(
        result.exceptionOrNull(),
        { test.closeScreen() },
        {
            previous.get()?.let { original ->
                test.onServer { player ->
                    player.inventory.setItem(test.playerInventoryIndex, original)
                    player.inventoryMenu.broadcastChanges()
                }
            }
        },
    )
    result.getOrThrow()
}
