package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Captures one native frame, closes its owned image, and waits off-thread for the completed PNG.
 * The requested dimensions remain an exact acceptance condition.
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
        readStandaloneMinecraftFramebuffer(minecraft) { image ->
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
    return try {
        result.get(30, TimeUnit.SECONDS)
    } catch (failure: ExecutionException) {
        throw (failure.cause ?: failure)
    }
}
