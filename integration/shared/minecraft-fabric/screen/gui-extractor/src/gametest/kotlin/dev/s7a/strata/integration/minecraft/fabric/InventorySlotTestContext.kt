package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Borrows one loaded client and integrated-server player for a single inventory verification invocation.
 * Call methods from the Fabric coordinator; client and server work stays on its existing owner thread.
 * Fixed native fixture inputs belong to this invocation.
 * The caller retains the world lifetime and is responsible for restoring scenario state.
 */
internal class InventorySlotTestContext(
    internal val client: MinecraftLoadedTestContext,
) {
    /**
     * Borrowed integrated server; access native state only inside a scheduled server callback.
     */
    internal val server: MinecraftServer =
        client.computeOnClient { minecraft -> checkNotNull(minecraft.singleplayerServer) }
    private val playerId =
        client.computeOnClient { minecraft -> checkNotNull(minecraft.player).uuid }

    /**
     * Waits until the client inventory reflects the requested item in one native slot.
     */
    internal fun waitForPlayerItem(
        index: Int,
        item: Item,
    ) {
        client.waitFor { minecraft ->
            val player = minecraft.player ?: return@waitFor false
            player.inventory.getItem(index).`is`(item)
        }
    }

    /**
     * Sends a matched primary press and release to the currently active synchronized screen.
     */
    internal fun clickSlot(
        pointer: IntOffset,
    ) {
        client.runOnClient { minecraft ->
            val screen = MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen ?: error("The synchronized Slot screen is not active.")
            val event = mouseEvent(pointer, InputConstants.MOUSE_BUTTON_LEFT)
            check(screen.mouseClicked(event, false)) { "The synchronized Slot must consume a primary press." }
            check(screen.mouseReleased(event)) { "The synchronized Slot must consume the matching release." }
        }
    }

    /**
     * Runs one player operation on the integrated server and rethrows its failure on the coordinator.
     */
    internal fun onServer(
        operation: (ServerPlayer) -> Unit,
    ) {
        val completed = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        server.execute {
            runCatching { operation(checkNotNull(server.playerList.getPlayer(playerId))) }
                .exceptionOrNull()
                ?.let(failure::set)
            completed.set(true)
        }
        client.waitFor { completed.get() }
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
        client.waitFor {
            failure.get()?.let { throwable -> throw throwable }
            if (matched.get()) {
                true
            } else {
                if (pending.compareAndSet(false, true)) {
                    server.execute {
                        runCatching { matched.set(condition(checkNotNull(server.playerList.getPlayer(playerId)))) }
                            .exceptionOrNull()
                            ?.let(failure::set)
                        pending.set(false)
                    }
                }
                false
            }
        }
        failure.get()?.let { throwable -> throw throwable }
    }

    /**
     * Closes the active Strata screen on the client and waits for its removal.
     */
    internal fun closeScreen() {
        client.runOnClient { minecraft ->
            val current = MinecraftClientScreenAccess.currentScreen(minecraft)
            if (current is FabricMinecraftScreen) current.onClose()
        }
        client.waitFor { minecraft -> (MinecraftClientScreenAccess.currentScreen(minecraft) is FabricMinecraftScreen).not() }
    }

    /**
     * Creates the existing unmodified native mouse event for a fixture position and button.
     */
    internal fun mouseEvent(
        pointer: IntOffset,
        button: Int,
    ): MouseButtonEvent = MouseButtonEvent(pointer.x.toDouble(), pointer.y.toDouble(), MouseButtonInfo(button, 0))

    internal val playerInventoryIndex: Int = 0
    internal val containerSlotIndex: Int = 0
    internal val fuelSlotIndex: Int = 0
    internal val chargeSlotIndex: Int = 1
    internal val containerSize: Int = 27
    internal val itemCount: Int = 7
    internal val industrialFuelPointer: IntOffset = IntOffset(90, 49)
    internal val industrialChargePointer: IntOffset = IntOffset(223, 49)

    /**
     * Matches this invocation's seeded item count without replacing component-sensitive native oracle comparisons.
     */
    internal fun stackMatches(
        stack: ItemStack,
        item: Item,
    ): Boolean = stack.`is`(item) && stack.count == itemCount
}
