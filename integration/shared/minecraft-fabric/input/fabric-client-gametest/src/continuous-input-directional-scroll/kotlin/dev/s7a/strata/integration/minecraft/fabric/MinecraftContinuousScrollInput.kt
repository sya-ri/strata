package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import net.minecraft.client.gui.screens.Screen

/**
 * Delivers one native vertical wheel event through the four-double API introduced in Minecraft 1.20.2.
 *
 * The caller borrows an active client-thread screen; this call neither schedules a frame nor retains the screen.
 *
 * @param screen active native screen.
 * @param position logical pointer position.
 * @param verticalDelta exact signed vertical wheel delta, including zero for ignored-input checks.
 * @return whether the native callback consumes the event.
 * @throws Throwable when the native or common input callback fails.
 */
internal fun scrollMinecraftScreen(
    screen: Screen,
    position: IntOffset,
    verticalDelta: Double = -1.0,
): Boolean = screen.mouseScrolled(position.x.toDouble(), position.y.toDouble(), 0.0, verticalDelta)
