package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.Minecraft

/**
 * Delivers physical coordinates and their native deltas through the public mouse callback on the client thread.
 */
internal fun moveStandaloneMinecraftPointer(
    minecraft: Minecraft,
    x: Double,
    y: Double,
) {
    val pointer = minecraft.mouseHandler
    pointer.onMove(minecraft.window.handle(), x, y, x - pointer.xpos(), y - pointer.ypos())
}
