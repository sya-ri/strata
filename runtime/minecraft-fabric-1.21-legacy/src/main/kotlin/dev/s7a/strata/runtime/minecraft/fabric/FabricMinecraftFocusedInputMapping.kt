package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.TextInputEvent
import org.lwjgl.glfw.GLFW

/**
 * Maps one Minecraft key press into the platform-neutral typed event without retaining the native record.
 *
 * @param keyValue native GLFW key value.
 * @param scanCode native platform scan code.
 * @param modifierFlags native GLFW modifier bit field.
 * @return detached common event, or null for a key value below Minecraft's unknown sentinel.
 */
@JvmSynthetic
internal fun mapMinecraftKeyPress(
    keyValue: Int,
    scanCode: Int,
    modifierFlags: Int,
): KeyboardEvent.Press? =
    mapMinecraftKey(keyValue, scanCode, modifierFlags) { key, mappedScanCode, modifiers ->
        KeyboardEvent.Press(key, mappedScanCode, modifiers)
    }

/**
 * Maps one Minecraft key release into the platform-neutral typed event without retaining the native record.
 *
 * @param keyValue native GLFW key value.
 * @param scanCode native platform scan code.
 * @param modifierFlags native GLFW modifier bit field.
 * @return detached common event, or null for a key value below Minecraft's unknown sentinel.
 */
@JvmSynthetic
internal fun mapMinecraftKeyRelease(
    keyValue: Int,
    scanCode: Int,
    modifierFlags: Int,
): KeyboardEvent.Release? =
    mapMinecraftKey(keyValue, scanCode, modifierFlags) { key, mappedScanCode, modifiers ->
        KeyboardEvent.Release(key, mappedScanCode, modifiers)
    }

/**
 * Maps one committed Minecraft Unicode character into the platform-neutral typed event.
 *
 * Supported legacy Minecraft releases expose committed character input but have no screen preedit callback, so input-method composition remains native until commitment.
 *
 * @param codePoint committed Unicode code point.
 * @return detached common event, or null for an invalid Unicode scalar value.
 */
@JvmSynthetic
internal fun mapMinecraftCharacter(codePoint: Int): TextInputEvent.Character? {
    if (Character.isValidCodePoint(codePoint).not() || codePoint in 0xD800..0xDFFF) return null
    return TextInputEvent.Character(codePoint)
}

private inline fun <T> mapMinecraftKey(
    keyValue: Int,
    scanCode: Int,
    modifierFlags: Int,
    create: (KeyCode, Int, KeyboardModifiers) -> T,
): T? {
    if (keyValue < GLFW.GLFW_KEY_UNKNOWN) return null
    val modifiers =
        KeyboardModifiers(
            shift = modifierFlags and GLFW.GLFW_MOD_SHIFT != 0,
            control = modifierFlags and GLFW.GLFW_MOD_CONTROL != 0,
            alt = modifierFlags and GLFW.GLFW_MOD_ALT != 0,
            superKey = modifierFlags and GLFW.GLFW_MOD_SUPER != 0,
            capsLock = modifierFlags and GLFW.GLFW_MOD_CAPS_LOCK != 0,
            numLock = modifierFlags and GLFW.GLFW_MOD_NUM_LOCK != 0,
        )
    return create(KeyCode(keyValue), scanCode, modifiers)
}
