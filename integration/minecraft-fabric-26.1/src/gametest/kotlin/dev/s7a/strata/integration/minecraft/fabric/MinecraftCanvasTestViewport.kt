package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Resizes the native OpenGL window and waits for its framebuffer and main render target to settle.
 * Callers own restoration; invalid dimensions, native failures, and bounded readiness failures propagate unchanged.
 */
internal fun resizeMinecraftTestWindow(
    context: MinecraftLoadedTestContext,
    logicalSize: IntSize,
    framebufferSize: IntSize = logicalSize,
) {
    require(0 < logicalSize.width && 0 < logicalSize.height) { "Minecraft acceptance requires a positive logical viewport." }
    require(0 < framebufferSize.width && 0 < framebufferSize.height) { "Minecraft acceptance requires a positive framebuffer." }
    context.resizeWindow(logicalSize)
    var matches = 0
    context.waitFor { minecraft ->
        val window = minecraft.window
        val target = minecraft.mainRenderTarget
        val settled =
            window.screenWidth == logicalSize.width && window.screenHeight == logicalSize.height &&
                window.width == framebufferSize.width && window.height == framebufferSize.height &&
                target.width == framebufferSize.width && target.height == framebufferSize.height
        matches = if (settled) matches + 1 else 0
        2 <= matches
    }
}
