package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonElement
import com.mojang.blaze3d.font.GlyphProvider
import com.mojang.blaze3d.font.SheetGlyphInfo
import com.mojang.blaze3d.font.TrueTypeGlyphProvider
import com.mojang.serialization.JsonOps
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.font.FontOption
import net.minecraft.client.gui.font.FontSet
import net.minecraft.client.gui.font.providers.FreeTypeUtil
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition
import net.minecraft.resources.ResourceLocation
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.freetype.FT_Face
import org.lwjgl.util.freetype.FT_Matrix
import org.lwjgl.util.freetype.FT_Vector
import org.lwjgl.util.freetype.FreeType

/**
 * Keeps the native map-codec and glyph coordinate ABI in its exact representative legacy target.
 */
internal object MinecraftNativeFontAccess {
    /**
     * Reads actual loaded FontSet face transforms through narrow, version-owned test introspection.
     * Runs after resource reload on the client thread and never changes or releases the borrowed native faces.
     * The private member names are verified against this target's actual named game classes; failure aborts diagnostics.
     * Production runs omit this optional diagnostic because private named members do not form a remapped public ABI.
     */
    fun faceState(): String {
        if (FabricLoader.getInstance().isDevelopmentEnvironment.not()) return "Private face diagnostics require the named development runtime."
        val getSet = Font::class.java.getDeclaredMethod("getFontSet", ResourceLocation::class.java).apply { isAccessible = true }
        val providers = FontSet::class.java.getDeclaredField("activeProviders").apply { isAccessible = true }
        val faceField = TrueTypeGlyphProvider::class.java.getDeclaredField("face").apply { isAccessible = true }
        val font = Minecraft.getInstance().font
        return buildString {
            MemoryStack.stackPush().use { stack ->
                val major = stack.mallocInt(1)
                val minor = stack.mallocInt(1)
                val patch = stack.mallocInt(1)
                FreeType.FT_Library_Version(FreeTypeUtil.getLibrary(), major, minor, patch)
                appendLine("FreeType ${major[0]}.${minor[0]}.${patch[0]}")
                appendLine("font\tface\tmatrixXX\tmatrixXY\tmatrixYX\tmatrixYY\tshiftX26dot6\tshiftY26dot6")
                val matrix = FT_Matrix.malloc(stack)
                val shift = FT_Vector.malloc(stack)
                MinecraftFontParityFixture.FontCase.entries.forEach { fixture ->
                    val identifier = ResourceLocation(fixture.id.namespace, fixture.id.path)
                    val set = getSet.invoke(font, identifier) as FontSet
                    (providers.get(set) as List<*>).filterIsInstance<TrueTypeGlyphProvider>().forEach { provider ->
                        val face = faceField.get(provider) as FT_Face
                        FreeType.FT_Get_Transform(face, matrix, shift)
                        appendLine("$identifier\t${face.address().toString(16)}\t${matrix.xx()}\t${matrix.xy()}\t${matrix.yx()}\t${matrix.yy()}\t${shift.x()}\t${shift.y()}")
                    }
                }
            }
        }
    }

    /**
     * Decodes the caller's original fixture JSON through Minecraft 1.20.5's actual codec, failing on invalid native input.
     */
    fun decode(json: JsonElement): GlyphProviderDefinition =
        GlyphProviderDefinition.MAP_CODEC
            .codec()
            .parse(JsonOps.INSTANCE, json)
            .result()
            .orElseThrow()

    /**
     * Returns the native sheet's top coordinate without reproducing its metric formula.
     */
    fun top(sheet: SheetGlyphInfo): Float = sheet.top

    /**
     * Returns the native sheet's bottom coordinate without reproducing its metric formula.
     */
    fun bottom(sheet: SheetGlyphInfo): Float = sheet.bottom

    /**
     * Loads the caller-owned numeric provider through this release's real unfiltered FontSet selection.
     * The always-pass native condition does not alter the provider's metrics, rasterization, or ownership.
     */
    fun reloadNumericSet(
        set: FontSet,
        provider: GlyphProvider,
    ) {
        set.reload(listOf(GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)), emptySet())
    }
}
