package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import net.minecraft.client.gui.screens.Screen

/**
 * Delivers one native vertical wheel event through the three-double API used by Minecraft 1.20 and 1.20.1.
 *
 * The caller borrows an active client-thread screen; this call neither schedules a frame nor retains the screen.
 *
 * @param screen active native screen.
 * @param position logical pointer position.
 * @return whether the native callback consumes the event.
 * @throws Throwable when the native or common input callback fails.
 */
internal fun scrollMinecraftScreen(
    screen: Screen,
    position: IntOffset,
): Boolean = screen.mouseScrolled(position.x.toDouble(), position.y.toDouble(), -1.0)
