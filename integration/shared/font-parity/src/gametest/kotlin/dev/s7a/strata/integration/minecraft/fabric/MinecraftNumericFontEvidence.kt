package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Exact raw-glyph and signed-width evidence for isolated numeric providers.
 * Native callbacks invoke the game while the portable path opens the original font bytes independently.
 * All native faces are scoped to the calling thread; files contain detached observations and hashes only.
 * No metrics, source dimensions, texels, or signed widths receive a tolerance.
 */
internal object MinecraftNumericFontEvidence {
    /**
     * Records all declared provider/code-point observations and actual signed native widths.
     * The supplied callbacks must belong to an independently loaded native session or a fresh portable engine.
     */
    fun capture(
        compatibility: MinecraftFontCompatibility,
        glyph: (MinecraftNumericFontFixture.Case, Int) -> MinecraftNumericFontGlyph,
        width: (MinecraftNumericFontFixture.Row) -> Int,
    ): Map<String, String> =
        MinecraftNumericFontFixture.metadata(compatibility).toMutableMap().apply {
            MinecraftNumericFontFixture.Case.entries.forEach { case ->
                MinecraftNumericFontFixture.codePoints.forEach { codePoint ->
                    putAll(glyph(case, codePoint).entries(case, codePoint))
                    val text = String(Character.toChars(codePoint))
                    val row = MinecraftNumericFontFixture.Row(listOf(MinecraftNumericFontFixture.Segment(case.id, text)), TextStyle.Normal, 0)
                    put("width.glyph.${case.name}.${codePoint.toString(16)}", width(row).toString())
                }
            }
            MinecraftNumericFontFixture.rows.forEachIndexed { index, row -> put("width.row.$index", width(row).toString()) }
        }

    /**
     * Recomputes raw backend observations and effective engine widths without game classes, native screenshots, or a graphics context.
     */
    fun portable(compatibility: MinecraftFontCompatibility): Map<String, String> {
        val raw = linkedMapOf<Pair<MinecraftNumericFontFixture.Case, Int>, MinecraftNumericFontGlyph>()
        val bytes = MinecraftFontParityFixture.bytes("assets/strata_font_test/font/strata-test.ttf")
        LwjglMinecraftFontBackendFactory.open(compatibility).use { backend ->
            MinecraftNumericFontFixture.Case.entries.forEach { case ->
                backend.openTrueType(bytes, case.settings()).use { face ->
                    MinecraftNumericFontFixture.codePoints.forEach { codePoint -> raw[case to codePoint] = MinecraftNumericFontGlyph.from(face.glyph(codePoint)) }
                }
            }
        }
        MinecraftFontEngine(MinecraftNumericFontFixture.snapshot(compatibility), LwjglMinecraftFontBackendFactory).use { engine ->
            return capture(compatibility, { case, codePoint -> checkNotNull(raw[case to codePoint]) }) { row ->
                var advance = 0f
                row.segments.forEach { segment ->
                    segment.text.codePoints().forEachOrdered { codePoint -> advance += engine.glyph(segment.font, codePoint).advance }
                }
                compatibility.roundedWidth(advance)
            }
        }
    }

    /**
     * Requires every input, numeric observation, effective width, and comparable runtime contract to match exactly.
     * Package-manifest decoration and full Java patch spelling are recorded but do not select numeric behavior.
     */
    fun verify(
        expected: Map<String, String>,
        actual: Map<String, String>,
    ) {
        val descriptive = setOf("runtime.lwjglDetail", "runtime.javaVersion")
        val expectedValues = expected.filterKeys { (it in descriptive).not() }
        val actualValues = actual.filterKeys { (it in descriptive).not() }
        val differences = (expectedValues.keys + actualValues.keys).filter { expectedValues[it] != actualValues[it] }
        check(differences.isEmpty()) {
            differences.take(12).joinToString("\n", prefix = "Independent numeric font evidence differs:\n") { key -> "$key: native=${expectedValues[key]}, portable=${actualValues[key]}" }
        }
    }

    /**
     * Writes one complete detached observation file, replacing only this owned build artifact.
     */
    fun write(
        path: Path,
        values: Map<String, String>,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, values.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" })
    }

    /**
     * Reads a required observation or receipt and rejects missing files, duplicate keys, and malformed records.
     */
    fun read(path: Path): Map<String, String> {
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Required numeric font evidence is missing: $path" }
        val result = linkedMapOf<String, String>()
        Files.readAllLines(path).filter { it.isNotEmpty() }.forEach { line ->
            val separator = line.indexOf('=')
            check(0 < separator) { "Malformed numeric font evidence: $path" }
            check(result.put(line.substring(0, separator), line.substring(separator + 1)) == null) { "Duplicate numeric font evidence key: $path" }
        }
        return result
    }

