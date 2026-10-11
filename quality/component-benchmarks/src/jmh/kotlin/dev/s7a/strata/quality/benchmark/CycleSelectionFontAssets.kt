package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer

/**
 * Frozen detached bitmap resources for enabled/inactive geometry and label/glyph replacement controls.
 * The replacement font moves its bitmap ascent by one pixel while retaining its independently declared font identity.
 */
internal object CycleSelectionFontAssets {
    /**
     * Loads the same generated immutable PNG bytes and both exact provider settings before either timed boundary.
     */
    internal fun snapshot(): MinecraftFontSnapshot {
        val base = ComponentFontAssets.source()
        val files = base.paths().associateWith { checkNotNull(base.read(it)) }.toMutableMap()
        val default = checkNotNull(files["assets/minecraft/font/default.json"]).toString(Charsets.UTF_8)
        files["assets/strata_benchmark/font/alternate.json"] = default.replace("\"ascent\":7", "\"ascent\":6").toByteArray(Charsets.UTF_8)
        check(files.getValue("assets/strata_benchmark/font/alternate.json").contentEquals(files.getValue("assets/minecraft/font/default.json")).not())
        return MinecraftFontSnapshot
            .load(
                listOf(MinecraftMemoryFontAssetSource("cycle-selection-bitmap-v1", files)),
                MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84, fractionalUnihexAdvance = true),
            ).also { check(it.diagnostics.isEmpty()) }
    }
}
