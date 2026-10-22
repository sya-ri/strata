package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.Screenshot
import java.nio.file.Path

/**
 * Captures and closes the native framebuffer image on the client thread, requiring the requested dimensions.
 */
internal fun captureStandaloneMinecraftScreenshot(
    context: MinecraftLoadedTestContext,
    name: String,
    output: Path,
    size: IntSize,
): Path {
    val destination = output.resolve("$name.png")
    context.computeOnClient { minecraft ->
        Screenshot.takeScreenshot(minecraft.mainRenderTarget).use { image ->
            require(image.getWidth() == size.width && image.getHeight() == size.height) {
                "Loaded screenshot dimensions differ: expected=$size, actual=${image.getWidth()}x${image.getHeight()}."
            }
            image.writeToFile(destination)
        }
    }
    return destination
}
