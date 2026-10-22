package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Runs the target's complete loaded-client acceptance while this context owns its scheduling boundaries.
 */
internal fun MinecraftLoadedTestContext.runLoadedSuite() {
    MinecraftLoadedAcceptance.run(this)
}

/**
 * Borrows the client for a synchronous action and propagates its failure to the test thread.
 */
internal fun MinecraftLoadedTestContext.runOnClient(action: (Minecraft) -> Unit) {
    computeOnClient(action)
}

/**
 * Installs a freshly constructed screen on the client thread through its native lifecycle.
 */
internal fun MinecraftLoadedTestContext.setScreen(factory: () -> Screen?) {
    runOnClient { minecraft -> MinecraftClientScreenAccess.setScreen(minecraft, factory()) }
}

/**
 * Waits for the selected screen type, or for no screen when [type] is null, within the context's default tick bound.
 */
internal fun MinecraftLoadedTestContext.waitForScreen(type: Class<out Screen>?) {
    waitFor { minecraft ->
        val screen = MinecraftClientScreenAccess.currentScreen(minecraft)
        if (type == null) screen == null else type.isInstance(screen)
    }
}

/**
 * Captures the current physical viewport with an exact, counter-free filename owned by [output].
 */
internal fun MinecraftLoadedTestContext.takeScreenshot(
    name: String,
    output: Path,
): Path {
    val size = computeOnClient { minecraft -> IntSize(minecraft.window.screenWidth, minecraft.window.screenHeight) }
    return takeScreenshot(name, output, size)
}

/**
 * Captures and checks exact native image dimensions and pixels while borrowing [expected] without closing it.
 * The actual PNG remains in [output] when a mismatch fails the invocation.
 */
internal fun MinecraftLoadedTestContext.assertScreenshotEquals(
    expected: NativeImage,
    name: String,
    output: Path,
    size: IntSize,
) {
    require(expected.width == size.width && expected.height == size.height) { "The reference screenshot has different dimensions: $size." }
    val path = takeScreenshot(name, output, size)
    NativeImage.read(path.inputStream()).use { actual ->
        for (y in 0 until size.height) {
            for (x in 0 until size.width) {
                check(actual.getPixel(x, y) == expected.getPixel(x, y)) { "Native screenshot pixel differs at ($x, $y): $path" }
            }
        }
    }
}
