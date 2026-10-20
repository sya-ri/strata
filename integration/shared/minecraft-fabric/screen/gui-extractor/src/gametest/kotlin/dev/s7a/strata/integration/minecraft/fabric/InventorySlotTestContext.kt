package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate

/**
 * Borrows one loaded client and integrated-server player for a single inventory verification invocation.
 * Call methods from the Fabric coordinator; client and server work stays on its existing owner thread.
 * The caller retains the world lifetime and is responsible for restoring scenario state.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class InventorySlotTestContext(
    internal val client: ClientGameTestContext,
) {
    /**
     * Borrowed integrated server; access native state only inside a scheduled server callback.
     */
    internal val server: MinecraftServer =
        client.computeOnClient(
            FailableFunction<Minecraft, MinecraftServer, RuntimeException> { minecraft -> checkNotNull(minecraft.singleplayerServer) },
        )
    private val playerId =
        client.computeOnClient(
            FailableFunction<Minecraft, UUID, RuntimeException> { minecraft -> checkNotNull(minecraft.player).uuid },
        )

    /**
     * Waits until the client inventory reflects the requested item in one native slot.
     */
    internal fun waitForPlayerItem(
        index: Int,
        item: Item,
    ) {
        client.waitFor(
            Predicate<Minecraft> { minecraft ->
                val player = minecraft.player ?: return@Predicate false
                player.inventory.getItem(index).`is`(item)
            },
        )
    }

    /**
     * Sends a matched primary press and release to the currently active synchronized screen.
     */
    internal fun clickSlot(
        pointer: IntOffset,
    ) {
        client.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val screen = MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen ?: error("The synchronized Slot screen is not active.")
                val event = mouseEvent(pointer, InputConstants.MOUSE_BUTTON_LEFT)
                check(screen.mouseClicked(event, false)) { "The synchronized Slot must consume a primary press." }
                check(screen.mouseReleased(event)) { "The synchronized Slot must consume the matching release." }
            },
        )
    }

    /**
     * Runs one player operation on the integrated server and rethrows its failure on the coordinator.
     */
    internal fun onServer(
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
        client.waitFor(Predicate<Minecraft> { completed.get() })
        failure.get()?.let { throwable -> throw throwable }
    }

    /**
     * Waits for a server-owned condition with at most one queued check and preserves callback failures.
     */
    internal fun waitForServer(
        condition: (ServerPlayer) -> Boolean,
    ) {
        val matched = AtomicBoolean()
        val pending = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        client.waitFor(
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

    /**
     * Closes the active Strata screen on the client and waits for its removal.
     */
    internal fun closeScreen() {
        client.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val current = MinecraftClientScreenAccess.currentScreen(minecraft)
                if (current is FabricMinecraftScreen) current.onClose()
            },
        )
        client.waitFor(Predicate<Minecraft> { minecraft -> (MinecraftClientScreenAccess.currentScreen(minecraft) is FabricMinecraftScreen).not() })
    }

    /**
     * Creates the existing unmodified native mouse event for a fixture position and button.
     */
    internal fun mouseEvent(
        pointer: IntOffset,
        button: Int,
    ): MouseButtonEvent = MouseButtonEvent(pointer.x.toDouble(), pointer.y.toDouble(), MouseButtonInfo(button, 0))

    /**
     * Shares the fixed slot indices and client coordinates used by synchronization and native gesture scenarios.
     */
    internal companion object {
        internal const val FUEL_SLOT_INDEX: Int = 0
        internal const val CHARGE_SLOT_INDEX: Int = 1
        internal const val CONTAINER_SIZE: Int = 27
        internal const val ITEM_COUNT: Int = 7
        internal val industrialFuelPointer: IntOffset = IntOffset(90, 49)
        internal val industrialChargePointer: IntOffset = IntOffset(223, 49)

        /**
         * Matches the fixture's item and count without replacing component-sensitive native oracle comparisons.
         */
        internal fun stackMatches(
            stack: ItemStack,
            item: Item,
        ): Boolean = stack.`is`(item) && stack.count == ITEM_COUNT
    }
}
