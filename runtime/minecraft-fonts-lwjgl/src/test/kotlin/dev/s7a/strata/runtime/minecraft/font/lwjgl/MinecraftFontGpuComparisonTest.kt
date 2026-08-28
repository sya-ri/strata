package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontFloatImage
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontGpuComparison
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontGpuComparison.Difference
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontGpuComparison.Observation
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontGpuPrecision
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontGpuReceipt
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityChecks
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontRasterSample
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Exercises the native-difference classifier with synthetic evidence, without Minecraft or a graphics context.
 * Tests deliberately corrupt tint, placement, sampling, candidate pixels, float evidence, and byte bounds.
 */
internal class MinecraftFontGpuComparisonTest {
    @TempDir
    lateinit var directory: Path

    private val precision = MinecraftFontGpuPrecision(8, 256)
    private val one = IntSize(1, 1)
    private val black = 0xFF000000.toInt()

    @Test
    fun `format conversion requires matching native float multiplication and one effective blend bound`() {
        val commands = scene(one, intArrayOf(0xFF1D1D1D.toInt()), one, FloatRect(0f, 0f, 1f, 1f), 0xFF161616.toInt())
        val candidate = rasterizeHeadless(commands, one).argbAt(0, 0)
        assertEquals(0xFF030303.toInt(), candidate)
        val actualFloat = gray((29f / 255f) * (22f / 255f))
        val comparison = MinecraftFontGpuComparison(commands, one, 1, precision)
        assertEquals(Difference.GpuColorConversion, comparison.classify(0, 0, Observation(0xFF020202.toInt(), candidate, actualFloat)))
        assertEquals(Difference.UnverifiedNativeColor, comparison.classify(0, 0, Observation(0xFF010101.toInt(), candidate, actualFloat)))
        assertEquals(Difference.UnverifiedNativeColor, comparison.classify(0, 0, Observation(black, candidate, actualFloat)))
        assertEquals(Difference.UnverifiedNativeFloat, comparison.classify(0, 0, Observation(0xFF020202.toInt(), candidate, gray(0.05f))))
        assertEquals(Difference.UnverifiedPortableRaster, comparison.classify(0, 0, Observation(0xFF020202.toInt(), 0xFF040404.toInt(), actualFloat)))
    }

    @Test
    fun `format conversion rounds the propagated byte bound outward after every blend`() {
        val background = 0xFF285FAA.toInt()
        val shadow = scene(one, intArrayOf(0x5D5D5D5D), one, FloatRect(0f, 0f, 1f, 1f), 0xFF282828.toInt(), background)
        val foreground = scene(one, intArrayOf(0x1F1F1F1F), one, FloatRect(0f, 0f, 1f, 1f), 0xFFA0A0A0.toInt(), background)
        val commands = shadow + foreground.drop(1)
        val candidate = rasterizeHeadless(commands, one).argbAt(0, 0)
        assertEquals(0xFF1E3C66.toInt(), candidate)
        val history =
            MinecraftFontRasterSample
                .background(background)
                .blend(0x5D5D5D5D, 0xFF282828.toInt(), 0.1f, false)
                .blend(0x1F1F1F1F, 0xFFA0A0A0.toInt(), 0.1f, false)
        assertEquals(
            Difference.GpuColorConversion,
            MinecraftFontGpuComparison(commands, one, 1, precision).classify(
                0,
                0,
                Observation(0xFF1C3B65.toInt(), candidate, MinecraftFontFloatImage.Sample(history.red, history.green, history.blue, 1f)),
            ),
        )
    }

    @Test
    fun `small tint regression cannot hide inside a one byte native difference`() {
        val commands = scene(one, intArrayOf(0xFF1D1D1D.toInt()), one, FloatRect(0f, 0f, 1f, 1f), 0xFF171717.toInt())
        val candidate = rasterizeHeadless(commands, one).argbAt(0, 0)
        val originalFloat = gray((29f / 255f) * (22f / 255f))
        assertEquals(
            Difference.UnverifiedNativeFloat,
            MinecraftFontGpuComparison(commands, one, 1, precision).classify(0, 0, Observation(0xFF020202.toInt(), candidate, originalFloat)),
        )
    }

