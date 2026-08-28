package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontGlyphEvidence
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontGpuImageComparison
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontGpuReceipt
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityChecks
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Compares independent native artifacts with images generated in a separate CPU-only JVM.
 * Missing, mismatched, or incomplete inputs fail unconditionally; metrics and raw glyph pixels remain exact.
 * Final native images require independently re-evaluated float evidence and bounded device-effect classifications.
 * The aggregate Gradle gate recreates native runs first, while individual comparison tasks consume explicitly required existing artifacts.
 */
@Tag("font-offline-parity")
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftOfflineFontParityTest {
    @Test
    fun `fresh offline resource rendering matches complete native evidence at every physical scale`() {
        val nativeRoot = Path.of(property("strata.fontNativeOutput"))
        val offlineRoot = Path.of(property("strata.fontOfflineOutput"))
        val output = Path.of(property("strata.fontComparisonOutput"))
        Files.createDirectories(output)
        val result = output.resolve("font-offline-parity.properties")
        Files.deleteIfExists(result)
        val nativePath = nativeRoot.resolve("font-parity.properties")
        val offlinePath = offlineRoot.resolve("font-offline.properties")
        val native = readMetadata(nativePath)
        val offline = readMetadata(offlinePath)
        val version = property("strata.minecraftVersion")
        assertEquals(version, native["minecraft.version"])
        assertEquals(version, offline["minecraft.version"])
        verifyInputs(nativeRoot, native, offline)
        val snapshot = MinecraftFontParityFixture.snapshot()
        val glyphs = nativeRoot.resolve("font-native-glyphs.properties")
        assertEquals(hash(glyphs), native["image.${glyphs.fileName}.sha256"])
        MinecraftFontGlyphEvidence.verify(snapshot, glyphs)
        val profile = MinecraftFontParityFixture.profile(snapshot)
        val commands = MinecraftFontParityFixture.commands(profile)
        val receipt =
            linkedMapOf(
                "minecraft.version" to version,
                "offline.nativePixels" to checkNotNull(native["native.pixels"]),
                "offline.nativeLayout" to "exact",
                "offline.nativeGlyphs" to "exact",
                "offline.guiScales" to "1,2,3",
                "native.metadata.sha256" to hash(nativePath),
                "offline.metadata.sha256" to hash(offlinePath),
            )
        for (scale in 1..3) {
            val offlineImage = offlineRoot.resolve("font-offline-$scale.png")
            val offlineHash = hash(offlineImage)
            assertEquals(offlineHash, offline["image.${offlineImage.fileName}.sha256"])
            receipt["offline.${offlineImage.fileName}.sha256"] = offlineHash
            val fresh = MinecraftFontParityFixture.render(profile, scale)
            val freshPath = output.resolve("font-offline-$scale.png")
            Files.write(freshPath, fresh.encodePng())
            verifyPixels(offlineImage, freshPath, output.resolve("font-worker-$scale-diff.png"))
            val nativeImage = nativeRoot.resolve("font-native-$scale.png")
            assertEquals(hash(nativeImage), native["image.${nativeImage.fileName}.sha256"])
            val comparison = MinecraftFontGpuImageComparison.compare(nativeImage, fresh, freshPath, commands, scale, output)
            assertEquals(0, comparison.unverified, comparison.firstFailure)
            receipt["loaded.${nativeImage.fileName}.sha256"] = hash(nativeImage)
            receipt["offline.gpuProof.$scale.sha256"] = hash(output.resolve("font-native-$scale-comparison.properties"))
            for (kind in listOf("headless", "fabric")) {
                val nativeImage = nativeRoot.resolve("font-$kind-$scale.png")
                val nativeHash = hash(nativeImage)
                assertEquals(nativeHash, native["image.${nativeImage.fileName}.sha256"])
                verifyPixels(nativeImage, offlineImage, output.resolve("font-$kind-$scale-diff.png"))
                receipt["loaded.${nativeImage.fileName}.sha256"] = nativeHash
            }
        }
        Files.writeString(result, receipt.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" })
    }

    private fun verifyInputs(
        nativeRoot: Path,
        native: Map<String, String>,
        offline: Map<String, String>,
    ) {
        val compatibility = MinecraftFontParityFixture.target.compatibility
        MinecraftFontParityChecks.verifyInputMetadata(compatibility, native)
        MinecraftFontParityChecks.verifyInputMetadata(compatibility, offline)
        assertEquals("exact", native["native.metrics"])
        assertEquals("1", native["native.gpuProofSchema"])
        val proofFiles = MinecraftFontGpuReceipt.verify(nativeRoot, MinecraftFontParityFixture.target)
        val gpuDifferences = MinecraftFontGpuReceipt.differences(nativeRoot)
        assertEquals(if (gpuDifferences == 0) "exact" else "verified-gpu-differences", native["native.pixels"])
        assertEquals(gpuDifferences.toString(), native["native.gpuDifferences"])
        assertEquals("1,2,3", native["native.guiScales"])
        assertEquals("1,2,3", offline["offline.guiScales"])
        assertEquals("original-resource-files", offline["offline.input"])
        assertEquals(hash(nativeRoot.resolve("font-parity.txt")), native["native.receipt.sha256"])
        proofFiles.forEach { path -> assertEquals(hash(path), native["image.${path.fileName}.sha256"]) }
        val inputKeys = offline.keys.filter { it.startsWith("input.") || it.startsWith("compatibility.") }.toSet()
        assertEquals(inputKeys, native.keys.filter { it.startsWith("input.") || it.startsWith("compatibility.") }.toSet())
        val sharedKeys = inputKeys + listOf("scene.sha256", "runtime.lwjgl", "runtime.icu", "runtime.javaFeature", "runtime.osName", "runtime.osArch")
        sharedKeys.forEach { key ->
            assertEquals(checkNotNull(offline[key]) { "Offline metadata is missing $key." }, native[key], key)
        }
        val layoutKeys =
            MinecraftFontParityFixture.rows.indices
                .map { "layout.$it" }
                .toSet()
        assertEquals(layoutKeys, native.keys.filter { it.startsWith("layout.") }.toSet())
        assertEquals(layoutKeys, offline.keys.filter { it.startsWith("layout.") }.toSet())
        layoutKeys.forEach { key -> assertEquals(offline[key], native[key], "Independent native and offline $key") }
    }

    private fun readMetadata(path: Path): Map<String, String> {
        val result = linkedMapOf<String, String>()
        readBytes(path).toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            val separator = line.indexOf('=')
            check(0 < separator) { "Malformed font evidence metadata in $path." }
            val key = line.substring(0, separator)
            check(result.put(key, line.substring(separator + 1)) == null) { "Duplicate font evidence key $key in $path." }
        }
        return result
    }

    private fun verifyPixels(
        expectedPath: Path,
        actualPath: Path,
        diffPath: Path,
    ) {
        val expected = checkNotNull(ImageIO.read(expectedPath.toFile())) { "Invalid native font PNG: $expectedPath" }
        val actual = checkNotNull(ImageIO.read(actualPath.toFile())) { "Invalid offline font PNG: $actualPath" }
        assertEquals(expected.width, actual.width, "Font scene physical width")
        assertEquals(expected.height, actual.height, "Font scene physical height")
        val diff = BufferedImage(expected.width, expected.height, BufferedImage.TYPE_INT_ARGB)
        var differences = 0
        var first = ""
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val native = expected.getRGB(x, y)
                val offline = actual.getRGB(x, y)
                if (native == offline) {
                    diff.setRGB(x, y, 0xFF202020.toInt())
                } else {
                    if (differences == 0) first = "($x,$y): native=${native.toUInt().toString(16)}, offline=${offline.toUInt().toString(16)}"
                    differences++
                    diff.setRGB(x, y, 0xFFFF00FF.toInt())
                }
            }
        }
        if (differences == 0) Files.deleteIfExists(diffPath) else check(ImageIO.write(diff, "png", diffPath.toFile()))
        assertEquals(0, differences, "$expectedPath versus $actualPath: first $first; difference image $diffPath")
    }

    private fun hash(path: Path): String = MinecraftFontParityFixture.sha256(readBytes(path))

    private fun readBytes(path: Path): ByteArray {
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Required font evidence is missing: $path" }
        return Files.readAllBytes(path)
    }

    private fun property(name: String): String = checkNotNull(System.getProperty(name)) { "The offline font comparison requires $name." }
}
