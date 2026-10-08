@file:Suppress("DEPRECATION") // Acceptance uses the same compatibility factory as existing loaded Canvas tests.

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Compares complete native GUI consumption of ordered transparent image/glyph tiles with the previous CPU path on every adapter family.
 * The fixture changes only preparation admission for the identical display list; native boundaries, blending and screenshot comparison stay unchanged.
 * Committed host frames order each screenshot and the existing source and generation owners fence every resource through cleanup.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftOrderedCompositionGameTest {
    private val viewport = IntSize(320, 240)

    /**
     * Checks two GUI densities and image replacement after the caller's other native Canvas scenes.
     * The surrounding version adapter restores its starting viewport after return.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        for (scale in 1..2) {
            val physical = IntSize(viewport.width * scale, viewport.height * scale)
            context.configureViewport(physical, scale)
            for (revision in listOf(0, 137)) verify(context, profile, physical, scale, revision)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Native or assertion failures remain primary while independent screen cleanup is attempted.
    private fun verify(context: MinecraftCanvasTestContext, profile: MinecraftUiProfile, physical: IntSize, scale: Int, revision: Int) {
        val screen = context.onClient { createMinecraftScreen(createMinecraftCompositionParityScene(viewport, revision), profile, parent = null) }
        var failure: Throwable? = null
        try {
            context.onClient { context.setScreen(screen) }
            val first = MinecraftCanvasFrameFence.hostFrameCount(context, screen)
            MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, first)
            val tiles = context.onClient { MinecraftCompositionParityInputs.portable(screen).count { MinecraftCompositionParityInputs.composed(it.first) } }
            check(0 < tiles) { "Ordered transparent parity must exercise whole-tile GPU composition." }
            val gpu = context.takeScreenshot("ordered-composition-gpu-$scale-$revision", physical)
            val baseline = MinecraftCanvasFrameFence.hostFrameCount(context, screen)
            context.onClient { MinecraftCompositionParityInputs.selectCpu(screen) }
            MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, baseline)
            // Another committed frame proves the changed preparation completed ordinary native GUI consumption.
            MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen))
            context.onClient { check(MinecraftCompositionParityInputs.portable(screen).none { MinecraftCompositionParityInputs.composed(it.first) }) }
            val cpu = context.takeScreenshot("ordered-composition-cpu-$scale-$revision", physical)
            compare(cpu, gpu, physical)
            Files.writeString(context.outputDirectory.resolve("ordered-composition-$scale-$revision.properties"), "gpu_tiles=$tiles\ntolerance=0\nresult=passed\n")
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(failure, { context.onClient { context.setScreen(null) } }, { context.onClient { screen.close() } })
        }
    }

    private fun compare(
        cpu: Path,
        gpu: Path,
        size: IntSize,
    ) {
        val expected = checkNotNull(ImageIO.read(cpu.toFile()))
        val actual = checkNotNull(ImageIO.read(gpu.toFile()))
        check(expected.width == size.width && expected.height == size.height && actual.width == size.width && actual.height == size.height)
        for (y in 0 until size.height) for (x in 0 until size.width) check(expected.getRGB(x, y) == actual.getRGB(x, y)) { "Whole ordered GUI composition differs at ($x, $y) in $gpu" }
    }
}
