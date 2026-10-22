package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Slots
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.network.chat.Component
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies player-inventory, logical Container, and raw active-menu Slot locators against the loaded client and integrated server.
 *
 * Every scenario seeds storage on the server thread, opens any required server-owned menu, performs native pointer pickup through Fabric input, observes the authoritative server mutation, restores the stack, and cleans up the menu.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object InventorySlotSynchronizationGameTest {
    /**
     * Runs only the existing native Canvas/Slot ordering case in the caller-owned integrated world.
     *
     * The runner thread transports the original stack without inspecting or mutating it; seeding and restoration occur on the server thread.
     * Native screens, screenshots, and pixels retain the existing Canvas fixture and independent item oracle.
     * Cleanup closes any surviving Strata screen and restores the original server stack even when the scene fails.
     *
     * @param context Fabric coordinator already connected to a temporary integrated world.
     * @param profile immutable UI profile extracted from the active native resources.
     * @param output contained build directory for the existing Canvas/Slot evidence.
     * @throws Throwable preserving the primary scene failure while attempting independent screen and inventory cleanup.
     */
    internal fun runCanvasOrdering(
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

    /**
     * Executes live player, custom Container, and ender-chest synchronization scenarios.
     *
     * @param context loaded client test context controlling client input, screenshots, and the integrated server lifetime.
     * @param profile immutable profile extracted from the same active resource manager.
     * @param output contained build directory receiving actual Fabric screen screenshots.
     * @throws AssertionError when rendering or client/server inventory synchronization differs from the locked behavior.
     * @throws Throwable when Minecraft screen, network, menu, input, rendering, or filesystem work fails.
     */
    internal fun run(
        context: MinecraftLoadedTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val test = InventorySlotTestContext(context)
        test.closeScreen()
        runPlayerInventoryScenario(test, profile, output)
        runContainerScenario(test, profile, output, ContainerScenario.Custom)
        runContainerScenario(test, profile, output, ContainerScenario.EnderChest)
        runIndustrialContainerScenario(test, profile, output)
        InventorySlotGestureParity.runMirroredSlotDragScenario(test, profile)
    }

    private fun runIndustrialContainerScenario(
        test: InventorySlotTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val containerReference = AtomicReference<SimpleContainer>()
        test.onServer { player ->
            val container = SimpleContainer(test.containerSize)
            containerReference.set(container)
            container.setItem(test.fuelSlotIndex, ItemStack(Items.COAL, test.itemCount))
            container.setItem(test.chargeSlotIndex, ItemStack(Items.REDSTONE, test.itemCount))
            val provider =
                SimpleMenuProvider(
                    { containerId, inventory, _ -> ChestMenu.threeRows(containerId, inventory, container) },
                    Component.literal("Coal Generator"),
                )
            check(player.openMenu(provider).isPresent) { "The server-owned coal generator menu must open." }
        }
        test.client.waitFor { minecraft ->
            val menu = minecraft.player?.containerMenu ?: return@waitFor false
            menu is ChestMenu &&
                test.stackMatches(menu.getSlot(test.fuelSlotIndex).item, Items.COAL) &&
                test.stackMatches(menu.getSlot(test.chargeSlotIndex).item, Items.REDSTONE)
        }
        resizeMinecraftTestWindow(test.client, industrialViewport)
        test.client.runOnClient { minecraft -> minecraft.resizeGui() }
        test.client.setScreen { createMinecraftScreen(createIndustrialScreenDefinition(), profile, parent = null) }
        test.client.waitForScreen(FabricMinecraftScreen::class.java)
        test.client.waitTicks(2)
        test.client.takeScreenshot(
            "strata-industrial-synchronized-fabric",
            output,
            IntSize(industrialViewport.width, industrialViewport.height),
        )

        test.clickSlot(test.industrialFuelPointer)
        test.waitForServer { player ->
            containerReference.get().getItem(test.fuelSlotIndex).isEmpty && test.stackMatches(player.containerMenu.carried, Items.COAL)
        }
        test.clickSlot(test.industrialFuelPointer)
        test.waitForServer { player ->
            test.stackMatches(containerReference.get().getItem(test.fuelSlotIndex), Items.COAL) && player.containerMenu.carried.isEmpty
        }

        test.clickSlot(test.industrialChargePointer)
        test.waitForServer { player ->
            containerReference.get().getItem(test.chargeSlotIndex).isEmpty && test.stackMatches(player.containerMenu.carried, Items.REDSTONE)
        }
        test.clickSlot(test.industrialChargePointer)
        test.waitForServer { player ->
            test.stackMatches(containerReference.get().getItem(test.chargeSlotIndex), Items.REDSTONE) && player.containerMenu.carried.isEmpty
        }
        InventorySlotGestureParity.runIndustrial(test, containerReference.get())
        test.closeScreen()
        test.onServer { player -> player.closeContainer() }
    }

    private fun runPlayerInventoryScenario(
        test: InventorySlotTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val previous = AtomicReference<ItemStack>()
        test.onServer { player ->
            val inventory = player.inventory
            previous.set(inventory.getItem(test.playerInventoryIndex).copy())
            inventory.setItem(test.playerInventoryIndex, ItemStack(Items.DIRT, test.itemCount))
            player.inventoryMenu.broadcastChanges()
        }
        test.waitForPlayerItem(test.playerInventoryIndex, Items.DIRT)
        runMinecraftUiSessionTest(test.client, output)
        runMinecraftCanvasSlotTest(test.client, profile, output, test.playerInventoryIndex)
        runMinecraftSlotTooltipTest(test.client, profile, output, test.playerInventoryIndex) { fixture ->
            replaceTooltipFixture(test, fixture)
        }
        test.onServer { player ->
            player.inventory.setItem(test.playerInventoryIndex, ItemStack(Items.DIRT, test.itemCount))
            player.inventoryMenu.broadcastChanges()
        }
        test.waitForPlayerItem(test.playerInventoryIndex, Items.DIRT)
        showBoundScreen(test, profile, Slots.playerInventory(test.playerInventoryIndex))
        test.client.takeScreenshot(
            "strata-inventory-slot-fabric",
            output,
            IntSize(viewport.width, viewport.height),
        )

        test.clickSlot(playerSlotPointer)
        test.waitForServer { player ->
            player.inventory.getItem(test.playerInventoryIndex).isEmpty && test.stackMatches(player.containerMenu.carried, Items.DIRT)
        }
        test.clickSlot(playerSlotPointer)
        test.waitForServer { player ->
            test.stackMatches(player.inventory.getItem(test.playerInventoryIndex), Items.DIRT) && player.inventoryMenu.carried.isEmpty
        }
        test.closeScreen()
        test.onServer { player ->
            player.inventory.setItem(test.playerInventoryIndex, previous.get())
            player.inventoryMenu.broadcastChanges()
        }
    }

    private fun runContainerScenario(
        test: InventorySlotTestContext,
        profile: MinecraftUiProfile,
        output: Path,
        scenario: ContainerScenario,
    ) {
        val containerReference = AtomicReference<Container>()
        val previous = AtomicReference<ItemStack>()
        test.onServer { player ->
            val container =
                when (scenario) {
                    ContainerScenario.Custom -> SimpleContainer(test.containerSize)
                    ContainerScenario.EnderChest -> player.enderChestInventory
                }
            containerReference.set(container)
            previous.set(container.getItem(test.containerSlotIndex).copy())
            container.setItem(test.containerSlotIndex, ItemStack(scenario.item, test.itemCount))
            val provider =
                SimpleMenuProvider(
                    { containerId, inventory, _ -> ChestMenu.threeRows(containerId, inventory, container) },
                    Component.literal(scenario.title),
                )
            check(player.openMenu(provider).isPresent) { "The server-owned Container menu must open." }
        }
        test.client.waitFor { minecraft ->
            val player = minecraft.player
            player != null && player.containerMenu is ChestMenu && test.stackMatches(player.containerMenu.getSlot(0).item, scenario.item)
        }

        showBoundScreen(
            test,
            profile,
            Slots.playerInventory(test.playerInventoryIndex),
            scenario.binding,
        )
        if (scenario == ContainerScenario.Custom) {
            test.client.takeScreenshot(
                "strata-custom-container-slot-fabric",
                output,
                IntSize(viewport.width, viewport.height),
            )
        }
        test.clickSlot(containerSlotPointer)
        test.waitForServer { player ->
            containerReference.get().getItem(test.containerSlotIndex).isEmpty && test.stackMatches(player.containerMenu.carried, scenario.item)
        }
        test.clickSlot(containerSlotPointer)
        test.waitForServer { player ->
            test.stackMatches(containerReference.get().getItem(test.containerSlotIndex), scenario.item) && player.containerMenu.carried.isEmpty
        }
        test.closeScreen()
        test.onServer { player ->
            containerReference.get().setItem(test.containerSlotIndex, previous.get())
            player.closeContainer()
        }
    }

    private fun replaceTooltipFixture(
        test: InventorySlotTestContext,
        fixture: MinecraftSlotTooltipFixture,
    ) {
        val expected = AtomicReference<Pair<ItemStack, ItemStack>>()
        test.onServer { player ->
            val stack = MinecraftSlotTooltipTestStack.create(test.server.registryAccess(), fixture)
            val carried =
                if (fixture == MinecraftSlotTooltipFixture.CarriedSword || fixture == MinecraftSlotTooltipFixture.CarriedBundle) ItemStack(Items.DIRT, test.itemCount) else ItemStack.EMPTY
            player.inventory.setItem(test.playerInventoryIndex, stack)
            player.inventoryMenu.carried = carried
            player.inventoryMenu.broadcastChanges()
            expected.set(Pair(stack.copy(), carried.copy()))
        }
        test.client.waitFor { minecraft ->
            val player = minecraft.player
            val snapshot = expected.get()
            player != null && matchesMinecraftTooltipFixture(player.inventory.getItem(test.playerInventoryIndex), snapshot.first) && matchesMinecraftTooltipFixture(player.containerMenu.carried, snapshot.second)
        }
    }

    private fun showBoundScreen(
        test: InventorySlotTestContext,
        profile: MinecraftUiProfile,
        playerBinding: SlotBinding,
        containerBinding: SlotBinding? = null,
    ) {
        test.client.setScreen {
            createMinecraftScreen(
                createInventorySlotScreenDefinition(playerBinding, containerBinding),
                profile,
                parent = null,
            )
        }
        test.client.waitForScreen(FabricMinecraftScreen::class.java)
        // Native window transitions can center the pointer over a Slot; screenshots require a fixed non-hover position.
        test.client.movePointerRaw(0.0, 0.0)
        test.client.waitTicks(2)
    }

    private enum class ContainerScenario(
        val title: String,
        val item: Item,
        val binding: SlotBinding,
    ) {
        Custom("Custom storage", Items.PAPER, Slots.container(0)),
        EnderChest("Ender Chest", Items.FEATHER, Slots.activeMenu(0)),
    }

    private val playerSlotPointer = IntOffset(82, 181)
    private val containerSlotPointer = IntOffset(82, 56)
    private val viewport = IntSize(320, 240)
    private val industrialViewport = IntSize(320, 180)
}
