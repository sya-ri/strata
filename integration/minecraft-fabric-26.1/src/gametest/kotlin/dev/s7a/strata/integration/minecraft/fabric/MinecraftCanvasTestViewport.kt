package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Applies a positive Canvas acceptance viewport through Fabric's native OpenGL window-resize boundary.
 *
 * The runner thread retains [context], Fabric owns native resize scheduling, and validation or resize failures propagate unchanged.
 */
internal fun resizeMinecraftCanvasTestWindow(
    context: ClientGameTestContext,
    logicalSize: IntSize,
) {
    require(0 < logicalSize.width && 0 < logicalSize.height) { "Canvas acceptance requires a positive logical viewport." }
    context.input.resizeWindow(logicalSize.width, logicalSize.height)
}