    @Test
    fun `low channel tint regression cannot hide in accumulated unit magnitude float error`() {
        val first = scene(one, intArrayOf(0xFF414141.toInt()), one, FloatRect(0f, 0f, 1f, 1f))
        val changed = scene(one, intArrayOf(0x1A010101), one, FloatRect(0f, 0f, 1f, 1f), 0xFEFEFEFE.toInt())
        val commands = first + changed.drop(1)
        val candidate = rasterizeHeadless(commands, one).argbAt(0, 0)
        assertEquals(0xFF3A3A3A.toInt(), candidate)
        val alpha = (26f / 255f) * (254f / 255f)
        val originalFloat = gray((1f / 255f) * alpha + (65f / 255f) * (1f - alpha))
        assertEquals(
            Difference.UnverifiedNativeFloat,
            MinecraftFontGpuComparison(commands, one, 1, precision).classify(0, 0, Observation(0xFF3B3B3B.toInt(), candidate, originalFloat)),
        )
    }

    @Test
    fun `opaque overwrite removes previous float byte and boundary uncertainty`() {
        val background = MinecraftFontRasterSample.background(black)
        val previous =
            background
                .blend(0x80414141.toInt(), -1, 0.1f, true)
                .blend(0xA05B8BAD.toInt(), 0xE0F0E0D0.toInt(), 0.1f, false)
        val source = 0xFF1D00FF.toInt()
        val overwritten = previous.blend(source, -1, 0.1f, false)
        assertEquals(background.blend(source, -1, 0.1f, false), overwritten)
        assertEquals(Math.ulp(29f / 255f).toDouble(), overwritten.redFloatError)
        assertEquals(0.0, overwritten.greenFloatError)
        assertEquals(0.0, overwritten.blueFloatError)
    }

    @Test
    fun `measured pixel edge ownership permits inclusion or exclusion only at the edge`() {
        val viewport = IntSize(3, 1)
        val commands = scene(viewport, intArrayOf(-1), one, FloatRect(0.5f, 0f, 1.5f, 1f))
        val candidate = rasterizeHeadless(commands, viewport)
        val comparison = MinecraftFontGpuComparison(commands, viewport, 1, precision)
        assertEquals(Difference.GpuRasterBoundary, comparison.classify(0, 0, Observation(black, candidate.argbAt(0, 0), gray(0f))))
        assertEquals(Difference.GpuRasterBoundary, comparison.classify(1, 0, Observation(-1, candidate.argbAt(1, 0), gray(1f))))
        assertEquals(Difference.UnverifiedNativeFloat, comparison.classify(2, 0, Observation(-1, candidate.argbAt(2, 0), gray(1f))))
        assertEquals(Difference.UnverifiedNativeFloat, comparison.classify(0, 0, Observation(0xFF808080.toInt(), candidate.argbAt(0, 0), gray(0.5f))))
    }

    @Test
    fun `nearest sampling permits only adjacent texels at a verified interpolation tie`() {
        val viewport = IntSize(7, 1)
        val colors = intArrayOf(-1, -1, 0xFF0000FF.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(), -1, -1, -1)
        val commands = scene(viewport, colors, IntSize(8, 1), FloatRect(0f, 0f, 7f, 1f))
        val candidate = rasterizeHeadless(commands, viewport).argbAt(3, 0)
        assertEquals(0xFFFF0000.toInt(), candidate)
        val comparison = MinecraftFontGpuComparison(commands, viewport, 1, precision)
        assertEquals(Difference.GpuRasterBoundary, comparison.classify(3, 0, Observation(0xFF00FF00.toInt(), candidate, MinecraftFontFloatImage.Sample(0f, 1f, 0f, 1f))))
        assertEquals(Difference.UnverifiedNativeFloat, comparison.classify(3, 0, Observation(0xFF0000FF.toInt(), candidate, MinecraftFontFloatImage.Sample(0f, 0f, 1f, 1f))))
    }

    @Test
    fun `discarded samples do not create a format conversion allowance`() {
        val commands = scene(one, intArrayOf(0x19FFFFFF), one, FloatRect(0f, 0f, 1f, 1f))
        val candidate = rasterizeHeadless(commands, one).argbAt(0, 0)
        assertEquals(black, candidate)
        assertEquals(
            Difference.UnverifiedNativeColor,
            MinecraftFontGpuComparison(commands, one, 1, precision).classify(0, 0, Observation(0xFF010101.toInt(), candidate, gray(0f))),
        )
    }

