package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftInventoryAction.CLONE
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftInventoryAction.PICKUP
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftInventoryAction.PICKUP_ALL
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftInventoryAction.QUICK_CRAFT
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftInventoryAction.QUICK_MOVE
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftInventoryAction.SWAP
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftInventoryAction.THROW
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.Font
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack

/**
 * Encodes the selected native input and rendering API without owning client or inventory state.
 */
internal object FabricMinecraftInventoryNative {
    /**
     * Maps native primary and secondary buttons to stable menu protocol identities.
     */
    fun containerButton(event: FabricMinecraftNativeMouseInput): Int =
        when (event.button()) {
            InputConstants.MOUSE_BUTTON_LEFT -> 0
            InputConstants.MOUSE_BUTTON_RIGHT -> 1
            else -> event.button()
        }

    /**
     * Reads Shift from the immutable native event.
     */
    fun shiftDown(event: FabricMinecraftNativeMouseInput): Boolean = event.hasShiftDown()

    /**
     * Matches a configured key binding against a native mouse event.
     */
    fun matchesMouse(
        mapping: KeyMapping,
        event: FabricMinecraftNativeMouseInput,
    ): Boolean = mapping.matchesMouse(event)

    /**
     * Reads the selected version's authoritative creative inventory capability.
     */
    fun hasInfiniteMaterials(player: LocalPlayer): Boolean = player.hasInfiniteMaterials()

    /**
     * Submits a typed menu action through the native game mode.
     */
    fun click(
        gameMode: MultiPlayerGameMode,
        menuId: Int,
        slot: Int,
        button: Int,
        action: FabricMinecraftInventoryAction,
        player: LocalPlayer,
    ) {
        val nativeAction =
            when (action) {
                PICKUP -> ContainerInput.PICKUP
                QUICK_MOVE -> ContainerInput.QUICK_MOVE
                SWAP -> ContainerInput.SWAP
                CLONE -> ContainerInput.CLONE
                THROW -> ContainerInput.THROW
                QUICK_CRAFT -> ContainerInput.QUICK_CRAFT
                PICKUP_ALL -> ContainerInput.PICKUP_ALL
            }
        gameMode.handleContainerInput(menuId, slot, button, nativeAction, player)
    }

    /**
     * Draws a copied slot payload and its native item decorations in submission order.
     */
    fun renderItem(
        graphics: FabricMinecraftInventoryGraphics,
        font: Font,
        stack: ItemStack,
        x: Int,
        y: Int,
        seed: Int,
    ) {
        graphics.item(stack, x, y, seed)
        graphics.itemDecorations(font, stack, x, y)
    }

    /**
     * Places the carried item above ordinary slot content using the selected native layer API.
     */
    fun renderCarried(
        graphics: FabricMinecraftInventoryGraphics,
        font: Font,
        stack: ItemStack,
        x: Int,
        y: Int,
    ) {
        graphics.nextStratum()
        graphics.item(stack, x, y)
        graphics.itemDecorations(font, stack, x, y)
    }
}
