package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Adapts detached native input values to the record-based key-binding API introduced in Minecraft 1.21.9.
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
     * @param modifiers native GLFW modifier bit field.
     * @return whether the binding accepts this key input.
     */
    static boolean matches(KeyMapping binding, int key, int scanCode, int modifiers) {
        return binding.matches(new KeyEvent(key, scanCode, modifiers));
    }

    /**
     * Matches one mouse press against a native Minecraft binding.
     *
     * @param binding borrowed client option binding.
     * @param button native GLFW mouse button value.
     * @param modifiers native GLFW modifier bit field.
     * @return whether the binding accepts this mouse input.
     */
    static boolean matchesMouse(KeyMapping binding, int button, int modifiers) {
        return binding.matchesMouse(new MouseButtonEvent(0.0, 0.0, new MouseButtonInfo(button, modifiers)));
    }
}
