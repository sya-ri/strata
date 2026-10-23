@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.mixin.canvas.MinecraftCanvasWindowTestAccess
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Resizes the native OpenGL window and waits for its framebuffer and main render target to settle.
 * Vulkan retains its platform surface while updating the independently owned test viewport and target.
 * Callers own restoration; invalid dimensions, native failures, and bounded readiness failures propagate unchanged.
 */
internal fun resizeMinecraftTestWindow(
    context: MinecraftLoadedTestContext,
    logicalSize: IntSize,
    framebufferSize: IntSize = logicalSize,
) {
    require(0 < logicalSize.width && 0 < logicalSize.height) { "Minecraft acceptance requires a positive logical viewport." }
    require(0 < framebufferSize.width && 0 < framebufferSize.height) { "Minecraft acceptance requires a positive framebuffer." }
    val backend = context.computeOnClient { MinecraftCanvasTestBackend.parse(RenderSystem.getDevice().deviceInfo.backendName()) }
    if (backend == MinecraftCanvasTestBackend.Vulkan) {
        context.runOnClient { minecraft ->
            val window = minecraft.window
            val access = MinecraftCanvasWindowTestAccess::class.java.cast(window)
            access.strataCanvasSetScreenWidth(logicalSize.width)
            access.strataCanvasSetScreenHeight(logicalSize.height)
            window.width = framebufferSize.width
            window.height = framebufferSize.height
            minecraft.gameRenderer.mainRenderTarget().resize(framebufferSize.width, framebufferSize.height)
        }
        return
    }
    context.resizeWindow(logicalSize)
    var matches = 0
    context.waitFor { minecraft ->
        val window = minecraft.window
        val target = minecraft.gameRenderer.mainRenderTarget()
        val settled =
            window.screenWidth == logicalSize.width && window.screenHeight == logicalSize.height &&
                window.width == framebufferSize.width && window.height == framebufferSize.height &&
                target.width == framebufferSize.width && target.height == framebufferSize.height
        matches = if (settled) matches + 1 else 0
        2 <= matches
    }
}
