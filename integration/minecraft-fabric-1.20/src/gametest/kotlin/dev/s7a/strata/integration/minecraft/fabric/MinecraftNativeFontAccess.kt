package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonElement
import com.mojang.blaze3d.font.GlyphProvider
import com.mojang.blaze3d.font.SheetGlyphInfo
import com.mojang.blaze3d.font.TrueTypeGlyphProvider
import com.mojang.serialization.JsonOps
import net.minecraft.client.gui.font.FontSet
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition

/**
 * Keeps the pre-filter native font codec and glyph coordinate ABI in its exact representative target.
 */
internal object MinecraftNativeFontAccess {
    /**
     * Identifies the target's STB provider without loading unavailable FreeType bindings.
     */
    fun faceState(): String = "STB TrueType provider class: ${TrueTypeGlyphProvider::class.java.name}"

    /**
     * Decodes the caller's original fixture JSON through Minecraft 1.20's actual codec, failing on invalid native input.
     */
    fun decode(json: JsonElement): GlyphProviderDefinition =
        GlyphProviderDefinition.CODEC
            .parse(JsonOps.INSTANCE, json)
            .result()
            .orElseThrow()

    /**
     * Applies the three-pixel origin adjustment made by this release's native BakedGlyph.render to its sheet coordinate.
     */
    fun top(sheet: SheetGlyphInfo): Float = sheet.up - 3f

    /**
     * Applies the same native BakedGlyph.render origin adjustment to the sheet's lower edge.
     */
    fun bottom(sheet: SheetGlyphInfo): Float = sheet.down - 3f

    /**
     * Loads the caller-owned numeric provider through this release's real unfiltered FontSet selection.
     * The set receives a non-owning provider view; the numeric session closes the actual native provider.
     */
    fun reloadNumericSet(
        set: FontSet,
        provider: GlyphProvider,
    ) {
        set.reload(listOf(provider))
    }
}
