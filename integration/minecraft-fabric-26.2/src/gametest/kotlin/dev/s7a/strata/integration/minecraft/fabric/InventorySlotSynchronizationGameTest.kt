package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.MinecraftSlots
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.apache.commons.lang3.function.FailableConsumer
import java.nio.file.Path
import java.util.function.Predicate

/**
 * Verifies player-inventory and raw active-menu Slot locators against the loaded Minecraft 26.2 client.
 *
 * The scenario renders a real diamond stack, performs the native primary pickup through Fabric input, verifies the authoritative carried stack, and restores the item before closing the screen.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object InventorySlotSynchronizationGameTest {
    /**
     * Executes the live Slot scenario on the client GameTest thread.
     *
     * @param context loaded client test context controlling native input and screenshots.
     * @param profile immutable profile extracted from the same active resource manager.
     * @param output contained build directory receiving the actual Fabric screen screenshot.
     * @throws AssertionError when rendering or inventory synchronization differs from the locked behavior.
     * @throws Throwable when Minecraft screen, input, rendering, or filesystem work fails.
     */
    internal fun run(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        closeFabricScreen(context)
        var activeMenuSlotIndex = -1
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val player = checkNotNull(minecraft.player)
                val inventory = player.inventory
                inventory.setItem(playerInventoryIndex, ItemStack(Items.DIAMOND, itemCount))
                activeMenuSlotIndex =
                    player.containerMenu.findSlot(inventory, playerInventoryIndex).orElseThrow {
                        IllegalStateException("The active inventory menu must expose the tested player Slot.")
                    }
                minecraft.resizeGui()
            },
        )
        context.input.setCursorPos(slotPointer.x.toDouble(), slotPointer.y.toDouble())
        context.setScreen {
            createMinecraftScreen(
                createInventorySlotScreenDefinition(MinecraftSlots.activeMenu(activeMenuSlotIndex)),
                profile,
                parent = null,
            )
        }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        context.waitTicks(2)
        context.takeScreenshot(
            TestScreenshotOptions
                .of("strata-inventory-slot-fabric")
                .disableCounterPrefix()
                .withSize(viewportWidth, viewportHeight)
                .withDestinationDir(output),
        )

        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                clickSlot(minecraft)
                val player = checkNotNull(minecraft.player)
                check(player.inventory.getItem(playerInventoryIndex).isEmpty) {
                    "Primary pickup must empty the synchronized inventory Slot."
                }
                val carried = player.inventoryMenu.carried
                check(carried.`is`(Items.DIAMOND) && carried.count == itemCount) {
                    "Primary pickup must move the synchronized stack to the authoritative carried stack."
                }
            },
        )

        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                clickSlot(minecraft)
                val player = checkNotNull(minecraft.player)
                val restored = player.inventory.getItem(playerInventoryIndex)
                check(restored.`is`(Items.DIAMOND) && restored.count == itemCount) {
                    "A second primary pickup must restore the synchronized stack."
                }
                check(player.inventoryMenu.carried.isEmpty) { "The carried stack must be empty after restoring the Slot." }
            },
        )
        closeFabricScreen(context)
    }

    private fun clickSlot(minecraft: Minecraft) {
        val screen = minecraft.gui.screen() as? FabricMinecraftScreen ?: error("The synchronized Slot screen is not active.")
        val event = MouseButtonEvent(slotPointer.x.toDouble(), slotPointer.y.toDouble(), MouseButtonInfo(primaryMouseButton, noModifiers))
        check(screen.mouseClicked(event, false)) { "The synchronized Slot must consume a primary press." }
        check(screen.mouseReleased(event)) { "The synchronized Slot must consume the matching release." }
    }

    private fun closeFabricScreen(context: ClientGameTestContext) {
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val current = minecraft.gui.screen()
                if (current is FabricMinecraftScreen) current.onClose()
            },
        )
        context.waitFor(Predicate<Minecraft> { minecraft -> (minecraft.gui.screen() is FabricMinecraftScreen).not() })
    }

    private val slotPointer = IntOffset(82, 181)
    private val playerInventoryIndex = 0
    private val itemCount = 7
    private val primaryMouseButton = 0
    private val noModifiers = 0
    private val viewportWidth = 320
    private val viewportHeight = 240
}
