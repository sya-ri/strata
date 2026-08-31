@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW

/**
 * Clicks and releases one screen position through the primitive input API used through Minecraft 1.21.8.
 *
 * @param screen borrowed active test screen.
 * @param position logical primary-button position.
 * @throws IllegalStateException when the Strata press is not consumed.
 */
internal fun clickMinecraftScreen(
    screen: Screen,
    position: IntOffset,
) {
    check(pressMinecraftScreen(screen, position)) {
        "The Strata element must consume its primary press."
    }
    releaseMinecraftScreen(screen, position)
}

/**
 * Delivers a primary press at an unclamped logical [position] through the native primitive callback.
 *
 * The client thread borrows [screen] for this invocation and receives its consumption result or unchanged callback failure.
 */
internal fun pressMinecraftScreen(
    screen: Screen,
    position: IntOffset,
): Boolean = screen.mouseClicked(position.x.toDouble(), position.y.toDouble(), PRIMARY_MOUSE_BUTTON)

/**
 * Delivers a primary drag with logical [position] and [delta] through the native primitive callback.
 *
 * The client thread borrows [screen] without changing coordinates and receives its consumption result or unchanged callback failure.
 */
internal fun dragMinecraftScreen(
    screen: Screen,
    position: IntOffset,
    delta: IntOffset,
): Boolean = screen.mouseDragged(position.x.toDouble(), position.y.toDouble(), PRIMARY_MOUSE_BUTTON, delta.x.toDouble(), delta.y.toDouble())

/**
 * Delivers a primary release at an unclamped logical [position] through the native primitive callback.
 *
 * The client thread borrows [screen] for this invocation and receives its consumption result or unchanged callback failure.
 */
internal fun releaseMinecraftScreen(
    screen: Screen,
    position: IntOffset,
): Boolean = screen.mouseReleased(position.x.toDouble(), position.y.toDouble(), PRIMARY_MOUSE_BUTTON)

/**
 * Delivers one native Tab press through the primitive screen callback.
 *
 * @param screen borrowed active test screen on the client thread.
 * @param reverse whether Shift is held for reverse traversal.
 * @return the exact native screen consumption result.
 * @throws Throwable when native or Strata input dispatch fails.
 */
internal fun pressMinecraftTab(
    screen: Screen,
    reverse: Boolean = false,
): Boolean = screen.keyPressed(GLFW.GLFW_KEY_TAB, NO_SCAN_CODE, if (reverse) GLFW.GLFW_MOD_SHIFT else NO_MODIFIERS)

/**
 * Delivers one native Enter press through the primitive screen callback.
 *
 * @param screen borrowed active test screen on the client thread.
 * @return the exact native screen consumption result.
 * @throws Throwable when native or Strata input dispatch fails.
 */
internal fun pressMinecraftEnter(screen: Screen): Boolean = screen.keyPressed(GLFW.GLFW_KEY_ENTER, NO_SCAN_CODE, NO_MODIFIERS)

/**
 * Delivers one native Space press through the primitive screen callback.
 *
 * @param screen borrowed active test screen on the client thread.
 * @return the exact native screen consumption result.
 * @throws Throwable when native or Strata input dispatch fails.
 */
internal fun pressMinecraftSpace(screen: Screen): Boolean = screen.keyPressed(GLFW.GLFW_KEY_SPACE, NO_SCAN_CODE, NO_MODIFIERS)

/**
 * Updates native window focus through the real callback using the pre-record-input window-handle API.
 *
 * The loaded test calls this on the client thread and restores the previous focus state; input-reset failures propagate unchanged.
 * Fabric's harness cancellation is bypassed only for this explicit invocation and remains enabled for ordinary callbacks.
 */
internal fun focusMinecraftWindow(focused: Boolean) {
    val window = Minecraft.getInstance().window
    MinecraftCanvasWindowTestScope.invoke(window, window.window, focused)
}

private const val PRIMARY_MOUSE_BUTTON = 0
private const val NO_SCAN_CODE = 0
private const val NO_MODIFIERS = 0
