package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.TextInputEvent
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.PreeditEvent
import org.lwjgl.glfw.GLFW

/**
 * Maps one Minecraft key press into the platform-neutral typed event without retaining the native record.
 *
 * @param event native immutable key record.
 * @return detached common event, or null for a key value below Minecraft's unknown sentinel.
 */
@JvmSynthetic
internal fun mapMinecraftKeyPress(event: KeyEvent): KeyboardEvent.Press? = mapMinecraftKey(event) { key, scanCode, modifiers -> KeyboardEvent.Press(key, scanCode, modifiers) }

/**
 * Maps one Minecraft key release into the platform-neutral typed event without retaining the native record.
 *
 * @param event native immutable key record.
 * @return detached common event, or null for a key value below Minecraft's unknown sentinel.
 */
@JvmSynthetic
internal fun mapMinecraftKeyRelease(event: KeyEvent): KeyboardEvent.Release? = mapMinecraftKey(event) { key, scanCode, modifiers -> KeyboardEvent.Release(key, scanCode, modifiers) }

/**
 * Maps one committed Minecraft Unicode character into the platform-neutral typed event.
 *
 * @param event native immutable character record.
 * @return detached common event, or null for an invalid Unicode scalar value.
 */
@JvmSynthetic
internal fun mapMinecraftCharacter(event: CharacterEvent): TextInputEvent.Character? {
    val codePoint = event.codepoint()
    if (Character.isValidCodePoint(codePoint).not() || codePoint in 0xD800..0xDFFF) return null
    return TextInputEvent.Character(codePoint)
}

/**
 * Maps one Minecraft input-method preedit snapshot into detached platform-neutral values.
 *
 * @param event native immutable preedit record, or null when composition ends.
 * @return detached common event, or null when native caret or focused-block indices are inconsistent.
 */
@JvmSynthetic
internal fun mapMinecraftPreedit(event: PreeditEvent?): TextInputEvent.Preedit? {
    if (event == null) return TextInputEvent.Preedit("", 0, emptyList(), -1)
    val text = event.fullText()
    val caret = event.caretPosition()
    val blocks = event.blocks().toList()
    val focusedBlock = event.focusedBlock()
    if ((caret in 0..text.length).not()) return null
    if ((focusedBlock == -1 || focusedBlock in blocks.indices).not()) return null
    return TextInputEvent.Preedit(text, caret, blocks, focusedBlock)
}

private inline fun <T> mapMinecraftKey(
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
