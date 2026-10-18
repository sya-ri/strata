package dev.s7a.strata.runtime.minecraft.fabric

import org.lwjgl.glfw.GLFW
import java.util.function.BooleanSupplier

/**
 * Captures legacy native press metadata before dispatching to the shared inventory owner.
 */
internal fun FabricMinecraftInventoryBridge.withMousePress(
    button: Int,
    modifiers: Int,
    doubleClick: Boolean,
    operation: BooleanSupplier,
): Boolean = withMousePress(FabricMinecraftNativeMouseInput(button, modifiers), doubleClick, operation)

/**
 * Captures legacy native drag metadata before dispatching to the shared inventory owner.
 */
internal fun FabricMinecraftInventoryBridge.withMouseDrag(
    button: Int,
    modifiers: Int,
    operation: BooleanSupplier,
): Boolean = withMouseDrag(FabricMinecraftNativeMouseInput(button, modifiers), operation)

/**
 * Captures legacy native release metadata before dispatching to the shared inventory owner.
 */
internal fun FabricMinecraftInventoryBridge.withMouseRelease(
    button: Int,
    modifiers: Int,
    operation: BooleanSupplier,
): Boolean = withMouseRelease(FabricMinecraftNativeMouseInput(button, modifiers), operation)

/**
 * Borrows legacy native key matching for one synchronous authoritative inventory action.
 */
internal fun FabricMinecraftInventoryBridge.handleKeyPressed(
    key: Int,
    scanCode: Int,
    modifiers: Int,
): Boolean = handleKeyPressed((modifiers and GLFW.GLFW_MOD_CONTROL) != 0) { mapping -> FabricMinecraftKeyBindingBridge.matches(mapping, key, scanCode, modifiers) }
