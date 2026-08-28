package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.render.DrawCommand
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Produces a full-frame difference classification from current independent native float evidence.
 * Only final image differences are classified; provider and layout comparisons remain exact prerequisites.
 * Each call owns its raster and report storage, closes file handles, and fails on incomplete or malformed evidence.
 */
internal object MinecraftFontGpuImageComparison {
    /**
     * Writes candidate pixels, per-pixel classifications, a difference PNG, and hashed comparison metadata.
     * Commands must originate in fresh portable resource rendering, never from a native capture.
     */
    fun compare(
        nativePath: Path,
        portable: HeadlessImage,
        candidatePath: Path,
        commands: List<DrawCommand>,
        scale: Int,
        output: Path = nativePath.parent,
    ): Result {
        Files.createDirectories(output)
        Files.write(candidatePath, portable.encodePng())
        val nativeRoot = nativePath.parent
        val native = checkNotNull(ImageIO.read(nativePath.toFile())) { "Invalid native font image: $nativePath" }
        check(IntSize(native.width, native.height) == portable.size) { "Native and portable physical font viewports differ." }
        val floatPath = nativeRoot.resolve("font-native-$scale.rgba32f")
        val precisionPath = nativeRoot.resolve("native-float-state-$scale.properties")
        val float = MinecraftFontFloatImage.read(floatPath, portable.size)
        val precision = MinecraftFontGpuPrecision.read(precisionPath, scale, portable.size)
        val comparison = MinecraftFontGpuComparison(commands, MinecraftFontParityFixture.viewport, scale, precision)
        val diff = BufferedImage(native.width, native.height, BufferedImage.TYPE_INT_ARGB)
        val classifications = output.resolve("font-native-$scale-classifications.tsv")
        val result =
            Files.newBufferedWriter(classifications).use { report ->
                report.appendLine("x\ty\tclassification\tnativeArgb\tportableArgb")
                comparePixels(native, portable, float, comparison, diff) { x, y, kind, expected, actual ->
                    report.appendLine("$x\t$y\t${kind.name}\t${expected.toUInt().toString(16)}\t${actual.toUInt().toString(16)}")
                }
            }
        val differencePath = output.resolve("font-native-$scale-diff.png")
        if (result.differences == 0) Files.deleteIfExists(differencePath) else check(ImageIO.write(diff, "png", differencePath.toFile()))
        writeResult(output, scale, result, listOf(nativePath, candidatePath, floatPath, precisionPath, classifications))
        return result
    }

    private fun comparePixels(
        native: BufferedImage,
        portable: HeadlessImage,
        float: MinecraftFontFloatImage,
        comparison: MinecraftFontGpuComparison,
        diff: BufferedImage,
        record: (Int, Int, MinecraftFontGpuComparison.Difference, Int, Int) -> Unit,
    ): Result {
        val counts =
            MinecraftFontGpuComparison.Difference.entries
                .associateWith { 0 }
                .toMutableMap()
        var firstFailure = ""
        for (y in 0 until native.height) {
            for (x in 0 until native.width) {
                val expected = native.getRGB(x, y)
                val actual = portable.argbAt(x, y)
                val kind = comparison.classify(x, y, MinecraftFontGpuComparison.Observation(expected, actual, float.sample(x, y)))
                counts[kind] = checkNotNull(counts[kind]) + 1
                if (kind != MinecraftFontGpuComparison.Difference.Exact) {
                    record(x, y, kind, expected, actual)
                    if (kind.accepted.not() && firstFailure.isEmpty()) firstFailure = "($x,$y) ${kind.name}: native=${expected.toUInt().toString(16)}, portable=${actual.toUInt().toString(16)}"
                }
                diff.setRGB(
                    x,
                    y,
                    when {
                        kind == MinecraftFontGpuComparison.Difference.Exact -> 0xFF202020.toInt()
                        kind.accepted -> 0xFF00C8FF.toInt()
                        else -> 0xFFFF00FF.toInt()
                    },
                )
            }
        }
        return Result(counts.toMap(), firstFailure)
    }

    private fun writeResult(
        output: Path,
        scale: Int,
        result: Result,
        evidence: List<Path>,
    ) {
        val values = linkedMapOf("schemaVersion" to "1", "scale" to scale.toString())
        result.counts.forEach { (kind, count) -> values["pixels.${kind.name}"] = count.toString() }
        evidence.forEach { path -> values["evidence.${path.fileName}.sha256"] = MinecraftFontParityFixture.sha256(Files.readAllBytes(path)) }
        Files.writeString(output.resolve("font-native-$scale-comparison.properties"), values.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" })
    }

    /**
     * Detached counts cover every physical pixel, including exact matches and every unclassified failure.
     */
    data class Result(
        val counts: Map<MinecraftFontGpuComparison.Difference, Int>,
        val firstFailure: String,
    ) {
        /**
         * Number of final pixels that differ, including explicitly verified device effects.
         */
        val differences: Int get() = counts.filterKeys { it != MinecraftFontGpuComparison.Difference.Exact }.values.sum()

        /**
         * Number of differences without an accepted independent GPU proof.
         */
        val unverified: Int get() = counts.filterKeys { it.accepted.not() }.values.sum()
    }
}
