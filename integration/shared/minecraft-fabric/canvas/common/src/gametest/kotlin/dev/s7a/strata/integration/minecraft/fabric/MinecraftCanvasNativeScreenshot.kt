package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

// Native image encoding and cleanup can fail with arbitrary Throwable; preserve the failure in the test's explicit future.

/**
 * Transfers one explicitly requested native screenshot into a contained acceptance PNG and completes its detached result.
 *
 * The native screenshot callback transfers exclusive ownership of [image]; this method always closes it after writing.
 * Calls run on Minecraft's screenshot completion thread and complete [result] exactly once without retaining native storage.
 * File, encoding, and close failures complete the result exceptionally instead of escaping into an unrelated render frame.
 */
@Suppress("TooGenericExceptionCaught")
internal fun completeCanvasNativeScreenshot(
    image: NativeImage,
    path: Path,
    result: CompletableFuture<Path>,
) {
    try {
        image.use {
            Files.createDirectories(path.parent)
            it.writeToFile(path)
        }
        result.complete(path)
    } catch (failure: Throwable) {
        result.completeExceptionally(failure)
    }
}
