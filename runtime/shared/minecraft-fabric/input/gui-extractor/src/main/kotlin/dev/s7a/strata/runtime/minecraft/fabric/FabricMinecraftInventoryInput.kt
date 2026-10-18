package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.input.KeyEvent

/**
 * Borrows the original native key event without reconstructing backend-specific scan or key-code fields.
 */
internal fun FabricMinecraftInventoryBridge.handleKeyPressed(event: KeyEvent): Boolean = handleKeyPressed(event.hasControlDown()) { mapping -> mapping.matches(event) }
