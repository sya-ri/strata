package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

// Enqueue and asynchronous image failures must remain attached to this explicit test capture.

/**
 * Records an explicit native framebuffer readback at a caller-selected acceptance boundary on the actual backend.
 *
 * Calls belong to the client render thread and [path] belongs to the runner's contained build directory.
 * Pixel assertions call this after GUI consumption; failure assertions copy before and after a manual extraction in the same client task.
 * The texture-to-buffer copy is ordered during this call in Minecraft's command encoder and completes after native submission.
 * Minecraft owns staging storage until it delivers an image for encoding and close; the future retains no GPU handle.
 * Ordinary Canvas presentation and portable capture never invoke this acceptance-only helper.
 */
@Suppress("TooGenericExceptionCaught")
internal fun captureMinecraftCanvasNativeFrame(path: Path): CompletableFuture<Path> {
    RenderSystem.assertOnRenderThread()
    val result = CompletableFuture<Path>()
    try {
        Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget()) { image -> completeCanvasNativeScreenshot(image, path, result) }
    } catch (failure: Throwable) {
        result.completeExceptionally(failure)
    }
    return result
}
