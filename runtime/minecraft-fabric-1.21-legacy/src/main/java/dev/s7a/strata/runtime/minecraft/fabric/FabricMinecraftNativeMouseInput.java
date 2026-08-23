package dev.s7a.strata.runtime.minecraft.fabric;

import org.lwjgl.glfw.GLFW;

/**
 * Detached mouse-button state shared across legacy Minecraft input API generations.
 *
 * @param button native GLFW mouse button value.
 * @param modifiers native GLFW modifier bit field captured with the button event.
 */
record FabricMinecraftNativeMouseInput(int button, int modifiers) {
    /**
     * Reports whether Shift was held for this input.
     *
     * @return true when either native Shift key contributed to the modifier bit field.
     */
    boolean hasShiftDown() {
        return (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
    }
}
