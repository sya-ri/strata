@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo

/**
 * Clicks and releases one screen position through the record-based input API introduced in Minecraft 1.21.9.
 *
 * @param screen borrowed active test screen.
 * @param position logical primary-button position.
 * @throws IllegalStateException when the Strata press is not consumed.
 */
internal fun clickMinecraftScreen(
    screen: Screen,
    position: IntOffset,
) {
    check(pressMinecraftScreen(screen, position)) { "The Strata element must consume its primary press." }
    releaseMinecraftScreen(screen, position)
}

/**
 * Delivers a primary press at an unclamped logical [position] through the native record callback.
 *
 * The client thread borrows [screen] for this invocation and receives its consumption result or unchanged callback failure.
 */
internal fun pressMinecraftScreen(
    screen: Screen,
    position: IntOffset,
): Boolean = screen.mouseClicked(position.mouseEvent(), false)

/**
 * Delivers a primary drag with logical [position] and [delta] through the native record callback.
 *
 * The client thread borrows [screen] without changing coordinates and receives its consumption result or unchanged callback failure.
 */
internal fun dragMinecraftScreen(
    screen: Screen,
    position: IntOffset,
    delta: IntOffset,
): Boolean = screen.mouseDragged(position.mouseEvent(), delta.x.toDouble(), delta.y.toDouble())

/**
 * Delivers a primary release at an unclamped logical [position] through the native record callback.
 *
 * The client thread borrows [screen] for this invocation and receives its consumption result or unchanged callback failure.
 */
internal fun releaseMinecraftScreen(
    screen: Screen,
    position: IntOffset,
): Boolean = screen.mouseReleased(position.mouseEvent())

/**
 * Updates native window focus through the real callback using the record-input family's window-handle API.
 *
 * The loaded test calls this on the client thread and restores the previous focus state; input-reset failures propagate unchanged.
 * Fabric's harness cancellation is bypassed only for this explicit invocation and remains enabled for ordinary callbacks.
 */
internal fun focusMinecraftWindow(focused: Boolean) {
    val window = Minecraft.getInstance().window
    MinecraftCanvasWindowTestScope.invoke(window, window.handle(), focused)
}

private fun IntOffset.mouseEvent(): MouseButtonEvent = MouseButtonEvent(x.toDouble(), y.toDouble(), MouseButtonInfo(PRIMARY_MOUSE_BUTTON, NO_MODIFIERS))

private const val PRIMARY_MOUSE_BUTTON = 0
private const val NO_MODIFIERS = 0
