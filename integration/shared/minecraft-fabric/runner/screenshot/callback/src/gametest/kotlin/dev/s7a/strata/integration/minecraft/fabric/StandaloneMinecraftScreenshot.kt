package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Screenshot
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Requests a native framebuffer capture on the client thread and waits off-thread while the GPU completes it.
 */
internal fun captureStandaloneMinecraftScreenshot(
    context: MinecraftLoadedTestContext,
    name: String,
    output: Path,
    size: IntSize,
): Path {
    val destination = output.resolve("$name.png")
    val result = CompletableFuture<Path>()
    context.computeOnClient { minecraft ->
        minecraft.gameRenderer.render(DeltaTracker.ONE, true)
        Screenshot.takeScreenshot(minecraft.mainRenderTarget) { image ->
            runCatching {
                image.use {
                    require(it.getWidth() == size.width && it.getHeight() == size.height) {
                        "Loaded screenshot dimensions differ: expected=$size, actual=${it.getWidth()}x${it.getHeight()}."
                    }
                    it.writeToFile(destination)
                    destination
                }
            }.onSuccess(result::complete).onFailure(result::completeExceptionally)
        }
    }
    return result.get(30, TimeUnit.SECONDS)
}
