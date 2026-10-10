package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Reads the committed detached bytes outside sampling; frozen files are registered with the shared collector.
 * The scalar oracle below uses the original shift expression, independently of the changed runtime helper.
 */
internal object UnihexBoundsAssets {
    /**
     * Default provider addressed by every public consumer operation.
     */
    internal val font: ResourceId = ResourceId("minecraft", "default")

    /**
     * Reads a packed record from the immutable geometry table, without runtime decoding or bit scans.
     */
    internal fun rows(fixture: UnihexBoundsFixture): LongArray {
        val line = bytes("fixtures.tsv").toString(Charsets.UTF_8).lineSequence().single { it.substringBefore('\t') == fixture.name }
        return line.split('\t')[2].split(',').map { it.toLong(16) }.toLongArray()
    }

    /**
     * Creates a copied public asset source from the exact original ZIP and font document.
     */
    internal fun source(
        fixture: UnihexBoundsFixture,
        document: String = "${fixture.name}.json",
    ): MinecraftMemoryFontAssetSource =
        MinecraftMemoryFontAssetSource(
            "unihex-bounds-v1",
            mapOf(
                "assets/minecraft/font/default.json" to bundle(document),
                "assets/strata_benchmark/font/glyphs.zip" to bundle("${fixture.name}.zip"),
            ),
        )

    /**
     * Loads the public snapshot with exact immutable release capabilities and default input budgets.
     */
    internal fun snapshot(
        fixture: UnihexBoundsFixture,
        fractional: Boolean,
        document: String = "${fixture.name}.json",
    ): MinecraftFontSnapshot = load(source(fixture, document), fractional)

    /**
     * Keeps source loading as a complete explicit operation rather than hidden benchmark setup.
     */
    internal fun load(
        source: MinecraftMemoryFontAssetSource,
        fractional: Boolean,
    ): MinecraftFontSnapshot =
        MinecraftFontSnapshot
            .load(listOf(source), compatibility(fractional))
            .also { check(it.diagnostics.isEmpty()) { "Frozen Unihex fixture failed: ${it.diagnostics}" } }

    /**
     * The two real advance capabilities are crossed independently with the same source bytes.
     */
    internal fun compatibility(fractional: Boolean): MinecraftFontCompatibility =
        MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84, fractionalUnihexAdvance = fractional, rejectMalformedOverlayMetadata = true)

    /**
     * Reads a registered external original or the same committed development resource outside timing.
     */
    internal fun bytes(name: String): ByteArray {
        if (System.getProperty("strata.performance.fixtureInputs") != null) {
            val path = checkNotNull(JmhFixtureSelection.inputs()["unihex-bounds-$name"]) { "Missing frozen input: $name" }
            return Files.readAllBytes(path)
        }
        return checkNotNull(javaClass.getResourceAsStream("/unihex-bounds/$name")).use { it.readBytes() }
    }

    /**
     * Extracts one exact stored bundle member; this work belongs only to source preparation.
     */
    internal fun bundle(name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes("assets.zip"))).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.name == name) return archive.readBytes()
                archive.closeEntry()
                entry = archive.nextEntry
            }
        }
        error("Missing frozen asset: $name")
    }

    /**
     * Original unsigned Long per-pixel reference, including the native column guard and JVM shift masking.
     */
    internal fun scalarInk(
        width: Int,
        rows: LongArray,
        x: Int,
        y: Int,
    ): Boolean = x in 0..31 && ((rows[y] shl (32 - width)) ushr (31 - x)) and 1L != 0L

    /**
     * Original scalar extrema; no new runtime helper or leading/trailing-bit operation enters this oracle.
     */
    internal fun scalarBounds(
        width: Int,
        rows: LongArray,
    ): IntRange {
        var left = width
        var right = -1
        for (y in rows.indices) {
            for (x in 0 until width) {
                if (scalarInk(width, rows, x, y)) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                }
            }
        }
        return if (right < left) 0..width else left..right
    }
}
