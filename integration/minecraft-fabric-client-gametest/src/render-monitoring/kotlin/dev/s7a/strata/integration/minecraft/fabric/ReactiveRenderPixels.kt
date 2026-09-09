package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Pixel oracle shared by native adapters; only the opaque scenario rectangle is compared.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object ReactiveRenderPixels {
    /**
     * Renders literal expected content with the same extracted resources on the client owner thread.
     */
    fun reference(
        definition: ScreenDefinition,
        profile: MinecraftUiProfile,
    ): HeadlessImage =
        createMinecraftUiHost(definition, profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            val frame = host.frame(IntSize(160, 48))
            rasterizeHeadless(frame.drawCommands, frame.size)
        }

    /**
     * Writes detached reference evidence and rejects any differing native pixel in the scenario rectangle.
     */
    fun verify(
        expected: HeadlessImage,
        screenshot: Path,
        output: Path,
    ) {
        Files.write(output.resolve("reactive-rendering-headless.png"), expected.encodePng())
        val native = checkNotNull(ImageIO.read(screenshot.toFile()))
        check(expected.size.width <= native.width && expected.size.height <= native.height)
        repeat(expected.size.height) { y ->
            repeat(expected.size.width) { x ->
                check(expected.argbAt(x, y) == native.getRGB(x, y)) {
                    "Reactive native pixels differ from the literal reference at ($x, $y)."
                }
            }
        }
    }
}
