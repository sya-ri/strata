@file:Suppress("DEPRECATION") // Loaded acceptance keeps the existing compatibility screen factory.

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.floor

/**
 * Checks large CPU-only sampled tiles against an independent per-pixel Float oracle in the loaded client.
 * An 8192-texel crop stays below production GPU composition admission while its output admits CPU palettes.
 * Uniform and early-changing backgrounds, reversed overlapping samples and clips use actual GUI consumption.
 * The final opaque portable tile avoids depending on the changing world underneath the screen.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftDestinationPaletteGameTest {
    private val viewport = IntSize(320, 240)
    private val imageSize = IntSize(128, 64)

    /**
     * Validates every GUI density and a fresh source revision, releasing the screen's real owners between cases.
     * The caller restores the original viewport after its complete Canvas suite.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        for (scale in 1..4) {
            val physical = IntSize(viewport.width * scale, viewport.height * scale)
            context.configureViewport(physical, scale)
            for (revision in listOf(0, 137)) verify(context, profile, physical, scale, revision)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Preserve a primary native/assertion failure while releasing every screen owner.
    private fun verify(context: MinecraftCanvasTestContext, profile: MinecraftUiProfile, physical: IntSize, scale: Int, revision: Int) {
        context.waitFor { released() }
        val screen = context.onClient { createMinecraftScreen(scene(revision), profile, parent = null) }
        var failure: Throwable? = null
        try {
            context.onClient { context.setScreen(screen) }
            MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen))
            val rasterizations = context.onClient { rasterizations(screen) }
            context.onClient {
                check(0L < rasterizations) { "Palette acceptance did not rasterize a portable CPU tile." }
                check(MinecraftCompositionParityInputs.portable(screen).none { MinecraftCompositionParityInputs.composed(it.first) }) {
                    "The small source crop must preserve the existing whole-tile CPU fallback."
                }
            }
            val name = "destination-palette-$scale-$revision"
            val path = context.takeScreenshot(name, physical)
            val actual = checkNotNull(ImageIO.read(path.toFile()))
            check(actual.width == physical.width && actual.height == physical.height)
            for (y in 0 until physical.height) {
                for (x in 0 until physical.width) {
                    val expected = reference(x / scale, y / scale, revision)
                    check(actual.getRGB(x, y) == expected) { "CPU palette GUI output differs at ($x, $y), scale=$scale, revision=$revision." }
                }
            }
            Files.writeString(path.resolveSibling("$name.properties"), "portable_rasterizations=$rasterizations\ngpu_tiles=0\nchecked_pixels=${physical.width * physical.height}\ntolerance=0\nresult=passed\n")
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(failure, { context.onClient { context.setScreen(null) } }, { context.onClient { screen.close() } }, { context.waitFor { released() } })
        }
    }

    private fun scene(revision: Int): ScreenDefinition {
        val image = createDrawImage(imageSize) { x, y -> source(y * imageSize.width + x, revision) }
        val backdrop = createDrawImage(imageSize) { x, y -> background(y * imageSize.width + x) }
        val source = FloatRect(0f, 0f, imageSize.width.toFloat(), imageSize.height.toFloat())
        val binding =
            CanvasSource {
                object : CanvasBinding {
                    override fun paint(scope: PaintScope) {
                        scope.fillRectangle(IntRect(0, 0, 128, 64), ArgbColor(0xFF234567.toInt()))
                        scope.blitImage(backdrop, IntRect(0, 0, 128, 64), IntRect(128, 0, 256, 64))
                        for (left in listOf(0f, 128f)) {
                            scope.sampledImage(image, source, FloatRect(left, 0f, left + 128f, 64f), ArgbColor(0x80A4C6E8.toInt()), 0f)
                        }
                        scope.withClip(IntRect(8, 8, 248, 56)) {
                            for (left in listOf(0f, 128f)) {
                                scope.sampledImage(image, source, FloatRect(left, 0f, left + 128f, 64f), SampledImageOrientation.FlipHorizontal, ArgbColor(0xFE37659B.toInt()), 0.1f)
                            }
                        }
                    }

                    override fun close(): Unit = Unit
                }
            }
        return ScreenDefinition("Destination palette CPU acceptance") {
            Stack(Modifier.Empty.size(viewport.width, viewport.height).background(ArgbColor(0xFF102030.toInt()))) {
                Canvas(binding, viewport)
            }
        }
    }

    private fun source(
        index: Int,
        revision: Int,
    ): Int = ((if (index % 3 == 0) 128 else index and 255) shl 24) or ((index * 73471 xor revision) and 0xFFFFFF)

    private fun background(index: Int): Int = if (index < 16) 0xFF234567.toInt() else 0xFF000000.toInt() or (index * 37199 and 0xFFFFFF)

    // Integer source/destination extents place all tested physical centers strictly inside logical source texels.
    // The oracle reads the fixed input formula, never optimized headless pixels or native texture snapshots.
    private fun reference(
        x: Int,
        y: Int,
        revision: Int,
    ): Int {
        if (256 <= x || 64 <= y) return 0xFF102030.toInt()
        val localX = x % 128
        val index = y * 128 + localX
        val destination = if (x < 128) 0xFF234567.toInt() else background(index)
        val first = blend(source(index, revision), destination, 0x80A4C6E8.toInt(), 0f)
        val insideX = 8 <= x && x < 248
        val insideY = 8 <= y && y < 56
        return if (insideX && insideY) blend(source(y * 128 + 127 - localX, revision), first, 0xFE37659B.toInt(), 0.1f) else first
    }

    private fun blend(
        source: Int,
        destination: Int,
        tint: Int,
        cutoff: Float,
    ): Int {
        val alpha = (source ushr 24).toFloat() / 255f * ((tint ushr 24).toFloat() / 255f)
        if (alpha == 0f || alpha < cutoff) return destination
        val weight = (destination ushr 24).toFloat() / 255f * (1f - alpha)
        val outputAlpha = alpha + weight

        fun channel(shift: Int): Int {
            val contribution = (source ushr shift and 255).toFloat() / 255f * ((tint ushr shift and 255).toFloat() / 255f) * alpha
            val value = (contribution + (destination ushr shift and 255).toFloat() / 255f * weight) / outputAlpha
            return rounded(value)
        }
        return (rounded(outputAlpha) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun rounded(value: Float): Int = floor((value * 255f).toDouble() + 0.5).toInt().coerceIn(0, 255)

    private fun rasterizations(screen: FabricMinecraftScreen): Long {
        val owner = if (runCatching { screen.javaClass.getDeclaredField("portableFrames") }.isSuccess) screen else MinecraftCompositionParityInputs.member(screen, "presentation")
        return MinecraftCompositionParityInputs.member(owner, "portableRasterizationCount") as Long
    }

    private fun released(): Boolean = NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0
}
