package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardModifiers
import net.minecraft.client.input.KeyEvent
import org.lwjgl.sdl.SDLScancode

/**
 * Preserves the SDL physical scan code while converting platform flags and shared key identities.
 */
@JvmSynthetic
internal inline fun <T> mapMinecraftKey(
    event: KeyEvent,
    create: (KeyCode, Int, KeyboardModifiers) -> T,
): T? {
    val scanCode = event.key()
    if (scanCode < 0) return null
    val flags = event.modifiers()
    val modifiers =
        KeyboardModifiers(
            shift = flags and InputConstants.MOD_SHIFT != 0,
            control = flags and InputConstants.MOD_CONTROL != 0,
            alt = flags and InputConstants.MOD_ALT != 0,
            superKey = flags and InputConstants.MOD_SUPER != 0,
            capsLock = flags and InputConstants.MOD_CAPS_LOCK != 0,
            numLock = flags and InputConstants.MOD_NUM_LOCK != 0,
        )
    return create(mapMinecraftPhysicalKey(scanCode), scanCode, modifiers)
}

/**
 * Maps supported physical keys to the existing key namespace; unknown SDL codes cannot alias editing keys.
 * A flat translation keeps each supported identity explicit without intermediate state.
 */
@Suppress("CyclomaticComplexMethod")
@JvmSynthetic
internal fun mapMinecraftPhysicalKey(scanCode: Int): KeyCode =
    when (scanCode) {
        InputConstants.KEY_SPACE -> KeyCode.Space
        InputConstants.KEY_ESCAPE -> KeyCode.Escape
        InputConstants.KEY_RETURN -> KeyCode.Enter
        InputConstants.KEY_TAB -> KeyCode.Tab
        InputConstants.KEY_BACKSPACE -> KeyCode.Backspace
        InputConstants.KEY_INSERT -> KeyCode.Insert
        InputConstants.KEY_DELETE -> KeyCode.Delete
        InputConstants.KEY_RIGHT -> KeyCode.Right
        InputConstants.KEY_LEFT -> KeyCode.Left
        InputConstants.KEY_DOWN -> KeyCode.Down
        InputConstants.KEY_UP -> KeyCode.Up
        InputConstants.KEY_PAGEUP -> KeyCode.PageUp
        InputConstants.KEY_PAGEDOWN -> KeyCode.PageDown
        InputConstants.KEY_HOME -> KeyCode.Home
        InputConstants.KEY_END -> KeyCode.End
        in InputConstants.KEY_A..InputConstants.KEY_Z -> KeyCode('A'.code + scanCode - InputConstants.KEY_A)
        in InputConstants.KEY_1..InputConstants.KEY_9 -> KeyCode('1'.code + scanCode - InputConstants.KEY_1)
        InputConstants.KEY_0 -> KeyCode('0'.code)
        InputConstants.KEY_APOSTROPHE -> KeyCode('\''.code)
        InputConstants.KEY_COMMA -> KeyCode(','.code)
        InputConstants.KEY_MINUS -> KeyCode('-'.code)
        InputConstants.KEY_PERIOD -> KeyCode('.'.code)
        InputConstants.KEY_SLASH -> KeyCode('/'.code)
        InputConstants.KEY_SEMICOLON -> KeyCode(';'.code)
        InputConstants.KEY_EQUALS -> KeyCode('='.code)
        InputConstants.KEY_LBRACKET -> KeyCode('['.code)
        InputConstants.KEY_BACKSLASH -> KeyCode('\\'.code)
        InputConstants.KEY_RBRACKET -> KeyCode(']'.code)
        InputConstants.KEY_GRAVE -> KeyCode('`'.code)
        in InputConstants.KEY_F1..InputConstants.KEY_F12 -> KeyCode(290 + scanCode - InputConstants.KEY_F1)
        in InputConstants.KEY_F13..InputConstants.KEY_F24 -> KeyCode(302 + scanCode - InputConstants.KEY_F13)
        InputConstants.KEY_CAPSLOCK -> KeyCode(280)
        InputConstants.KEY_SCROLLLOCK -> KeyCode(281)
        InputConstants.KEY_NUMLOCK -> KeyCode(282)
        InputConstants.KEY_PRINTSCREEN -> KeyCode(283)
        InputConstants.KEY_PAUSE -> KeyCode(284)
        InputConstants.KEY_NUMPAD0 -> KeyCode(320)
        in InputConstants.KEY_NUMPAD1..InputConstants.KEY_NUMPAD9 -> KeyCode(321 + scanCode - InputConstants.KEY_NUMPAD1)
        SDLScancode.SDL_SCANCODE_KP_PERIOD -> KeyCode(330)
        SDLScancode.SDL_SCANCODE_KP_DIVIDE -> KeyCode(331)
        InputConstants.KEY_MULTIPLY -> KeyCode(332)
        SDLScancode.SDL_SCANCODE_KP_MINUS -> KeyCode(333)
        InputConstants.KEY_ADD -> KeyCode(334)
        SDLScancode.SDL_SCANCODE_APPLICATION -> KeyCode(348)
        InputConstants.KEY_NUMPADENTER -> KeyCode(335)
        InputConstants.KEY_NUMPADEQUALS -> KeyCode(336)
        InputConstants.KEY_LSHIFT -> KeyCode(340)
        InputConstants.KEY_LCONTROL -> KeyCode(341)
        InputConstants.KEY_LALT -> KeyCode(342)
        InputConstants.KEY_LGUI -> KeyCode(343)
        InputConstants.KEY_RSHIFT -> KeyCode(344)
        InputConstants.KEY_RCONTROL -> KeyCode(345)
        InputConstants.KEY_RALT -> KeyCode(346)
        InputConstants.KEY_RGUI -> KeyCode(347)
        else -> KeyCode.Unknown
    }
