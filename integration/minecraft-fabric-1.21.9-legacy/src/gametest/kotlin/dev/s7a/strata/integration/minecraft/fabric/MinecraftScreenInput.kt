package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
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
    val event =
        MouseButtonEvent(
            position.x.toDouble(),
            position.y.toDouble(),
            MouseButtonInfo(PRIMARY_MOUSE_BUTTON, NO_MODIFIERS),
        )
    check(screen.mouseClicked(event, false)) { "The Strata element must consume its primary press." }
    screen.mouseReleased(event)
}

private const val PRIMARY_MOUSE_BUTTON = 0
private const val NO_MODIFIERS = 0
