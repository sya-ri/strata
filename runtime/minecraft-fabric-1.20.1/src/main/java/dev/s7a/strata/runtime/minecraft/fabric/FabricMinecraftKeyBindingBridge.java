package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.KeyMapping;

/**
 * Adapts detached native input values to the primitive key-binding API used by Minecraft 1.20 and 1.20.1.
 */
final class FabricMinecraftKeyBindingBridge {
    private FabricMinecraftKeyBindingBridge() {
    }

    /**
     * Matches one key press against a native Minecraft binding.
     *
     * @param binding borrowed client option binding.
     * @param key native GLFW key value.
     * @param scanCode native platform scan code.
     * @param modifiers native GLFW modifier bit field, retained for cross-version call symmetry.
     * @return whether the binding accepts this key input.
     */
    static boolean matches(KeyMapping binding, int key, int scanCode, int modifiers) {
        return binding.matches(key, scanCode);
    }

    /**
     * Matches one mouse press against a native Minecraft binding.
     *
     * @param binding borrowed client option binding.
     * @param button native GLFW mouse button value.
     * @param modifiers native GLFW modifier bit field, retained for cross-version call symmetry.
     * @return whether the binding accepts this mouse input.
     */
    static boolean matchesMouse(KeyMapping binding, int button, int modifiers) {
        return binding.matchesMouse(button);
    }
}
