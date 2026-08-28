package dev.s7a.strata.integration.minecraft.fabric

import com.ibm.icu.util.VersionInfo
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture.FontCase
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture.LEFT
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture.Row
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture.definition
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture.evidenceMetadata
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture.rows
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture.sha256
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture.target
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture.viewport
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.lwjgl.Version
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Requires independent native metrics, glyphs, layout, and pixel evidence for the immutable font fixture.
 * Checks open and close their own portable engines and hosts, and retain no native provider or source object.
 * Missing, malformed, or mismatched evidence fails before an acceptance receipt is written.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftFontParityChecks {
    /**
     * Binds saved evidence to the current resource bytes, scene, compiled target contract, and runtime dependency generation.
     * Metadata may contain additional receipt fields, but missing or changed input and compatibility fields fail.
     * Detailed JVM and LWJGL build labels remain diagnostic; their feature and library versions are compared.
     */
    fun verifyInputMetadata(
        compatibility: MinecraftFontCompatibility,
        metadata: Map<String, String>,
    ) {
        val current = evidenceMetadata(compatibility) - setOf("runtime.lwjglDetail", "runtime.javaVersion")
        val recorded = metadata.filterKeys { it in current || it.startsWith("input.") || it.startsWith("compatibility.") }
        check(current == recorded) {
            val changed = (current.keys + recorded.keys).filter { current[it] != recorded[it] }
            "Font evidence does not describe the current inputs: ${changed.joinToString()}"
        }
    }

    /**
     * Compares public Text layout to native logical Component measurements independently of shaped display order.
     * Returns detached native dimensions and the shared draw origins for the later offline-process comparison.
     */
    fun verifyLayout(
        profile: MinecraftUiProfile,
        nativeSize: (Row) -> IntSize,
    ): Map<String, String> {
        val measurements = linkedMapOf<String, String>()
        createMinecraftUiHost(definition(), profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            val text = host.frame(viewport).semantics.filter { it.semantics.role == SemanticsRole.Text }
            check(text.size == rows.size) { "The portable font scene did not retain every Text component." }
            rows.zip(text).forEachIndexed { index, (row, entry) ->
                val expected = nativeSize(row)
                check(entry.bounds.width == expected.width && entry.bounds.height == expected.height) {
                    "${row.font} ${row.style} logical size: native=$expected, portable=${entry.bounds.width},${entry.bounds.height}"
                }
                check(entry.bounds.left == LEFT && entry.bounds.top == row.top) { "The portable font scene's text origin differs from the native oracle." }
                measurements["layout.$index"] = "$LEFT,${row.top},${expected.width},${expected.height}"
            }
        }
        return measurements.toMap()
    }

    /**
     * Compares independently loaded native provider metrics and texels to the portable engine.
     * The supplied native callback must load and bake a real game provider and return detached pixels only.
     */
    fun verifyGlyphs(
        snapshot: MinecraftFontSnapshot,
        output: Path,
        native: (FontCase, Int) -> MinecraftFontGlyph,
    ) {
        val failures = mutableListOf<String>()
        val evidence = linkedMapOf<String, String>()
        val report = output.resolve("glyph-mismatches.txt")
        Files.deleteIfExists(report)
        MinecraftFontEngine(snapshot, LwjglMinecraftFontBackendFactory).use { engine ->
            FontCase.entries.filter { it.providerIndex != null }.forEach { font ->
                font.codePoints.forEach { codePoint ->
                    val expected = native(font, codePoint)
                    evidence.putAll(MinecraftFontGlyphEvidence.entries(font, codePoint, expected))
                    val actual = engine.glyph(font.id, codePoint)
                    val context = "${font.id} U+${codePoint.toString(16)}"
                    runCatching { verifyGlyph(expected, actual, context) }.onFailure { failure ->
                        failures += checkNotNull(failure.message)
                        val directory = output.resolve("glyphs")
                        Files.createDirectories(directory)
                        val name = "${font.id.path}-${codePoint.toString(16)}"
                        expected.image?.let { writeGlyphImage(it, directory.resolve("$name-native.png")) }
                        actual.image?.let { writeGlyphImage(it, directory.resolve("$name-headless.png")) }
                    }
                }
            }
        }
        Files.writeString(output.resolve("font-native-glyphs.properties"), evidence.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" })
        if (failures.isNotEmpty()) Files.write(report, failures)
        check(failures.isEmpty()) { failures.joinToString("\n", prefix = "Native font glyph mismatches:\n") }
    }

    /**
     * Writes the candidate PNG before checking every final native pixel, preserving useful evidence on failure.
     */
    fun verifyPixels(
        nativePath: Path,
        portable: HeadlessImage,
        candidatePath: Path,
    ) {
        Files.write(candidatePath, portable.encodePng())
        val native = checkNotNull(ImageIO.read(nativePath.toFile())) { "Could not decode native screenshot: $nativePath" }
        check(native.width == portable.size.width && native.height == portable.size.height) { "Native font viewport differs from the portable viewport." }
        val diff = BufferedImage(native.width, native.height, BufferedImage.TYPE_INT_ARGB)
        var differences = 0
        var first = ""
        for (y in 0 until native.height) {
            for (x in 0 until native.width) {
                val expected = native.getRGB(x, y)
                val actual = portable.argbAt(x, y)
                if (expected != actual) {
                    if (differences == 0) first = "($x,$y): native=${expected.toUInt().toString(16)}, portable=${actual.toUInt().toString(16)}"
                    differences++
                    diff.setRGB(x, y, 0xFFFF00FF.toInt())
                } else {
                    diff.setRGB(x, y, 0xFF202020.toInt())
                }
            }
        }
        val diffPath = nativePath.resolveSibling("${nativePath.fileName.toString().removeSuffix(".png")}-diff.png")
        if (differences == 0) Files.deleteIfExists(diffPath) else check(ImageIO.write(diff, "png", diffPath.toFile()))
        check(differences == 0) { "Native font pixel mismatch: $differences pixels; first $first. See $nativePath, $candidatePath, and $diffPath." }
    }

    /**
     * Writes an acceptance receipt only after exact metrics/texels and complete independently verified GPU classifications succeeded.
     */
    fun writeReceipt(
        output: Path,
        nativeLayout: Map<String, String>,
    ) {
        check(nativeLayout.keys == rows.indices.map { "layout.$it" }.toSet()) { "The native receipt requires every row's independent layout measurement." }
        val proof = MinecraftFontGpuReceipt.verify(output, target)
        val differences = MinecraftFontGpuReceipt.differences(output)
        val files = ((1..3).flatMap { scale -> listOf("font-native-$scale.png", "font-headless-$scale.png", "font-fabric-$scale.png") }.map(output::resolve) + proof + listOf(output.resolve("font-native-glyphs.properties"))).distinct()
        val receipt =
            buildString {
                appendLine("Minecraft ${target.version}")
                appendLine("Loaded LWJGL ${Version.getVersion()}; ICU ${VersionInfo.ICU_VERSION}; Java ${System.getProperty("java.version")}")
                appendLine("Native provider metrics and texels: exact")
                appendLine("Fabric and portable Text pixels: exact at GUI scales 1, 2, 3")
                appendLine("Native final Text pixels: $differences independently verified GPU differences; no unclassified differences")
                appendLine("Candidate input: original fixture resource files; no native glyph capture reused")
                files.forEach { path ->
                    appendLine("${path.fileName}: ${sha256(Files.readAllBytes(path))}")
                }
            }
        Files.writeString(output.resolve("font-parity.txt"), receipt)
        val metadata = evidenceMetadata(target.compatibility).toMutableMap()
        metadata.putAll(nativeLayout)
        metadata["minecraft.version"] = target.version
        metadata["native.metrics"] = "exact"
        metadata["native.pixels"] = if (differences == 0) "exact" else "verified-gpu-differences"
        metadata["native.gpuProofSchema"] = "1"
        metadata["native.gpuDifferences"] = differences.toString()
        metadata["native.guiScales"] = "1,2,3"
        metadata["native.receipt.sha256"] = sha256(receipt.toByteArray())
        files.forEach { path -> metadata["image.${path.fileName}.sha256"] = sha256(Files.readAllBytes(path)) }
        Files.writeString(output.resolve("font-parity.properties"), metadata.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" })
    }

    private fun verifyGlyph(
        expected: MinecraftFontGlyph,
        actual: MinecraftFontGlyph,
        context: String,
    ) {
        check(expected.advance == actual.advance) { "$context advance: native=${expected.advance}, portable=${actual.advance}" }
        check(expected.left == actual.left && expected.top == actual.top && expected.right == actual.right && expected.bottom == actual.bottom) {
            "$context quad: native=(${expected.left},${expected.top},${expected.right},${expected.bottom}), portable=(${actual.left},${actual.top},${actual.right},${actual.bottom})"
        }
        check(expected.boldOffset == actual.boldOffset && expected.shadowOffset == actual.shadowOffset) {
            "$context offsets differ: native=${expected.boldOffset}/${expected.shadowOffset}, portable=${actual.boldOffset}/${actual.shadowOffset}"
        }
        check(expected.channel == actual.channel) { "$context texture channel differs." }
        check(expected.orientation == actual.orientation) { "$context source orientation differs." }
        verifyGlyphPixels(expected.image, actual.image, context)
    }

    private fun writeGlyphImage(
        image: DrawImage,
        path: Path,
    ) {
        val bitmap = BufferedImage(image.size.width, image.size.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.size.height) {
            for (x in 0 until image.size.width) bitmap.setRGB(x, y, image.argbAt(x, y))
        }
        check(ImageIO.write(bitmap, "png", path.toFile()))
    }

    private fun verifyGlyphPixels(
        expected: DrawImage?,
        actual: DrawImage?,
        context: String,
    ) {
        if (expected == null || actual == null) {
            check(expected == null && actual == null) { "$context spacing glyph raster presence differs." }
            return
        }
        check(expected.size == actual.size) { "$context bitmap size: native=${expected.size}, portable=${actual.size}" }
        for (y in 0 until expected.size.height) {
            for (x in 0 until expected.size.width) {
                check(expected.argbAt(x, y) == actual.argbAt(x, y)) {
                    "$context bitmap ($x,$y): native=${expected.argbAt(x, y).toUInt().toString(16)}, portable=${actual.argbAt(x, y).toUInt().toString(16)}"
                }
            }
        }
    }
}
