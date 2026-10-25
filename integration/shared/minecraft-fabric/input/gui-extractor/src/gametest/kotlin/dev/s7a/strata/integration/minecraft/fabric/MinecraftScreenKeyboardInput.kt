package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.input.KeyCode
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent

/**
 * Delivers a left, right, space or enter navigation key through this family's native callback without modifiers.
 * Native constants preserve physical key identity across GLFW and SDL instead of forwarding the common key value.
 * The client thread borrows [screen]; native consumption and callback failures are returned unchanged.
 *
 * @throws IllegalArgumentException when [key] is outside the supported navigation set.
 */
internal fun pressMinecraftKey(
    screen: Screen,
    key: KeyCode,
): Boolean {
    val nativeKey =
        when (key) {
            KeyCode.Left -> InputConstants.KEY_LEFT
            KeyCode.Right -> InputConstants.KEY_RIGHT
            KeyCode.Space -> InputConstants.KEY_SPACE
            KeyCode.Enter -> InputConstants.KEY_RETURN
            else -> throw IllegalArgumentException("Unsupported native navigation key: $key")
        }
    return screen.keyPressed(KeyEvent(nativeKey, NO_SCAN_CODE, NO_MODIFIERS))
}

/**
 * Delivers one native Tab press through the unobfuscated record-based screen callback.
 *
 * @param screen borrowed active test screen on the client thread.
 * @param reverse whether Shift is held for reverse traversal.
 * @return the exact native screen consumption result.
 * @throws Throwable when native or Strata input dispatch fails.
 */
internal fun pressMinecraftTab(
    screen: Screen,
    reverse: Boolean = false,
): Boolean = screen.keyPressed(KeyEvent(InputConstants.KEY_TAB, NO_SCAN_CODE, if (reverse) InputConstants.MOD_SHIFT else NO_MODIFIERS))

/**
 * Delivers one native Enter press through the unobfuscated record-based screen callback.
 *
 * @param screen borrowed active test screen on the client thread.
 * @return the exact native screen consumption result.
 * @throws Throwable when native or Strata input dispatch fails.
 */
internal fun pressMinecraftEnter(screen: Screen): Boolean = screen.keyPressed(KeyEvent(InputConstants.KEY_RETURN, NO_SCAN_CODE, NO_MODIFIERS))

/**
 * Releases the remapped Enter binding through the native screen callback.
 */
internal fun releaseMinecraftEnter(screen: Screen): Boolean = screen.keyReleased(KeyEvent(InputConstants.KEY_RETURN, NO_SCAN_CODE, NO_MODIFIERS))

/**
 * Delivers one native Space press through the unobfuscated record-based screen callback.
 *
 * @param screen borrowed active test screen on the client thread.
 * @return the exact native screen consumption result.
 * @throws Throwable when native or Strata input dispatch fails.
 */
internal fun pressMinecraftSpace(screen: Screen): Boolean = screen.keyPressed(KeyEvent(InputConstants.KEY_SPACE, NO_SCAN_CODE, NO_MODIFIERS))

private const val NO_SCAN_CODE = 0
private const val NO_MODIFIERS = 0
