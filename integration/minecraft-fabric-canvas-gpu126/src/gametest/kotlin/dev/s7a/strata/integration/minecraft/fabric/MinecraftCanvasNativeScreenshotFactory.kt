package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

// Both enqueue and completion failures must reach the waiting test without escaping into a later unrelated render frame.

/**
 * Queues an explicit native framebuffer copy at the caller's ordered acceptance boundary.
 *
 * Calls belong to the client render thread; [path] is owned by the acceptance runner inside its build directory.
 * Pixel assertions call this after GUI consumption; failure assertions copy before and after a manual extraction in the same client task.
 * The texture-to-buffer copy is issued during this call; only image completion is deferred.
 * Minecraft owns the readback staging resource until its callback transfers an image for encoding and close.
 * The returned future completes with a PNG path or the exact failure, and ordinary Canvas frames never call this helper.
 */
@Suppress("TooGenericExceptionCaught")
internal fun captureMinecraftCanvasNativeFrame(path: Path): CompletableFuture<Path> {
    RenderSystem.assertOnRenderThread()
    val result = CompletableFuture<Path>()
    try {
        Screenshot.takeScreenshot(Minecraft.getInstance().mainRenderTarget) { image -> completeCanvasNativeScreenshot(image, path, result) }
    } catch (failure: Throwable) {
        result.completeExceptionally(failure)
    }
    return result
}
