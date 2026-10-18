package dev.s7a.strata.runtime.minecraft.fabric

import org.lwjgl.glfw.GLFW

/**
 * Detached GLFW mouse button and modifier bits captured for one legacy native input transaction.
 */
internal data class FabricMinecraftNativeMouseInput(
    val button: Int,
    val modifiers: Int,
) {
    /**
     * Reports Shift captured with the original input.
     */
    fun hasShiftDown(): Boolean = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
}
