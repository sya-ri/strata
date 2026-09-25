package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.TextInputEvent
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.PreeditEvent

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
