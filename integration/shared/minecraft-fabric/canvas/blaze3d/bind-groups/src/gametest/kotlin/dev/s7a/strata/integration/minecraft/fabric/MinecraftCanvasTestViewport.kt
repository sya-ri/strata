@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.mixin.canvas.MinecraftCanvasWindowTestAccess
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction

/**
 * Configures the requested logical and framebuffer test extents while preserving a stable native Vulkan surface.
 *
 * OpenGL delegates native resize ownership and framebuffer scaling to Fabric.
 * Vulkan borrows the active client window on the render thread, applies [logicalSize] and [framebufferSize] independently, and resizes the main target without changing the platform window or swapchain.
 * Invalid dimensions fail before scheduling; unsupported backends, scheduling failures, and target resize failures propagate unchanged.
 */
internal fun resizeMinecraftCanvasTestWindow(
    context: ClientGameTestContext,
    logicalSize: IntSize,
    framebufferSize: IntSize = logicalSize,
) {
    require(0 < logicalSize.width && 0 < logicalSize.height) { "Canvas acceptance requires a positive logical viewport." }
    require(0 < framebufferSize.width && 0 < framebufferSize.height) { "Canvas acceptance requires a positive framebuffer." }
    val backend =
        context.computeOnClient(
            FailableFunction<Minecraft, MinecraftCanvasTestBackend, RuntimeException> {
                MinecraftCanvasTestBackend.parse(RenderSystem.getDevice().deviceInfo.backendName())
            },
        )
    when (backend) {
        MinecraftCanvasTestBackend.OpenGl -> {
            context.input.resizeWindow(logicalSize.width, logicalSize.height)
        }

        MinecraftCanvasTestBackend.Vulkan -> {
            context.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    val window = minecraft.window
                    val access = window as Any as MinecraftCanvasWindowTestAccess
                    access.strataCanvasSetScreenWidth(logicalSize.width)
                    access.strataCanvasSetScreenHeight(logicalSize.height)
                    window.setWidth(framebufferSize.width)
                    window.setHeight(framebufferSize.height)
                    minecraft.gameRenderer.mainRenderTarget().resize(framebufferSize.width, framebufferSize.height)
                },
            )
        }
    }
}
