package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer

/**
 * Exact original ZIP rows and deterministic ordered documents, detached before any timed operation.
 */
internal object UnihexOverrideAssets {
    /**
     * Reads only the original immutable packaged ZIP, never vanilla or system font inputs.
     */
    internal fun bytes(): ByteArray = checkNotNull(javaClass.getResourceAsStream("/font-unihex-225/ordered.zip")).use { it.readBytes() }

    /**
     * Creates declaration-order documents separately from actual snapshot loading.
     */
    internal fun source(scenario: UnihexOverrideScenario): MinecraftMemoryFontAssetSource {
        val records = JsonArray()
        val first = if (scenario == UnihexOverrideScenario.Supplementary) 0x1F600 else 65
        val selected =
            when (scenario) {
                UnihexOverrideScenario.First, UnihexOverrideScenario.Single -> 0
                UnihexOverrideScenario.Middle -> scenario.ranges / 2
                else -> scenario.ranges - 1
            }
        repeat(scenario.ranges) { position ->
            val hit = position == selected && (scenario in setOf(UnihexOverrideScenario.None, UnihexOverrideScenario.Absent)).not()
            val overlap = scenario == UnihexOverrideScenario.Overlap && position in setOf(3, 5, 9)
            records.add(
                JsonObject().apply {
                    addProperty("from", String(Character.toChars(if (hit || overlap) first else 10_000 + position * 2)))
                    addProperty("to", String(Character.toChars(if (hit || overlap) first + 63 else 10_001 + position * 2)))
                    addProperty("left", if (scenario == UnihexOverrideScenario.Padding) -1 else if (overlap) 1 else 0)
                    addProperty("right", if (scenario == UnihexOverrideScenario.Padding) 8 else if (overlap) position else 7)
                },
            )
        }
        val provider =
            JsonObject().apply {
                addProperty("type", "unihex")
                addProperty("hex_file", "strata_benchmark:font/ordered.zip")
                add("size_overrides", records)
            }
        val document = JsonObject().apply { add("providers", JsonArray().apply { add(provider) }) }
        return MinecraftMemoryFontAssetSource(
            "unihex-ordered-${scenario.name}-v1",
            mapOf("assets/minecraft/font/default.json" to document.toString().toByteArray(Charsets.UTF_8), "assets/strata_benchmark/font/ordered.zip" to bytes()),
        )
    }

    /**
     * Performs the complete public snapshot load over already prepared immutable source bytes.
     */
    internal fun load(source: MinecraftMemoryFontAssetSource): MinecraftFontSnapshot = MinecraftFontSnapshot.load(listOf(source), FontRasterAssets.compatibility(MinecraftTrueTypeRasterizer.FreeType)).also { check(it.diagnostics.isEmpty()) }

    /**
     * Fixed query order; no string construction occurs during public glyph measurements.
     */
    internal fun scalars(scenario: UnihexOverrideScenario): List<Int> {
        val first =
            when (scenario) {
                UnihexOverrideScenario.Absent -> 0x20000
                UnihexOverrideScenario.Supplementary -> 0x1F600
                else -> 65
            }
        return (first until first + scenario.queries).toList()
    }
}
