package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Stable source preparation outside sampling; only explicit snapshot-load operations reload these owned bytes.
 * Native input uses the repository's original CC0 geometric font, registered separately in the evidence manifest.
 */
internal object FontPerformanceAssets {
    /**
     * Returns deterministic provider bytes without game, operating-system font or network acquisition.
     */
    internal fun source(workload: FontWorkload): MinecraftMemoryFontAssetSource {
        if (workload in setOf(FontWorkload.BitmapCached, FontWorkload.BitmapUncached)) return ComponentFontAssets.source()
        val files = linkedMapOf<String, ByteArray>()
        val native = workload in setOf(FontWorkload.StbCached, FontWorkload.FreeTypeCached, FontWorkload.StbFaces1, FontWorkload.FreeTypeFaces16)
        val count = if (workload in setOf(FontWorkload.StbFaces1, FontWorkload.FreeTypeFaces16)) 17 else 1
        when {
            native -> {
                files["assets/strata_benchmark/font/fixture.ttf"] = Files.readAllBytes(Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))))
                repeat(count) { index ->
                    val name = if (index == 0) "assets/minecraft/font/default.json" else "assets/strata_benchmark/font/face_$index.json"
                    files[name] = document("""{"type":"ttf","file":"strata_benchmark:fixture.ttf","size":${11 + index},"oversample":2}""")
                }
            }

            workload in setOf(FontWorkload.ReferenceDepth128, FontWorkload.ReferenceDepth129) -> {
                val depth = if (workload == FontWorkload.ReferenceDepth128) 128 else 129
                repeat(depth) { index ->
                    val name = if (index == 0) "assets/minecraft/font/default.json" else "assets/strata_benchmark/font/ref_$index.json"
                    val provider = if (index + 1 == depth) """{"type":"space","advances":{"A":7}}""" else """{"type":"reference","id":"strata_benchmark:ref_${index + 1}"}"""
                    files[name] = document(provider)
                }
            }

            else -> {
                files["assets/minecraft/font/default.json"] = document("""{"type":"unihex","hex_file":"strata_benchmark:font/glyphs.zip","size_overrides":[]}""")
                files["assets/strata_benchmark/font/glyphs.zip"] = hexArchive()
            }
        }
        return MinecraftMemoryFontAssetSource("font-performance-${workload.name}-v1", files)
    }

    /**
     * Uses the real provider loader and the declared modern native generation.
     */
    internal fun snapshot(
        workload: FontWorkload,
        source: MinecraftMemoryFontAssetSource,
    ): MinecraftFontSnapshot = MinecraftFontSnapshot.load(listOf(source), compatibility(workload))

    /**
     * Chooses the actual native rasterizer without another native generation on the classpath.
     */
    internal fun compatibility(workload: FontWorkload): MinecraftFontCompatibility = MinecraftFontCompatibility(if (workload in setOf(FontWorkload.StbCached, FontWorkload.StbFaces1)) MinecraftTrueTypeRasterizer.Stb else MinecraftTrueTypeRasterizer.FreeType, 84, fractionalUnihexAdvance = true, rejectMalformedOverlayMetadata = true)

    private fun document(provider: String): ByteArray = """{"providers":[$provider]}""".toByteArray(Charsets.UTF_8)

    private fun hexArchive(): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("glyphs.hex").apply { time = 0 })
                zip.write((32..127).joinToString("\n", postfix = "\n") { scalar -> "%04X:%s".format(Locale.ROOT, scalar, "7E".repeat(16)) }.toByteArray(Charsets.US_ASCII))
                zip.closeEntry()
            }
            output.toByteArray()
        }
}