    /**
     * Issues a current native receipt only after exact numeric evidence and every independently verified float proof succeed.
     * Fabric captures are additional required artifacts; their exact comparisons occur in the loaded caller before this method.
     */
    fun writeReceipt(output: Path) {
        verify(read(output.resolve("numeric-native.properties")), read(output.resolve("numeric-portable.properties")))
        verifyFabric(output)
        val proof = MinecraftFontGpuReceipt.verify(output, MinecraftFontParityFixture.target)
        val files = proof + listOf("numeric-native.properties", "numeric-portable.properties").map(output::resolve) + (1..3).map { output.resolve("font-fabric-$it.png") }
        val values =
            linkedMapOf(
                "minecraft.version" to MinecraftFontParityFixture.target.version,
                "numeric.rawGlyphs" to Agreement.Exact.value,
                "numeric.signedWidths" to Agreement.Exact.value,
                "numeric.providerProbes" to (MinecraftNumericFontFixture.Case.entries.size * MinecraftNumericFontFixture.codePoints.size).toString(),
                "numeric.rows" to MinecraftNumericFontFixture.rows.size.toString(),
                "numeric.guiScales" to "1,2,3",
                "numeric.gpuDifferences" to MinecraftFontGpuReceipt.differences(output).toString(),
            )
        files.distinct().forEach { path -> values["evidence.${path.fileName}.sha256"] = hash(path) }
        write(output.resolve("numeric-parity.properties"), values)
    }

    /**
     * Rechecks current receipt input hashes and all float proofs before a separate offline process consumes native evidence.
     */
    fun verifyReceipt(output: Path) {
        val values = read(output.resolve("numeric-parity.properties"))
        check(values["minecraft.version"] == MinecraftFontParityFixture.target.version)
        check(Agreement.entries.singleOrNull { it.value == values["numeric.rawGlyphs"] } == Agreement.Exact)
        check(Agreement.entries.singleOrNull { it.value == values["numeric.signedWidths"] } == Agreement.Exact)
        check(values["numeric.providerProbes"] == (MinecraftNumericFontFixture.Case.entries.size * MinecraftNumericFontFixture.codePoints.size).toString())
        check(values["numeric.rows"] == MinecraftNumericFontFixture.rows.size.toString())
        check(values["numeric.guiScales"]?.split(',')?.map(String::toIntOrNull) == (1..3).toList())
        val proof = MinecraftFontGpuReceipt.verify(output, MinecraftFontParityFixture.target)
        check(values["numeric.gpuDifferences"] == MinecraftFontGpuReceipt.differences(output).toString())
        val files = proof + listOf("numeric-native.properties", "numeric-portable.properties").map(output::resolve) + (1..3).map { output.resolve("font-fabric-$it.png") }
        files.distinct().forEach { path -> check(values["evidence.${path.fileName}.sha256"] == hash(path)) { "Numeric font proof artifact changed: $path" } }
        verify(read(output.resolve("numeric-native.properties")), read(output.resolve("numeric-portable.properties")))
        verifyFabric(output)
    }

    /**
     * Hashes a required current artifact without following a missing-file fallback.
     */
    fun hash(path: Path): String {
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Required numeric font artifact is missing: $path" }
        return MinecraftFontParityFixture.sha256(Files.readAllBytes(path))
    }

    private fun verifyFabric(output: Path) {
        for (scale in 1..3) {
            val native = checkNotNull(ImageIO.read(output.resolve("font-fabric-$scale.png").toFile()))
            val portable = checkNotNull(ImageIO.read(output.resolve("font-headless-$scale.png").toFile()))
            val viewport = MinecraftFontParityFixture.viewport
            check(native.width == viewport.width * scale && native.height == viewport.height * scale)
            check(native.width == portable.width && native.height == portable.height)
            val expected = native.getRGB(0, 0, native.width, native.height, null, 0, native.width)
            val actual = portable.getRGB(0, 0, portable.width, portable.height, null, 0, portable.width)
            check(expected.contentEquals(actual)) { "Numeric Fabric/headless pixels differ at GUI scale $scale." }
        }
    }

    private enum class Agreement(
        val value: String,
    ) {
        Exact("exact"),
    }
}
