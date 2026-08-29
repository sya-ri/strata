package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

// Native screenshot allocation can fail before delivering an image; route that exact failure to the waiting test.

/**
 * Reads the actual immediate OpenGL framebuffer at an explicit acceptance boundary.
 *
 * The caller runs on the client render thread and owns [path] inside the runner's build directory.
 * Pixel assertions call this after GUI consumption; failure assertions copy before and after a manual render in the same client task.
 * This test-only readback is never used by ordinary Canvas presentation or portable capture.
 * The returned future owns only its PNG path or failure after the native image is closed.
 */
@Suppress("TooGenericExceptionCaught")
internal fun captureMinecraftCanvasNativeFrame(path: Path): CompletableFuture<Path> {
    RenderSystem.assertOnRenderThread()
    val result = CompletableFuture<Path>()
    try {
        completeCanvasNativeScreenshot(Screenshot.takeScreenshot(Minecraft.getInstance().mainRenderTarget), path, result)
    } catch (failure: Throwable) {
        result.completeExceptionally(failure)
    }
    return result
}
