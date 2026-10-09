package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Immutable provider documents and registered original CC0/PNG inputs prepared outside timed operations.
 */
internal object FontFallbackAssets {
    /**
     * Creates the complete ordered provider chain without loading game or system fonts.
     */
    internal fun snapshot(
        workload: FontFallbackWorkload,
        depth: Int,
    ): MinecraftFontSnapshot {
        val files = linkedMapOf<String, ByteArray>()
        val glyphs = JsonObject().apply { for (scalar in 65..128) addProperty(scalar.toChar().toString(), 7) }
        val hit = """{"type":"space","advances":$glyphs}"""
        val miss = """{"type":"space","advances":{"日":3}}"""
        val terminal =
            when (workload) {
                FontFallbackWorkload.First, FontFallbackWorkload.Late, FontFallbackWorkload.FilteredLate -> {
                    hit
                }

                FontFallbackWorkload.Missing -> {
                    miss
                }

                FontFallbackWorkload.StbLate, FontFallbackWorkload.FreeTypeLate -> {
                    files["assets/strata_benchmark/font/fixture.ttf"] = Files.readAllBytes(Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))))
                    """{"type":"ttf","file":"strata_benchmark:fixture.ttf","size":11,"oversample":2}"""
                }

                FontFallbackWorkload.AtlasRejected -> {
                    files["assets/strata_benchmark/textures/font/rejected.png"] = FontRasterAssets.png(257)
                    """{"type":"bitmap","file":"strata_benchmark:font/rejected.png","height":257,"ascent":256,"chars":["A"]}"""
                }

                FontFallbackWorkload.Poisoned -> {
                    files["assets/strata_benchmark/font/invalid.ttf"] = byteArrayOf(0)
                    """{"type":"ttf","file":"strata_benchmark:invalid.ttf","filter":{"uniform":true}}"""
                }
            }
        val providers =
            List(depth) { index ->
                when {
                    index == depth - 1 -> terminal
                    workload == FontFallbackWorkload.First -> hit
                    workload == FontFallbackWorkload.FilteredLate -> """{"type":"space","advances":$glyphs,"filter":{"uniform":true}}"""
                    else -> miss
                }
            }
        files["assets/minecraft/font/default.json"] = """{"providers":[${providers.joinToString(",")}]}""".toByteArray(Charsets.UTF_8)
        return MinecraftFontSnapshot.load(listOf(MinecraftMemoryFontAssetSource("font-fallback-v1", files)), compatibility(workload)).also { check(it.diagnostics.isEmpty()) }
    }

    /**
     * Uses the actual declared native generation and immutable modern provider-filter capabilities.
     */
    internal fun compatibility(workload: FontFallbackWorkload): MinecraftFontCompatibility = FontRasterAssets.compatibility(if (workload == FontFallbackWorkload.StbLate) MinecraftTrueTypeRasterizer.Stb else MinecraftTrueTypeRasterizer.FreeType)
}
