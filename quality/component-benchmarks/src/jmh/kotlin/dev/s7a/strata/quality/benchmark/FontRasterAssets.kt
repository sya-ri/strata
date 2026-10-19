package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Deterministic source preparation and independent pixel inputs, never called by a sampled operation.
 */
internal object FontRasterAssets {
    /**
     * Returns a repeated straight-ARGB pattern including hidden RGB and every relevant alpha boundary.
     */
    internal fun pixel(
        x: Int,
        y: Int,
    ): Int =
        when ((x + y) % 6) {
            0 -> 0x00123456
            1 -> 0x01020304
            2 -> 0x7fabcdef
            3 -> 0x80123456.toInt()
            4 -> 0xfe765432.toInt()
            else -> 0xff102030.toInt()
        }

    /**
     * Creates the admitted immutable source sheet before any engine operation.
     */
    internal fun sheet(axis: Int): DrawImage = createDrawImage(IntSize(axis, axis), ::pixel)

    /**
     * Prepares untimed inputs in memory, or requires each declared frozen PNG when a manifest is supplied.
     */
    internal fun png(axis: Int): ByteArray {
        if (System.getProperty("strata.performance.fixtureInputs") == null) return encodePng(axis)
        val label = "font-raster-png-$axis"
        val input = checkNotNull(JmhFixtureSelection.inputs()[label]) { "Missing frozen font PNG input: $label" }
        return Files.readAllBytes(input)
    }

    /**
     * Writes the complete external PNG set to a fresh directory for immutable paired measurement inputs.
     */
    internal fun preparePngs(destination: Path) {
        require(Files.exists(destination).not()) { "Frozen font PNG inputs already exist" }
        Files.createDirectories(destination)
        listOf(32, 256, 1024).forEach { axis -> Files.write(destination.resolve("png-$axis.png"), encodePng(axis)) }
    }

    /**
     * Loads the exact one-cell bitmap provider while keeping PNG preparation outside sampling.
     */
    internal fun bitmap(axis: Int): MinecraftFontSnapshot =
        snapshot(
            """{"type":"bitmap","file":"strata_benchmark:font/cell.png","height":$axis,"ascent":${axis - 1},"chars":["A"]}""",
            "assets/strata_benchmark/textures/font/cell.png" to encodePng(axis),
        )

    /**
     * Loads a sixteen-row hexadecimal glyph with ink at both inclusive edges.
     */
    internal fun unihex(width: Int): MinecraftFontSnapshot {
        val row = "%0${width / 4}X".format(Locale.ROOT, (1L shl (width - 1)) or 1L)
        val archive =
            ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("glyphs.hex").apply { time = 0 })
                    zip.write("0041:${row.repeat(16)}\n".toByteArray(Charsets.US_ASCII))
                    zip.closeEntry()
                }
                output.toByteArray()
            }
        return snapshot("""{"type":"unihex","hex_file":"strata_benchmark:font/glyphs.zip","size_overrides":[]}""", "assets/strata_benchmark/font/glyphs.zip" to archive)
    }

    /**
     * Uses the same independent modern font contract as the existing provider corpus.
     */
    internal fun compatibility(rasterizer: MinecraftTrueTypeRasterizer): MinecraftFontCompatibility = MinecraftFontCompatibility(rasterizer, 84, fractionalUnihexAdvance = true, rejectMalformedOverlayMetadata = true)

    private fun snapshot(
        provider: String,
        file: Pair<String, ByteArray>,
    ): MinecraftFontSnapshot =
        MinecraftFontSnapshot
            .load(
                listOf(MinecraftMemoryFontAssetSource("font-raster-ownership-v1", mapOf("assets/minecraft/font/default.json" to """{"providers":[$provider]}""".toByteArray(Charsets.UTF_8), file))),
                compatibility(MinecraftTrueTypeRasterizer.FreeType),
            ).also { check(it.diagnostics.isEmpty()) { "Font raster fixture preparation failed: ${it.diagnostics}" } }

    /**
     * Fingerprints prepared bytes and independently extracted complete raster pixels outside timing.
     */
    internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun encodePng(axis: Int): ByteArray {
        val image = BufferedImage(axis, axis, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until axis) {
            for (x in 0 until axis) image.setRGB(x, y, pixel(x, y))
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "PNG", output)) { "The fixture PNG encoder is unavailable" }
            output.toByteArray()
        }
    }
}