    @Test
    fun `exact color endpoints have no byte allowance even when other channels are blended`() {
        val commands = scene(one, intArrayOf(0xFF001DFF.toInt()), one, FloatRect(0f, 0f, 1f, 1f), 0xFFFF16FF.toInt())
        val candidate = rasterizeHeadless(commands, one).argbAt(0, 0)
        val nativeFloat = MinecraftFontFloatImage.Sample(0f, (29f / 255f) * (22f / 255f), 1f, 1f)
        val comparison = MinecraftFontGpuComparison(commands, one, 1, precision)
        assertEquals(Difference.GpuColorConversion, comparison.classify(0, 0, Observation(0xFF0002FF.toInt(), candidate, nativeFloat)))
        assertEquals(Difference.UnverifiedNativeColor, comparison.classify(0, 0, Observation(0xFF0102FF.toInt(), candidate, nativeFloat)))
        assertEquals(Difference.UnverifiedNativeColor, comparison.classify(0, 0, Observation(0xFF0002FE.toInt(), candidate, nativeFloat)))
        val blackCommands = scene(one, intArrayOf(black), one, FloatRect(0f, 0f, 1f, 1f))
        assertEquals(
            Difference.UnverifiedNativeColor,
            MinecraftFontGpuComparison(blackCommands, one, 1, precision).classify(0, 0, Observation(0xFF010101.toInt(), black, gray(0f))),
        )
    }

