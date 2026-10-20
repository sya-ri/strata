package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuConstructor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import org.apache.commons.lang3.function.FailableConsumer
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate

/**
 * Compares native inventory gestures with independent reference menus on the integrated server thread.
 * The caller retains the world, menu and screen lifetime around industrial comparisons.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object InventorySlotGestureParity {
    /**
     * Compares pick-item, double-click and overlapping-button behavior in the caller-owned industrial menu.
     */
    internal fun runIndustrial(
        test: InventorySlotTestContext,
        container: SimpleContainer,
    ) {
        runCreativePickScenario(test, container)
        runCrossSlotDoubleClickScenario(test, container)
        runSameSlotDoubleClickScenario(test, container)
        runOverlappingButtonsScenario(test, container)
    }

    /**
     * Verifies native pick-item release and creative drag filling against authoritative server stacks.
     */
    private fun runCreativePickScenario(
        test: InventorySlotTestContext,
        container: SimpleContainer,
    ) {
        val previousMode = AtomicReference<GameType>()
        val expected = AtomicReference<NativeInventoryOutcome>()
        val result =
            runCatching {
                test.onServer { player ->
                    previousMode.set(player.gameMode.gameModeForPlayer)
                    player.setGameMode(GameType.CREATIVE)
                    player.containerMenu.carried = ItemStack(Items.DIRT, 1)
                    player.containerMenu.broadcastChanges()
                    expected.set(nativeCreativeOutcome(test, player, container, drag = false))
                }
                test.client.waitFor(
                    Predicate<Minecraft> { minecraft ->
                        val player = minecraft.player ?: return@Predicate false
                        player.hasInfiniteMaterials() && player.containerMenu.carried.`is`(Items.DIRT)
                    },
                )
                sendPickInput(test, drag = false)
                test.waitForServer { player ->
                    expected.get().matches(player.containerMenu)
                }
                test.onServer { player ->
                    container.clearContent()
                    player.containerMenu.carried = ItemStack(Items.DIRT, 1)
                    player.containerMenu.broadcastChanges()
                    expected.set(nativeCreativeOutcome(test, player, container, drag = true))
                }
                test.client.waitFor(
                    Predicate<Minecraft> { minecraft ->
                        val menu = minecraft.player?.containerMenu ?: return@Predicate false
                        menu.getSlot(test.fuelSlotIndex).item.isEmpty && menu.getSlot(test.chargeSlotIndex).item.isEmpty && menu.carried.`is`(Items.DIRT)
                    },
                )
                sendPickInput(test, drag = true)
                test.waitForServer { player ->
                    expected.get().matches(player.containerMenu)
                }
                runPickBindingConflicts(test, container)
            }
        runCanvasTestCleanup(result.exceptionOrNull(), {
            test.onServer { player ->
                player.containerMenu.carried = ItemStack.EMPTY
                previousMode.get()?.let { mode -> player.setGameMode(mode) }
                player.containerMenu.broadcastChanges()
            }
        })
        result.getOrThrow()
    }

    /**
     * Computes the reference on the server thread through an independent native menu with copied storage and cursor state.
     */
    private fun nativeCreativeOutcome(
        test: InventorySlotTestContext,
        player: ServerPlayer,
        container: SimpleContainer,
        drag: Boolean,
    ): NativeInventoryOutcome =
        nativeInventoryOutcome(test, player, container) { menu ->
            if (drag) {
                val mode = AbstractContainerMenu.QUICKCRAFT_TYPE_CLONE
                menu.clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_START, mode), ContainerInput.QUICK_CRAFT, player)
                for (index in listOf(test.fuelSlotIndex, test.chargeSlotIndex)) {
                    menu.clicked(index, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE, mode), ContainerInput.QUICK_CRAFT, player)
                }
                menu.clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_END, mode), ContainerInput.QUICK_CRAFT, player)
                check(listOf(test.fuelSlotIndex, test.chargeSlotIndex).all { index -> menu.getSlot(index).item.count == menu.getSlot(index).item.maxStackSize }) { "The native oracle must fill both creative drag slots." }
            } else {
                menu.clicked(test.fuelSlotIndex, InputConstants.MOUSE_BUTTON_MIDDLE, ContainerInput.CLONE, player)
            }
        }

    /**
     * Applies independent native menu operations to copied storage on the server thread without attaching another live menu.
     */
    private fun nativeInventoryOutcome(
        test: InventorySlotTestContext,
        player: ServerPlayer,
        container: SimpleContainer,
        operation: (ChestMenu) -> Unit,
    ): NativeInventoryOutcome {
        val storage = SimpleContainer(container.containerSize)
        for (index in 0 until container.containerSize) storage.setItem(index, container.getItem(index).copy())
        val menu = ChestMenu.threeRows(0, player.inventory, storage)
        menu.carried = player.containerMenu.carried.copy()
        operation(menu)
        return NativeInventoryOutcome(
            firstSlot = storage.getItem(test.fuelSlotIndex).copy(),
            secondSlot = storage.getItem(test.chargeSlotIndex).copy(),
            carried = menu.carried.copy(),
            firstSlotIndex = test.fuelSlotIndex,
            secondSlotIndex = test.chargeSlotIndex,
        )
    }

    /**
     * Ensures a native double-click marker does not collect stacks after the pointer changes native slot identity.
     */
    private fun runCrossSlotDoubleClickScenario(
        test: InventorySlotTestContext,
        container: SimpleContainer,
    ) {
        val expected = AtomicReference<NativeInventoryOutcome>()
        test.onServer { player ->
            container.setItem(test.fuelSlotIndex, ItemStack(Items.DIRT, test.itemCount))
            container.setItem(test.chargeSlotIndex, ItemStack(Items.DIRT, test.itemCount))
            player.containerMenu.carried = ItemStack.EMPTY
            player.containerMenu.broadcastChanges()
            expected.set(
                nativeInventoryOutcome(test, player, container) { menu ->
                    menu.clicked(test.fuelSlotIndex, 0, ContainerInput.PICKUP, player)
                    menu.clicked(test.chargeSlotIndex, 0, ContainerInput.PICKUP, player)
                },
            )
        }
        test.client.waitFor(
            Predicate<Minecraft> { minecraft ->
                val menu = minecraft.player?.containerMenu ?: return@Predicate false
                test.stackMatches(menu.getSlot(test.fuelSlotIndex).item, Items.DIRT) && test.stackMatches(menu.getSlot(test.chargeSlotIndex).item, Items.DIRT) && menu.carried.isEmpty
            },
        )
        test.client.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val screen = MinecraftClientScreenAccess.currentScreen(minecraft) as FabricMinecraftScreen
                val first = test.mouseEvent(test.industrialFuelPointer, InputConstants.MOUSE_BUTTON_LEFT)
                val second = test.mouseEvent(test.industrialChargePointer, InputConstants.MOUSE_BUTTON_LEFT)
                check(screen.mouseClicked(first, false) && screen.mouseReleased(first)) { "The first slot must accept a complete click." }
                check(screen.mouseClicked(second, true) && screen.mouseReleased(second)) { "A double-click marker on another slot must remain an ordinary click." }
            },
        )
        test.waitForServer { player -> expected.get().matches(player.containerMenu) }
    }

    /**
     * Verifies a secondary press starts fresh release handling while the primary button is still held.
     */
    private fun runOverlappingButtonsScenario(
        test: InventorySlotTestContext,
        container: SimpleContainer,
    ) {
        val expected = AtomicReference<NativeInventoryOutcome>()
        test.onServer { player ->
            container.setItem(test.fuelSlotIndex, ItemStack(Items.DIRT, test.itemCount))
            container.setItem(test.chargeSlotIndex, ItemStack.EMPTY)
            player.containerMenu.carried = ItemStack.EMPTY
            player.containerMenu.broadcastChanges()
            expected.set(
                nativeInventoryOutcome(test, player, container) { menu ->
                    menu.clicked(test.fuelSlotIndex, 0, ContainerInput.PICKUP, player)
                    menu.clicked(test.fuelSlotIndex, 1, ContainerInput.PICKUP, player)
                },
            )
        }
        test.client.waitFor(
            Predicate<Minecraft> { minecraft ->
                val menu = minecraft.player?.containerMenu ?: return@Predicate false
                test.stackMatches(menu.getSlot(test.fuelSlotIndex).item, Items.DIRT) && menu.getSlot(test.chargeSlotIndex).item.isEmpty && menu.carried.isEmpty
            },
        )
        val result =
            runCatching {
                test.client.runOnClient(
                    FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                        val screen = MinecraftClientScreenAccess.currentScreen(minecraft) as FabricMinecraftScreen
                        check(screen.mouseClicked(test.mouseEvent(test.industrialFuelPointer, InputConstants.MOUSE_BUTTON_LEFT), false))
                        val secondary = test.mouseEvent(test.industrialFuelPointer, InputConstants.MOUSE_BUTTON_RIGHT)
                        check(screen.mouseClicked(secondary, false) && screen.mouseReleased(secondary))
                    },
                )
                test.waitForServer { player -> expected.get().matches(player.containerMenu) }
            }
        runCanvasTestCleanup(result.exceptionOrNull(), {
            test.client.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    val screen = MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen
                    screen?.mouseReleased(test.mouseEvent(test.industrialFuelPointer, InputConstants.MOUSE_BUTTON_LEFT))
                },
            )
        })
        result.getOrThrow()
        test.waitForServer { player -> test.stackMatches(container.getItem(test.fuelSlotIndex), Items.DIRT) && player.containerMenu.carried.isEmpty }
    }

    /**
     * Keeps same-slot collection native while isolating the oracle from unrelated player-inventory stacks.
     */
    private fun runSameSlotDoubleClickScenario(
        test: InventorySlotTestContext,
        container: SimpleContainer,
    ) {
        val previous = AtomicReference<List<Pair<Int, ItemStack>>>()
        val expected = AtomicReference<NativeInventoryOutcome>()
        val result =
            runCatching {
                test.onServer { player ->
                    val inventory = player.inventory
                    previous.set((0 until inventory.containerSize).filter { index -> inventory.getItem(index).`is`(Items.DIRT) }.map { index -> index to inventory.getItem(index).copy() })
                    for ((index, _) in previous.get()) inventory.setItem(index, ItemStack.EMPTY)
                    container.clearContent()
                    container.setItem(test.fuelSlotIndex, ItemStack(Items.DIRT, test.itemCount))
                    container.setItem(test.chargeSlotIndex, ItemStack(Items.DIRT, test.itemCount))
                    player.containerMenu.carried = ItemStack.EMPTY
                    player.containerMenu.broadcastChanges()
                    expected.set(
                        nativeInventoryOutcome(test, player, container) { menu ->
                            menu.clicked(test.fuelSlotIndex, 0, ContainerInput.PICKUP, player)
                            menu.clicked(test.fuelSlotIndex, 0, ContainerInput.PICKUP_ALL, player)
                        },
                    )
                }
                test.client.waitFor(
                    Predicate<Minecraft> { minecraft ->
                        val menu = minecraft.player?.containerMenu ?: return@Predicate false
                        test.stackMatches(menu.getSlot(test.fuelSlotIndex).item, Items.DIRT) && test.stackMatches(menu.getSlot(test.chargeSlotIndex).item, Items.DIRT) && menu.carried.isEmpty
                    },
                )
                test.client.runOnClient(
                    FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                        val screen = MinecraftClientScreenAccess.currentScreen(minecraft) as FabricMinecraftScreen
                        val mouse = test.mouseEvent(test.industrialFuelPointer, InputConstants.MOUSE_BUTTON_LEFT)
                        check(screen.mouseClicked(mouse, false) && screen.mouseReleased(mouse))
                        check(screen.mouseClicked(mouse, true) && screen.mouseReleased(mouse))
                    },
                )
                test.waitForServer { player -> expected.get().matches(player.containerMenu) }
            }
        runCanvasTestCleanup(result.exceptionOrNull(), {
            test.onServer { player ->
                previous.get()?.forEach { (index, stack) -> player.inventory.setItem(index, stack) }
                player.containerMenu.carried = ItemStack.EMPTY
                player.containerMenu.broadcastChanges()
            }
        })
        result.getOrThrow()
    }

    /**
     * Verifies native creative pick precedence when a hotbar or offhand binding uses the same mouse button.
     */
    private fun runPickBindingConflicts(
        test: InventorySlotTestContext,
        container: SimpleContainer,
    ) {
        for (conflict in PickBindingConflict.entries) {
            val expected = AtomicReference<NativeInventoryOutcome>()
            val previousKey = AtomicReference<InputConstants.Key>()
            val result =
                runCatching {
                    test.onServer { player ->
                        player.containerMenu.carried = ItemStack.EMPTY
                        player.containerMenu.broadcastChanges()
                        expected.set(nativeCreativeOutcome(test, player, container, drag = false))
                    }
                    test.client.waitFor(
                        Predicate<Minecraft> { minecraft ->
                            minecraft.player
                                ?.containerMenu
                                ?.carried
                                ?.isEmpty == true
                        },
                    )
                    test.client.runOnClient(
                        FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                            val binding = conflict.binding(minecraft)
                            previousKey.set(InputConstants.getKey(binding.saveString()))
                            binding.setKey(InputConstants.getKey(minecraft.options.keyPickItem.saveString()))
                            KeyMapping.resetMapping()
                        },
                    )
                    sendPickInput(test, drag = false)
                    test.waitForServer { player -> expected.get().matches(player.containerMenu) }
                }
            runCanvasTestCleanup(result.exceptionOrNull(), {
                previousKey.get()?.let { key ->
                    test.client.runOnClient(
                        FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                            conflict.binding(minecraft).setKey(key)
                            KeyMapping.resetMapping()
                        },
                    )
                }
            }, {
                test.onServer { player ->
                    player.containerMenu.carried = ItemStack.EMPTY
                    player.containerMenu.broadcastChanges()
                }
            })
            result.getOrThrow()
        }
    }

    /**
     * Verifies that mirrored views consume one native quick-craft target, leaving room for another slot.
     */
    internal fun runMirroredSlotDragScenario(
        test: InventorySlotTestContext,
        profile: MinecraftUiProfile,
    ) {
        val expected = AtomicReference<NativeInventoryOutcome>()
        val storage = AtomicReference<SimpleContainer>()
        val result =
            runCatching {
                test.onServer { player ->
                    val container = SimpleContainer(test.containerSize)
                    storage.set(container)
                    val provider =
                        SimpleMenuProvider(
                            MenuConstructor { containerId, inventory, _ -> ChestMenu.threeRows(containerId, inventory, container) },
                            Component.literal("Mirrored inventory slots"),
                        )
                    check(player.openMenu(provider).isPresent)
                    player.containerMenu.carried = ItemStack(Items.DIRT, 2)
                    player.containerMenu.broadcastChanges()
                    expected.set(
                        nativeInventoryOutcome(test, player, container) { menu ->
                            val mode = AbstractContainerMenu.QUICKCRAFT_TYPE_CHARITABLE
                            menu.clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_START, mode), ContainerInput.QUICK_CRAFT, player)
                            for (index in listOf(test.fuelSlotIndex, test.chargeSlotIndex)) {
                                menu.clicked(index, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE, mode), ContainerInput.QUICK_CRAFT, player)
                            }
                            menu.clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_END, mode), ContainerInput.QUICK_CRAFT, player)
                        },
                    )
                }
                test.client.waitFor(
                    Predicate<Minecraft> { minecraft ->
                        val menu = minecraft.player?.containerMenu ?: return@Predicate false
                        menu is ChestMenu && menu.getSlot(test.fuelSlotIndex).item.isEmpty && menu.getSlot(test.chargeSlotIndex).item.isEmpty && menu.carried.count == 2
                    },
                )
                test.client.setScreen { createMinecraftScreen(createInventoryMirrorDefinition(), profile, parent = null) }
                test.client.waitForScreen(FabricMinecraftScreen::class.java)
                test.client.waitTicks(2)
                test.client.runOnClient(
                    FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                        val screen = MinecraftClientScreenAccess.currentScreen(minecraft) as FabricMinecraftScreen
                        check(screen.mouseClicked(test.mouseEvent(IntOffset(9, 9), InputConstants.MOUSE_BUTTON_LEFT), false))
                        for (pointer in listOf(IntOffset(9, 9), IntOffset(27, 9), IntOffset(45, 9))) {
                            check(screen.mouseDragged(test.mouseEvent(pointer, InputConstants.MOUSE_BUTTON_LEFT), 18.0, 0.0)) { "Every mirrored-slot drag target must consume input." }
                        }
                        check(screen.mouseReleased(test.mouseEvent(IntOffset(45, 9), InputConstants.MOUSE_BUTTON_LEFT)))
                    },
                )
                test.waitForServer { player -> expected.get().matches(player.containerMenu) }
                runCancelledDragScenario(test, storage.get())
            }
        runCanvasTestCleanup(result.exceptionOrNull(), { test.closeScreen() }, {
            test.onServer { player ->
                player.containerMenu.carried = ItemStack.EMPTY
                player.closeContainer()
            }
        })
        result.getOrThrow()
    }

    /**
     * Verifies that an unrelated button release cancels the native drag before the original button is released.
     */
    private fun runCancelledDragScenario(
        test: InventorySlotTestContext,
        container: SimpleContainer,
    ) {
        test.onServer { player ->
            container.clearContent()
            player.containerMenu.carried = ItemStack(Items.DIRT, 2)
            player.containerMenu.broadcastChanges()
        }
        test.client.waitFor(
            Predicate<Minecraft> { minecraft ->
                val menu = minecraft.player?.containerMenu ?: return@Predicate false
                menu.getSlot(test.fuelSlotIndex).item.isEmpty && menu.getSlot(test.chargeSlotIndex).item.isEmpty && menu.carried.count == 2
            },
        )
        test.client.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val screen = MinecraftClientScreenAccess.currentScreen(minecraft) as FabricMinecraftScreen
                val primary = test.mouseEvent(IntOffset(9, 9), InputConstants.MOUSE_BUTTON_LEFT)
                check(screen.mouseClicked(primary, false))
                check(screen.mouseDragged(primary, 0.0, 0.0))
                val second = test.mouseEvent(IntOffset(45, 9), InputConstants.MOUSE_BUTTON_LEFT)
                check(screen.mouseDragged(second, 36.0, 0.0))
                val other = test.mouseEvent(IntOffset(45, 9), InputConstants.MOUSE_BUTTON_4)
                screen.mouseClicked(other, false)
                check(screen.mouseReleased(other)) { "An unrelated release must cancel the active native drag." }
                check(screen.mouseReleased(second))
                val menu = checkNotNull(minecraft.player).containerMenu
                check(menu.getSlot(test.fuelSlotIndex).item.isEmpty && menu.getSlot(test.chargeSlotIndex).item.isEmpty && menu.carried.count == 2) { "A cancelled native drag must preserve predicted client inventory state." }
            },
        )
        test.waitForServer { player ->
            container.getItem(test.fuelSlotIndex).isEmpty && container.getItem(test.chargeSlotIndex).isEmpty && player.containerMenu.carried.count == 2
        }
    }

    /**
     * Sends one native pick click or a drag crossing two independently bound slots.
     */
    private fun sendPickInput(
        test: InventorySlotTestContext,
        drag: Boolean,
    ) {
        test.client.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val screen = MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen ?: error("The synchronized Slot screen is not active.")
                val press = MouseButtonEvent(test.industrialFuelPointer.x.toDouble(), test.industrialFuelPointer.y.toDouble(), MouseButtonInfo(InputConstants.MOUSE_BUTTON_MIDDLE, 0))
                check(minecraft.options.keyPickItem.matchesMouse(press)) { "The native middle button must retain the fixture's pick-item binding." }
                check(screen.mouseClicked(press, false)) { "The synchronized Slot must consume a creative pick press." }
                val release =
                    if (drag) {
                        check(screen.mouseDragged(press, 0.0, 0.0)) { "The first creative drag slot must consume input." }
                        val next = MouseButtonEvent(test.industrialChargePointer.x.toDouble(), test.industrialChargePointer.y.toDouble(), press.buttonInfo())
                        check(screen.mouseDragged(next, (test.industrialChargePointer.x - test.industrialFuelPointer.x).toDouble(), 0.0)) { "The second creative drag slot must consume input." }
                        next
                    } else {
                        press
                    }
                check(screen.mouseReleased(release)) { "The synchronized Slot must consume the pick release." }
            },
        )
    }

    /**
     * Native menu bindings whose conflicts must retain vanilla pick-item precedence.
     */
    private enum class PickBindingConflict {
        Hotbar,
        Offhand,
        ;

        /**
         * Borrows the selected binding only while the caller runs on the client thread.
         */
        fun binding(
            minecraft: Minecraft,
        ): KeyMapping =
            when (this) {
                Hotbar -> minecraft.options.keyHotbarSlots[1]
                Offhand -> minecraft.options.keySwapOffhand
            }
    }

    /**
     * Retains reference copies of the two bound slots and cursor for comparison only on the server thread.
     */
    private data class NativeInventoryOutcome(
        val firstSlot: ItemStack,
        val secondSlot: ItemStack,
        val carried: ItemStack,
        val firstSlotIndex: Int,
        val secondSlotIndex: Int,
    ) {
        /**
         * Compares the server-owned menu with the native reference result, including item components and counts.
         */
        fun matches(menu: AbstractContainerMenu): Boolean =
            ItemStack.matches(firstSlot, menu.getSlot(firstSlotIndex).item) &&
                ItemStack.matches(secondSlot, menu.getSlot(secondSlotIndex).item) &&
                ItemStack.matches(carried, menu.carried)
    }
}
