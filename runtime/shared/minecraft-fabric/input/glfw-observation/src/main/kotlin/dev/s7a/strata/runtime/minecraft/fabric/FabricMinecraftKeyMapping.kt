package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardModifiers
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW

/**
 * Maps the GLFW record into a detached common key event on the client thread.
 */
@JvmSynthetic
internal inline fun <T> mapMinecraftKey(
    event: KeyEvent,
    create: (KeyCode, Int, KeyboardModifiers) -> T,
): T? {
    val keyValue = event.key()
    if (keyValue < GLFW.GLFW_KEY_UNKNOWN) return null
    val flags = event.modifiers()
    val modifiers =
        KeyboardModifiers(
            shift = flags and GLFW.GLFW_MOD_SHIFT != 0,
            control = flags and GLFW.GLFW_MOD_CONTROL != 0,
            alt = flags and GLFW.GLFW_MOD_ALT != 0,
            superKey = flags and GLFW.GLFW_MOD_SUPER != 0,
            capsLock = flags and GLFW.GLFW_MOD_CAPS_LOCK != 0,
            numLock = flags and GLFW.GLFW_MOD_NUM_LOCK != 0,
        )
    return create(KeyCode(keyValue), event.scancode(), modifiers)
}