    @Test
    fun `missing and nonfinite native float evidence fail instead of enabling tolerance`() {
        assertThrows(IllegalStateException::class.java) { MinecraftFontFloatImage.read(directory.resolve("missing.rgba32f"), one) }
        assertThrows(IllegalArgumentException::class.java) { MinecraftFontFloatImage.of(one, floatArrayOf(Float.NaN, 0f, 0f, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { MinecraftFontFloatImage.of(one, floatArrayOf(0f, 0f, 0f)) }
        assertThrows(IllegalArgumentException::class.java) { MinecraftFontGpuPrecision(0, 256) }
    }

    @Test
    fun `precision observations must describe the same scale viewport and float target`() {
        val path = directory.resolve("precision.properties")
        val metadata = "scale=1\nwidth=1\nheight=1\nsubpixelBits=8\nmaxAtlasWidth=256\nmaxAtlasHeight=256\ncolorFormat=RGBA32F\npreparationThreads=1\n"
        Files.writeString(path, metadata)
        assertEquals(precision, MinecraftFontGpuPrecision.read(path, 1, one))
        assertThrows(IllegalStateException::class.java) { MinecraftFontGpuPrecision.read(path, 2, one) }
        assertThrows(IllegalStateException::class.java) { MinecraftFontGpuPrecision.read(path, 1, IntSize(2, 1)) }
        Files.writeString(path, metadata.replace("RGBA32F", "RGBA8"))
        assertThrows(IllegalArgumentException::class.java) { MinecraftFontGpuPrecision.read(path, 1, one) }
        Files.writeString(path, metadata.replace("preparationThreads=1", "preparationThreads=2"))
        assertThrows(IllegalStateException::class.java) { MinecraftFontGpuPrecision.read(path, 1, one) }
    }

    @Test
    fun `overflowing counts cannot forge complete native proof coverage`() {
        val counts = Difference.entries.associateWith { 0 }.toMutableMap()
        counts[Difference.Exact] = Int.MAX_VALUE
        counts[Difference.GpuColorConversion] = Int.MAX_VALUE
        counts[Difference.GpuRasterBoundary] = 320 * 240 + 2
        val metadata = "schemaVersion=1\nscale=1\n" + counts.entries.joinToString("\n") { (kind, count) -> "pixels.${kind.name}=$count" }
        Files.writeString(directory.resolve("font-native-1-comparison.properties"), metadata)
        assertThrows(IllegalStateException::class.java) { MinecraftFontGpuReceipt.verify(directory, MinecraftFontParityFixture.Target.LegacyFreeType) }
    }

    @Test
    fun `format only pipeline calibration requires exact ordinary RGBA8 pixels at every scale`() {
        for (scale in 1..3) {
            val calibrationPath = writeCalibration(scale)
            assertEquals(calibrationPath, MinecraftFontGpuReceipt.verifyCalibration(directory, scale))
            val changed = ImageIO.read(calibrationPath.toFile())
            changed.setRGB(changed.width - 1, changed.height - 1, 0xFF010203.toInt())
            check(ImageIO.write(changed, "png", calibrationPath.toFile()))
            assertThrows(IllegalStateException::class.java) { MinecraftFontGpuReceipt.verifyCalibration(directory, scale) }
        }
        val calibrationPath = writeCalibration(1)
        check(ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", calibrationPath.toFile()))
        assertThrows(IllegalStateException::class.java) { MinecraftFontGpuReceipt.verifyCalibration(directory, 1) }
        Files.delete(calibrationPath)
        assertThrows(IllegalStateException::class.java) { MinecraftFontGpuReceipt.verifyCalibration(directory, 1) }
    }

    @Test
    fun `exact calibration images cannot substitute for required format only metadata`() {
        writeCalibration(1)
        val path = directory.resolve("native-float-state-1.properties")
        for (metadata in listOf("rgba8Calibration=exact\n", "pipelineFormatOnly=false\nrgba8Calibration=exact\n", "pipelineFormatOnly=true\n", "pipelineFormatOnly=true\nrgba8Calibration=approximate\n")) {
            Files.writeString(path, metadata)
            assertThrows(IllegalStateException::class.java) { MinecraftFontGpuReceipt.verifyCalibration(directory, 1) }
        }
    }

    @Test
    fun `recorded evidence must match current resource bytes scene and compiled target contract`() {
        val compatibility = MinecraftFontParityFixture.Target.LegacyFreeType.compatibility
        val current = MinecraftFontParityFixture.evidenceMetadata(compatibility)
        MinecraftFontParityChecks.verifyInputMetadata(compatibility, current)
        val inputKey = current.keys.first { it.startsWith("input.") }
        for (key in listOf(inputKey, "scene.sha256", "compatibility.interleavedShadows", "runtime.lwjgl")) {
            val stale = current + (key to "different")
            assertThrows(IllegalStateException::class.java) { MinecraftFontParityChecks.verifyInputMetadata(compatibility, stale) }
        }
        assertThrows(IllegalStateException::class.java) { MinecraftFontParityChecks.verifyInputMetadata(compatibility, current - inputKey) }
        assertThrows(IllegalStateException::class.java) {
            MinecraftFontParityChecks.verifyInputMetadata(compatibility, current + ("input.removed-resource.sha256" to "obsolete"))
        }
    }

    @Test
    fun `overlapping translucent glyphs preserve native draw order`() {
        val red = scene(one, intArrayOf(0x80FF0000.toInt()), one, FloatRect(0f, 0f, 1f, 1f))
        val green = scene(one, intArrayOf(0x8000FF00.toInt()), one, FloatRect(0f, 0f, 1f, 1f))
        val commands = red + green.drop(1)
        val candidate = rasterizeHeadless(commands, one).argbAt(0, 0)
        val alpha = 128f / 255f
        val reversedFloat = MinecraftFontFloatImage.Sample(alpha, alpha * (1f - alpha), 0f, 1f)
        val reversedArgb = rasterizeHeadless(green + red.drop(1), one).argbAt(0, 0)
        assertEquals(
            Difference.UnverifiedNativeFloat,
            MinecraftFontGpuComparison(commands, one, 1, precision).classify(0, 0, Observation(reversedArgb, candidate, reversedFloat)),
        )
    }

    @Test
    fun `raster boundary alternatives cannot cross an exact clip boundary`() {
        val viewport = IntSize(2, 1)
        val unclipped = scene(viewport, intArrayOf(-1), one, FloatRect(0.5f, 0f, 1.5f, 1f))
        val commands = listOf(unclipped.first(), DrawCommand.PushClip(IntRect(1, 0, 2, 1)), unclipped.last(), DrawCommand.PopClip)
        val candidate = rasterizeHeadless(commands, viewport)
        val comparison = MinecraftFontGpuComparison(commands, viewport, 1, precision)
        assertEquals(Difference.UnverifiedNativeFloat, comparison.classify(0, 0, Observation(-1, candidate.argbAt(0, 0), gray(1f))))
        assertEquals(Difference.GpuRasterBoundary, comparison.classify(1, 0, Observation(-1, candidate.argbAt(1, 0), gray(1f))))
    }

    private fun gray(value: Float): MinecraftFontFloatImage.Sample = MinecraftFontFloatImage.Sample(value, value, value, 1f)

    private fun writeCalibration(scale: Int): Path {
        val viewport = MinecraftFontParityFixture.viewport
        val image = BufferedImage(viewport.width * scale, viewport.height * scale, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0x7F102030)
        check(ImageIO.write(image, "png", directory.resolve("font-native-$scale.png").toFile()))
        val calibration = directory.resolve("font-native-calibration-$scale.png")
        check(ImageIO.write(image, "png", calibration.toFile()))
        Files.writeString(directory.resolve("native-float-state-$scale.properties"), "pipelineFormatOnly=true\nrgba8Calibration=exact\n")
        return calibration
    }

    private fun scene(
        viewport: IntSize,
        colors: IntArray,
        imageSize: IntSize,
        destination: FloatRect,
        tint: Int = -1,
        background: Int = black,
    ): List<DrawCommand> =
        listOf(
            DrawCommand.FillRectangle(IntRect(0, 0, viewport.width, viewport.height), ArgbColor(background)),
            DrawCommand.SampledImage(
                createDrawImage(imageSize, colors),
                FloatRect(0f, 0f, imageSize.width.toFloat(), imageSize.height.toFloat()),
                destination,
                ArgbColor(tint),
            ),
        )
}
