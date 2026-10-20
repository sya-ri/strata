package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Slots
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.InventorySlotTestContext.Companion.CHARGE_SLOT_INDEX
import dev.s7a.strata.integration.minecraft.fabric.InventorySlotTestContext.Companion.CONTAINER_SIZE
import dev.s7a.strata.integration.minecraft.fabric.InventorySlotTestContext.Companion.FUEL_SLOT_INDEX
import dev.s7a.strata.integration.minecraft.fabric.InventorySlotTestContext.Companion.ITEM_COUNT
import dev.s7a.strata.integration.minecraft.fabric.InventorySlotTestContext.Companion.industrialChargePointer
import dev.s7a.strata.integration.minecraft.fabric.InventorySlotTestContext.Companion.industrialFuelPointer
import dev.s7a.strata.integration.minecraft.fabric.InventorySlotTestContext.Companion.stackMatches
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MenuConstructor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.apache.commons.lang3.function.FailableConsumer
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate

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
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val test = InventorySlotTestContext(context)
        val previous = AtomicReference<ItemStack?>()
        val result =
            runCatching {
                test.onServer { player ->
                    previous.set(player.inventory.getItem(PLAYER_INVENTORY_INDEX).copy())
                    player.inventory.setItem(PLAYER_INVENTORY_INDEX, ItemStack(Items.DIRT, ITEM_COUNT))
                    player.inventoryMenu.broadcastChanges()
                }
                test.waitForPlayerItem(PLAYER_INVENTORY_INDEX, Items.DIRT)
                runMinecraftCanvasSlotTest(context, profile, output, PLAYER_INVENTORY_INDEX)
            }
        runCanvasTestCleanup(
            result.exceptionOrNull(),
            { test.closeScreen() },
            {
                previous.get()?.let { original ->
                    test.onServer { player ->
                        player.inventory.setItem(PLAYER_INVENTORY_INDEX, original)
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
        context: ClientGameTestContext,
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
            val container = SimpleContainer(CONTAINER_SIZE)
            containerReference.set(container)
            container.setItem(FUEL_SLOT_INDEX, ItemStack(Items.COAL, ITEM_COUNT))
            container.setItem(CHARGE_SLOT_INDEX, ItemStack(Items.REDSTONE, ITEM_COUNT))
            val provider =
                SimpleMenuProvider(
                    MenuConstructor { containerId, inventory, _ -> ChestMenu.threeRows(containerId, inventory, container) },
                    Component.literal("Coal Generator"),
                )
            check(player.openMenu(provider).isPresent) { "The server-owned coal generator menu must open." }
        }
        test.client.waitFor(
            Predicate<Minecraft> { minecraft ->
                val menu = minecraft.player?.containerMenu ?: return@Predicate false
                menu is ChestMenu &&
                    stackMatches(menu.getSlot(FUEL_SLOT_INDEX).item, Items.COAL) &&
                    stackMatches(menu.getSlot(CHARGE_SLOT_INDEX).item, Items.REDSTONE)
            },
        )
        resizeMinecraftTestWindow(test.client, IntSize(INDUSTRIAL_VIEWPORT_WIDTH, INDUSTRIAL_VIEWPORT_HEIGHT))
        test.client.runOnClient(FailableConsumer<Minecraft, RuntimeException> { minecraft -> minecraft.resizeGui() })
        test.client.setScreen { createMinecraftScreen(createIndustrialScreenDefinition(), profile, parent = null) }
        test.client.waitForScreen(FabricMinecraftScreen::class.java)
        test.client.waitTicks(2)
        test.client.takeScreenshot(
            TestScreenshotOptions
                .of("strata-industrial-synchronized-fabric")
                .disableCounterPrefix()
                .withSize(INDUSTRIAL_VIEWPORT_WIDTH, INDUSTRIAL_VIEWPORT_HEIGHT)
                .withDestinationDir(output),
        )

        test.clickSlot(industrialFuelPointer)
        test.waitForServer { player ->
            containerReference.get().getItem(FUEL_SLOT_INDEX).isEmpty && carriedMatches(player, Items.COAL)
        }
        test.clickSlot(industrialFuelPointer)
        test.waitForServer { player ->
            stackMatches(containerReference.get().getItem(FUEL_SLOT_INDEX), Items.COAL) && player.containerMenu.carried.isEmpty
        }

        test.clickSlot(industrialChargePointer)
        test.waitForServer { player ->
            containerReference.get().getItem(CHARGE_SLOT_INDEX).isEmpty && carriedMatches(player, Items.REDSTONE)
        }
        test.clickSlot(industrialChargePointer)
        test.waitForServer { player ->
            stackMatches(containerReference.get().getItem(CHARGE_SLOT_INDEX), Items.REDSTONE) && player.containerMenu.carried.isEmpty
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
            previous.set(inventory.getItem(PLAYER_INVENTORY_INDEX).copy())
            inventory.setItem(PLAYER_INVENTORY_INDEX, ItemStack(Items.DIRT, ITEM_COUNT))
            player.inventoryMenu.broadcastChanges()
        }
        test.waitForPlayerItem(PLAYER_INVENTORY_INDEX, Items.DIRT)
        runMinecraftUiSessionTest(test.client, output)
        runMinecraftCanvasSlotTest(test.client, profile, output, PLAYER_INVENTORY_INDEX)
        runMinecraftSlotTooltipTest(test.client, profile, output, PLAYER_INVENTORY_INDEX) { fixture ->
            replaceTooltipFixture(test, fixture)
        }
        test.onServer { player ->
            player.inventory.setItem(PLAYER_INVENTORY_INDEX, ItemStack(Items.DIRT, ITEM_COUNT))
            player.inventoryMenu.broadcastChanges()
        }
        test.waitForPlayerItem(PLAYER_INVENTORY_INDEX, Items.DIRT)
        showBoundScreen(test, profile, Slots.playerInventory(PLAYER_INVENTORY_INDEX))
        test.client.takeScreenshot(
            TestScreenshotOptions
                .of("strata-inventory-slot-fabric")
                .disableCounterPrefix()
                .withSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .withDestinationDir(output),
        )

        test.clickSlot(playerSlotPointer)
        test.waitForServer { player ->
            player.inventory.getItem(PLAYER_INVENTORY_INDEX).isEmpty && carriedMatches(player, Items.DIRT)
        }
        test.clickSlot(playerSlotPointer)
        test.waitForServer { player ->
            stackMatches(player.inventory.getItem(PLAYER_INVENTORY_INDEX), Items.DIRT) && player.inventoryMenu.carried.isEmpty
        }
        test.closeScreen()
        test.onServer { player ->
            player.inventory.setItem(PLAYER_INVENTORY_INDEX, previous.get())
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
                    ContainerScenario.Custom -> SimpleContainer(CONTAINER_SIZE)
                    ContainerScenario.EnderChest -> player.enderChestInventory
                }
            containerReference.set(container)
            previous.set(container.getItem(CONTAINER_SLOT_INDEX).copy())
            container.setItem(CONTAINER_SLOT_INDEX, ItemStack(scenario.item, ITEM_COUNT))
            val provider =
                SimpleMenuProvider(
                    MenuConstructor { containerId, inventory, _ -> ChestMenu.threeRows(containerId, inventory, container) },
                    Component.literal(scenario.title),
                )
            check(player.openMenu(provider).isPresent) { "The server-owned Container menu must open." }
        }
        test.client.waitFor(
            Predicate<Minecraft> { minecraft ->
                val player = minecraft.player
                player != null && player.containerMenu is ChestMenu && stackMatches(player.containerMenu.getSlot(0).item, scenario.item)
            },
        )

        showBoundScreen(
            test,
            profile,
            Slots.playerInventory(PLAYER_INVENTORY_INDEX),
            scenario.binding,
        )
        if (scenario == ContainerScenario.Custom) {
            test.client.takeScreenshot(
                TestScreenshotOptions
                    .of("strata-custom-container-slot-fabric")
                    .disableCounterPrefix()
                    .withSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                    .withDestinationDir(output),
            )
        }
        test.clickSlot(containerSlotPointer)
        test.waitForServer { player ->
            containerReference.get().getItem(CONTAINER_SLOT_INDEX).isEmpty && carriedMatches(player, scenario.item)
        }
        test.clickSlot(containerSlotPointer)
        test.waitForServer { player ->
            stackMatches(containerReference.get().getItem(CONTAINER_SLOT_INDEX), scenario.item) && player.containerMenu.carried.isEmpty
        }
        test.closeScreen()
        test.onServer { player ->
            containerReference.get().setItem(CONTAINER_SLOT_INDEX, previous.get())
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
                if (fixture == MinecraftSlotTooltipFixture.CarriedSword || fixture == MinecraftSlotTooltipFixture.CarriedBundle) ItemStack(Items.DIRT, ITEM_COUNT) else ItemStack.EMPTY
            player.inventory.setItem(PLAYER_INVENTORY_INDEX, stack)
            player.inventoryMenu.carried = carried
            player.inventoryMenu.broadcastChanges()
            expected.set(Pair(stack.copy(), carried.copy()))
        }
        test.client.waitFor(
            Predicate<Minecraft> { minecraft ->
                val player = minecraft.player
                val snapshot = expected.get()
                player != null && matchesMinecraftTooltipFixture(player.inventory.getItem(PLAYER_INVENTORY_INDEX), snapshot.first) && matchesMinecraftTooltipFixture(player.containerMenu.carried, snapshot.second)
            },
        )
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
        test.client.input.setCursorPos(0.0, 0.0)
        test.client.waitTicks(2)
    }

    private fun carriedMatches(
        player: ServerPlayer,
        item: Item,
    ): Boolean = stackMatches(player.containerMenu.carried, item)

    private enum class ContainerScenario(
        val title: String,
        val item: Item,
        val binding: SlotBinding,
    ) {
        Custom("Custom storage", Items.PAPER, Slots.container(CONTAINER_SLOT_INDEX)),
        EnderChest("Ender Chest", Items.FEATHER, Slots.activeMenu(CONTAINER_SLOT_INDEX)),
    }

    private val playerSlotPointer = IntOffset(82, 181)
    private val containerSlotPointer = IntOffset(82, 56)
    private const val PLAYER_INVENTORY_INDEX: Int = 0
    private const val CONTAINER_SLOT_INDEX: Int = 0
    private const val VIEWPORT_WIDTH: Int = 320
    private const val VIEWPORT_HEIGHT: Int = 240
    private const val INDUSTRIAL_VIEWPORT_WIDTH: Int = 320
    private const val INDUSTRIAL_VIEWPORT_HEIGHT: Int = 180
}
