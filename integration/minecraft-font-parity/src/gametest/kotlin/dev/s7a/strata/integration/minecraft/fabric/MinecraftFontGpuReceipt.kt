package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture.Target
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Validates complete per-scale GPU proof reports before a loaded or offline acceptance receipt can be issued.
 * Input hashes bind each report to its actual current float, precision, native, and portable files.
 * This helper retains detached metadata only and propagates missing, malformed, or unsuccessful evidence as a failure.
 */
internal object MinecraftFontGpuReceipt {
    /**
     * Returns all required proof files after checking scale coverage, classified counts, and every input hash.
     * The compiled target selects whether format-only pipeline declarations also require exact RGBA8 calibration.
     */
    fun verify(
        output: Path,
        target: Target,
    ): List<Path> =
        (1..3)
            .flatMap { scale ->
                val report = output.resolve("font-native-$scale-comparison.properties")
                val values = read(report)
                val schema = Schema.entries.singleOrNull { it.version == checkNotNull(values["schemaVersion"]).toInt() }
                check(schema == Schema.Initial && checkNotNull(values["scale"]).toInt() == scale) { "Unsupported or mismatched native font proof." }
                val counts = MinecraftFontGpuComparison.Difference.entries.associateWith { kind -> checkNotNull(values["pixels.${kind.name}"]).toInt() }
                check(counts.values.all { 0 <= it }) { "Negative native font proof count." }
                val viewport = MinecraftFontParityFixture.viewport
                check(counts.values.sumOf { it.toLong() } == viewport.width.toLong() * viewport.height * scale * scale) { "Native font proof must cover every physical pixel." }
                check(counts.filterKeys { it.accepted.not() }.values.sumOf { it.toLong() } == 0L) { "Native font proof still contains unclassified differences." }
                val evidence = evidence(output, scale)
                evidence.forEach { path ->
                    check(values["evidence.${path.fileName}.sha256"] == MinecraftFontParityFixture.sha256(Files.readAllBytes(path))) { "Native font proof input changed: $path" }
                }
                val calibration =
                    when (target) {
                        Target.LegacyStb, Target.LegacyFreeType -> emptyList()
                        Target.CurrentFreeType -> listOf(verifyCalibration(output, scale))
                    }
                evidence + listOf(report) + calibration
            }.distinct()

    /**
     * Rechecks the actual calibration PNG against the ordinary native capture at the requested physical scale.
     * Required metadata cannot replace this exact comparison; missing, resized, or altered captures fail.
     * The returned file is included in the receipt's hashes and is independently checked again by the offline JVM.
     */
    fun verifyCalibration(
        output: Path,
        scale: Int,
    ): Path {
        require(scale in 1..3) { "Unsupported native font calibration scale." }
        val values = read(output.resolve("native-float-state-$scale.properties"))
        check(checkNotNull(values["pipelineFormatOnly"]).toBooleanStrict()) { "Native float capture changed more than the color target format." }
        val result = Calibration.entries.singleOrNull { it.value == values["rgba8Calibration"] }
        check(result == Calibration.Exact) { "Native float capture lacks exact RGBA8 calibration." }
        val nativePath = output.resolve("font-native-$scale.png")
        val calibrationPath = output.resolve("font-native-calibration-$scale.png")
        check(Files.isRegularFile(nativePath, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(calibrationPath, LinkOption.NOFOLLOW_LINKS)) { "Required native calibration image is missing." }
        val native = checkNotNull(ImageIO.read(nativePath.toFile())) { "Invalid ordinary native capture." }
        val calibration = checkNotNull(ImageIO.read(calibrationPath.toFile())) { "Invalid native calibration capture." }
        val viewport = MinecraftFontParityFixture.viewport
        check(native.width == viewport.width * scale && native.height == viewport.height * scale) { "Native calibration viewport differs from the fixture." }
        check(native.width == calibration.width && native.height == calibration.height) { "Native calibration changed the physical viewport." }
        val expected = native.getRGB(0, 0, native.width, native.height, null, 0, native.width)
        val actual = calibration.getRGB(0, 0, calibration.width, calibration.height, null, 0, calibration.width)
        check(expected.contentEquals(actual)) { "Format-only native pipeline calibration changed RGBA8 pixels." }
        return calibrationPath
    }

    /**
     * Returns a checked count of all allowed final image differences across the three required physical scales.
     */
    fun differences(output: Path): Int =
        (1..3).sumOf { scale ->
            val values = read(output.resolve("font-native-$scale-comparison.properties"))
            MinecraftFontGpuComparison.Difference.entries.filter { it.accepted && it != MinecraftFontGpuComparison.Difference.Exact }.sumOf { kind ->
                checkNotNull(values["pixels.${kind.name}"]).toInt()
            }
        }

    private fun evidence(
        output: Path,
        scale: Int,
    ): List<Path> =
        listOf(
            "font-native-$scale.png",
            "font-headless-$scale.png",
            "font-native-$scale.rgba32f",
            "native-float-state-$scale.properties",
            "font-native-$scale-classifications.tsv",
        ).map(output::resolve)

    private fun read(path: Path): Map<String, String> {
        val values = linkedMapOf<String, String>()
        Files.readAllLines(path).filter { it.isNotEmpty() }.forEach { line ->
            val separator = line.indexOf('=')
            check(0 < separator) { "Malformed native GPU proof: $path" }
            check(values.put(line.substring(0, separator), line.substring(separator + 1)) == null) { "Duplicate native GPU proof key: $path" }
        }
        return values
    }

    private enum class Schema(
        val version: Int,
    ) {
        Initial(1),
    }

    private enum class Calibration(
        val value: String,
    ) {
        Exact("exact"),
    }
}
