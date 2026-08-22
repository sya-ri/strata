package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Slots
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
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
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate

/**
 * Verifies player-inventory, logical Container, and raw active-menu Slot locators against the loaded Minecraft 26.2 client and integrated server.
 *
 * Every scenario seeds storage on the server thread, opens any required server-owned menu, performs native pointer pickup through Fabric input, observes the authoritative server mutation, restores the stack, and cleans up the menu.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object InventorySlotSynchronizationGameTest {
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
        closeFabricScreen(context)
        val server =
            context.computeOnClient(
                FailableFunction<Minecraft, MinecraftServer, RuntimeException> { minecraft -> checkNotNull(minecraft.singleplayerServer) },
            )
        val playerId =
            context.computeOnClient(
                FailableFunction<Minecraft, UUID, RuntimeException> { minecraft -> checkNotNull(minecraft.player).uuid },
            )
        runPlayerInventoryScenario(context, profile, output, server, playerId)
        runContainerScenario(context, profile, output, server, playerId, ContainerScenario.Custom)
        runContainerScenario(context, profile, output, server, playerId, ContainerScenario.EnderChest)
        runIndustrialContainerScenario(context, profile, output, server, playerId)
    }

    private fun runIndustrialContainerScenario(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
        server: MinecraftServer,
        playerId: UUID,
    ) {
        val containerReference = AtomicReference<SimpleContainer>()
        onServer(context, server, playerId) { player ->
            val container = SimpleContainer(containerSize)
            containerReference.set(container)
            container.setItem(fuelSlotIndex, ItemStack(Items.COAL, itemCount))
            container.setItem(chargeSlotIndex, ItemStack(Items.REDSTONE, itemCount))
            val provider =
                SimpleMenuProvider(
                    MenuConstructor { containerId, inventory, _ -> ChestMenu.threeRows(containerId, inventory, container) },
                    Component.literal("Coal Generator"),
                )
            check(player.openMenu(provider).isPresent) { "The server-owned coal generator menu must open." }
        }
        context.waitFor(
            Predicate<Minecraft> { minecraft ->
                val menu = minecraft.player?.containerMenu ?: return@Predicate false
                menu is ChestMenu &&
                    stackMatches(menu.getSlot(fuelSlotIndex).item, Items.COAL) &&
                    stackMatches(menu.getSlot(chargeSlotIndex).item, Items.REDSTONE)
            },
        )
        context.input.resizeWindow(industrialViewportWidth, industrialViewportHeight)
        context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { minecraft -> minecraft.resizeGui() })
        context.setScreen { createMinecraftScreen(createIndustrialScreenDefinition(), profile, parent = null) }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        context.waitTicks(2)
        context.takeScreenshot(
            TestScreenshotOptions
                .of("strata-industrial-synchronized-fabric")
                .disableCounterPrefix()
                .withSize(industrialViewportWidth, industrialViewportHeight)
                .withDestinationDir(output),
        )

        clickSlot(context, industrialFuelPointer)
        waitForServer(context, server, playerId) { player ->
            containerReference.get().getItem(fuelSlotIndex).isEmpty && carriedMatches(player, Items.COAL)
        }
        clickSlot(context, industrialFuelPointer)
        waitForServer(context, server, playerId) { player ->
            stackMatches(containerReference.get().getItem(fuelSlotIndex), Items.COAL) && player.containerMenu.carried.isEmpty
        }

        clickSlot(context, industrialChargePointer)
        waitForServer(context, server, playerId) { player ->
            containerReference.get().getItem(chargeSlotIndex).isEmpty && carriedMatches(player, Items.REDSTONE)
        }
        clickSlot(context, industrialChargePointer)
        waitForServer(context, server, playerId) { player ->
            stackMatches(containerReference.get().getItem(chargeSlotIndex), Items.REDSTONE) && player.containerMenu.carried.isEmpty
        }
        closeFabricScreen(context)
        onServer(context, server, playerId) { player -> player.closeContainer() }
    }

    private fun runPlayerInventoryScenario(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
        server: MinecraftServer,
        playerId: UUID,
    ) {
        val previous = AtomicReference<ItemStack>()
        onServer(context, server, playerId) { player ->
            val inventory = player.inventory
            previous.set(inventory.getItem(playerInventoryIndex).copy())
            inventory.setItem(playerInventoryIndex, ItemStack(Items.DIRT, itemCount))
            player.inventoryMenu.broadcastChanges()
        }
        waitForPlayerItem(context, playerInventoryIndex, Items.DIRT)
        showBoundScreen(context, profile, Slots.playerInventory(playerInventoryIndex))
        context.takeScreenshot(
            TestScreenshotOptions
                .of("strata-inventory-slot-fabric")
                .disableCounterPrefix()
                .withSize(viewportWidth, viewportHeight)
                .withDestinationDir(output),
        )

        clickSlot(context, playerSlotPointer)
        waitForServer(context, server, playerId) { player ->
            player.inventory.getItem(playerInventoryIndex).isEmpty && carriedMatches(player, Items.DIRT)
        }
        clickSlot(context, playerSlotPointer)
        waitForServer(context, server, playerId) { player ->
            stackMatches(player.inventory.getItem(playerInventoryIndex), Items.DIRT) && player.inventoryMenu.carried.isEmpty
        }
        closeFabricScreen(context)
        onServer(context, server, playerId) { player ->
            player.inventory.setItem(playerInventoryIndex, previous.get())
            player.inventoryMenu.broadcastChanges()
        }
    }

    @Suppress("LongParameterList")
    private fun runContainerScenario(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
        server: MinecraftServer,
        playerId: UUID,
        scenario: ContainerScenario,
    ) {
        val containerReference = AtomicReference<Container>()
        val previous = AtomicReference<ItemStack>()
        onServer(context, server, playerId) { player ->
            val container =
                when (scenario) {
                    ContainerScenario.Custom -> SimpleContainer(containerSize)
                    ContainerScenario.EnderChest -> player.enderChestInventory
                }
            containerReference.set(container)
            previous.set(container.getItem(containerSlotIndex).copy())
            container.setItem(containerSlotIndex, ItemStack(scenario.item, itemCount))
            val provider =
                SimpleMenuProvider(
                    MenuConstructor { containerId, inventory, _ -> ChestMenu.threeRows(containerId, inventory, container) },
                    Component.literal(scenario.title),
                )
            check(player.openMenu(provider).isPresent) { "The server-owned Container menu must open." }
        }
        context.waitFor(
            Predicate<Minecraft> { minecraft ->
                val player = minecraft.player
                player != null && player.containerMenu is ChestMenu && stackMatches(player.containerMenu.getSlot(0).item, scenario.item)
            },
        )

        showBoundScreen(
            context,
            profile,
            Slots.playerInventory(playerInventoryIndex),
            scenario.binding,
        )
        if (scenario == ContainerScenario.Custom) {
            context.takeScreenshot(
                TestScreenshotOptions
                    .of("strata-custom-container-slot-fabric")
                    .disableCounterPrefix()
                    .withSize(viewportWidth, viewportHeight)
                    .withDestinationDir(output),
            )
        }
        clickSlot(context, containerSlotPointer)
        waitForServer(context, server, playerId) { player ->
            containerReference.get().getItem(containerSlotIndex).isEmpty && carriedMatches(player, scenario.item)
        }
        clickSlot(context, containerSlotPointer)
        waitForServer(context, server, playerId) { player ->
            stackMatches(containerReference.get().getItem(containerSlotIndex), scenario.item) && player.containerMenu.carried.isEmpty
        }
        closeFabricScreen(context)
        onServer(context, server, playerId) { player ->
            containerReference.get().setItem(containerSlotIndex, previous.get())
            player.closeContainer()
        }
    }

    private fun showBoundScreen(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        playerBinding: SlotBinding,
        containerBinding: SlotBinding? = null,
    ) {
        context.setScreen {
            createMinecraftScreen(
                createInventorySlotScreenDefinition(playerBinding, containerBinding),
                profile,
                parent = null,
            )
        }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        context.waitTicks(2)
    }

    private fun waitForPlayerItem(
        context: ClientGameTestContext,
        index: Int,
        item: Item,
    ) {
        context.waitFor(
            Predicate<Minecraft> { minecraft ->
                val player = minecraft.player ?: return@Predicate false
                player.inventory.getItem(index).`is`(item)
            },
        )
    }

    private fun clickSlot(
        context: ClientGameTestContext,
        pointer: IntOffset,
    ) {
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val screen = minecraft.gui.screen() as? FabricMinecraftScreen ?: error("The synchronized Slot screen is not active.")
                val event = MouseButtonEvent(pointer.x.toDouble(), pointer.y.toDouble(), MouseButtonInfo(primaryMouseButton, noModifiers))
                check(screen.mouseClicked(event, false)) { "The synchronized Slot must consume a primary press." }
                check(screen.mouseReleased(event)) { "The synchronized Slot must consume the matching release." }
            },
        )
    }

    private fun onServer(
        context: ClientGameTestContext,
        server: MinecraftServer,
        playerId: UUID,
        operation: (ServerPlayer) -> Unit,
    ) {
        val completed = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        server.execute(
            Runnable {
                runCatching { operation(checkNotNull(server.playerList.getPlayer(playerId))) }
                    .exceptionOrNull()
                    ?.let(failure::set)
                completed.set(true)
            },
        )
        context.waitFor(Predicate<Minecraft> { completed.get() })
        failure.get()?.let { throwable -> throw throwable }
    }

    private fun waitForServer(
        context: ClientGameTestContext,
        server: MinecraftServer,
        playerId: UUID,
        condition: (ServerPlayer) -> Boolean,
    ) {
        val matched = AtomicBoolean()
        val pending = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        context.waitFor(
            Predicate<Minecraft> {
                failure.get()?.let { throwable -> throw throwable }
                if (matched.get()) {
                    true
                } else {
                    if (pending.compareAndSet(false, true)) {
                        server.execute(
                            Runnable {
                                runCatching { matched.set(condition(checkNotNull(server.playerList.getPlayer(playerId)))) }
                                    .exceptionOrNull()
                                    ?.let(failure::set)
                                pending.set(false)
                            },
                        )
                    }
                    false
                }
            },
        )
        failure.get()?.let { throwable -> throw throwable }
    }

    private fun carriedMatches(
        player: ServerPlayer,
        item: Item,
    ): Boolean = stackMatches(player.containerMenu.carried, item)

    private fun stackMatches(
        stack: ItemStack,
        item: Item,
    ): Boolean = stack.`is`(item) && stack.count == itemCount

    private fun closeFabricScreen(context: ClientGameTestContext) {
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val current = minecraft.gui.screen()
                if (current is FabricMinecraftScreen) current.onClose()
            },
        )
        context.waitFor(Predicate<Minecraft> { minecraft -> (minecraft.gui.screen() is FabricMinecraftScreen).not() })
    }

    private enum class ContainerScenario(
        val title: String,
        val item: Item,
        val binding: SlotBinding,
    ) {
        Custom("Custom storage", Items.PAPER, Slots.container(containerSlotIndex)),
        EnderChest("Ender Chest", Items.FEATHER, Slots.activeMenu(containerSlotIndex)),
    }

    private val playerSlotPointer = IntOffset(82, 181)
    private val containerSlotPointer = IntOffset(82, 56)
    private val industrialFuelPointer = IntOffset(90, 49)
    private val industrialChargePointer = IntOffset(223, 49)

    @Suppress("MayBeConstant")
    private val playerInventoryIndex = 0

    @Suppress("MayBeConstant")
    private val containerSlotIndex = 0

    @Suppress("MayBeConstant")
    private val fuelSlotIndex = 0

    @Suppress("MayBeConstant")
    private val chargeSlotIndex = 1

    @Suppress("MayBeConstant")
    private val containerSize = 27

    @Suppress("MayBeConstant")
    private val itemCount = 7

    @Suppress("MayBeConstant")
    private val primaryMouseButton = 0

    @Suppress("MayBeConstant")
    private val noModifiers = 0

    @Suppress("MayBeConstant")
    private val viewportWidth = 320

    @Suppress("MayBeConstant")
    private val viewportHeight = 240

    @Suppress("MayBeConstant")
    private val industrialViewportWidth = 320

    @Suppress("MayBeConstant")
    private val industrialViewportHeight = 180
}
