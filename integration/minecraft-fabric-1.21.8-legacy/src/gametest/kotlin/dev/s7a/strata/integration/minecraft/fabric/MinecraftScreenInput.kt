package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import net.minecraft.client.gui.screens.Screen

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
    check(screen.mouseClicked(position.x.toDouble(), position.y.toDouble(), PRIMARY_MOUSE_BUTTON)) {
        "The Strata element must consume its primary press."
    }
    screen.mouseReleased(position.x.toDouble(), position.y.toDouble(), PRIMARY_MOUSE_BUTTON)
}

private const val PRIMARY_MOUSE_BUTTON = 0
