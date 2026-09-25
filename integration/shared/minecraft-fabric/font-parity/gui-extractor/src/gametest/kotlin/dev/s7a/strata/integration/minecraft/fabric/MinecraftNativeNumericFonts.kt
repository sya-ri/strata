package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonParser
import com.mojang.blaze3d.font.GlyphBitmap
import com.mojang.blaze3d.font.GlyphInfo
import com.mojang.blaze3d.font.GlyphProvider
import com.mojang.blaze3d.font.UnbakedGlyph
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.serialization.JsonOps
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.resource.ResourceId
import it.unimi.dsi.fastutil.ints.IntSet
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GlyphSource
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.font.FontOption
import net.minecraft.client.gui.font.FontSet
import net.minecraft.client.gui.font.GlyphStitcher
import net.minecraft.client.gui.font.glyphs.BakedGlyph
import net.minecraft.client.gui.font.glyphs.EffectGlyph
import net.minecraft.client.gui.font.glyphs.EmptyGlyph
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier

/**
 * Owns isolated actual current providers, FontSets, and atlas registrations without altering the startup font manager.
 * All work is client/render-thread confined; standard native Font code performs selection, width, fallback, and drawing.
 * Provider observations never upload a source larger than the native atlas and never become portable render inputs.
 */
internal class MinecraftNativeNumericFonts : AutoCloseable {
    private val textures = NumericTextures()
    private val providers = linkedMapOf<ResourceId, GlyphProvider>()
    private val sets = linkedMapOf<ResourceId, FontSet>()
    private var closed = false

    /**
     * The actual native Font with an unfiltered source resolving only this owned session's identifiers.
     */
    val font: Font =
        Font(
            object : Font.Provider {
                override fun glyphs(description: FontDescription): GlyphSource {
                    check(description is FontDescription.Resource) { "Numeric fixture requested a non-resource font." }
                    val id = description.id()
                    return checkNotNull(sets[ResourceId(id.namespace, id.path)]) { "Unknown isolated native numeric font: $id" }.source(false)
                }

                override fun effect(): EffectGlyph = checkNotNull(sets[MinecraftNumericFontFixture.regularFont]).whiteGlyph()
            },
        )

    init {
        RenderSystem.assertOnRenderThread()
        runCatching {
            val ordinary = JsonParser.parseString(MinecraftFontParityFixture.bytes("assets/strata_font_test/font/ttf.json").decodeToString()).asJsonObject.getAsJsonArray("providers")[0]
            load(MinecraftNumericFontFixture.regularFont, ordinary.toString())
            MinecraftNumericFontFixture.Case.entries.forEach { case -> load(case.id, MinecraftNumericFontFixture.providerJson(case)) }
        }.getOrElse { failure ->
            runCatching(::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    /**
     * Captures native raw float bits and source dimensions, reading pixels only for atlas-sized sources.
     */
    fun glyph(
        case: MinecraftNumericFontFixture.Case,
        codePoint: Int,
    ): MinecraftNumericFontGlyph {
        RenderSystem.assertOnRenderThread()
        val native = checkNotNull(providers[case.id]).getGlyph(codePoint) ?: return MinecraftNumericFontGlyph(false)
        val info = native.info()
        var result = MinecraftNumericFontGlyph(true, info.advance, boldOffset = info.boldOffset, shadowOffset = info.shadowOffset)
        native.bake(
            object : UnbakedGlyph.Stitcher {
                override fun stitch(
                    info: GlyphInfo,
                    bitmap: GlyphBitmap,
                ): BakedGlyph {
                    val width = bitmap.pixelWidth
                    val height = bitmap.pixelHeight
                    check(0 < width && 0 < height) { "Native ink glyph has non-positive source dimensions." }
                    val image = if (width <= 256 && height <= 256) MinecraftNativeFontOracle.readPixels(bitmap) else null
                    result =
                        MinecraftNumericFontGlyph(
                            true,
                            info.advance,
                            bitmap.left,
                            bitmap.top,
                            bitmap.right,
                            bitmap.bottom,
                            info.boldOffset,
                            info.shadowOffset,
                            width,
                            height,
                            image,
                        )
                    return EmptyGlyph(info.advance).bake(this)
                }

                override fun getMissing(): BakedGlyph = error("Numeric raw provider unexpectedly requested missing-glyph stitching.")
            },
        )
        return result
    }

    /**
     * Returns the native signed width before any projection to non-negative layout geometry.
     */
    fun width(row: MinecraftNumericFontFixture.Row): Int = font.width(component(row))

    /**
     * Extracts the unchanged numeric scene through the standard native GUI text path.
     */
    fun draw(graphics: GuiGraphicsExtractor) {
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), MinecraftFontParityFixture.background)
        text(graphics)
    }

    /**
     * Extracts only native text when the screen has already supplied its identical opaque background.
     */
    fun text(graphics: GuiGraphicsExtractor) {
        MinecraftNumericFontFixture.rows.forEach { row -> graphics.text(font, component(row), row.left, row.top, row.color, row.shadow) }
    }

    /**
     * Reads actual native atlas extents allocated by this scene, without substituting a driver maximum.
     */
    fun atlasSize(): IntSize = textures.atlasSize()

    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        closed = true
        val owned: List<AutoCloseable> = sets.values.toList() + providers.values.toList() + listOf(textures)
        MinecraftNumericFontCleanup.preserving({ MinecraftNumericFontCleanup.closeAll(owned) }) {
            sets.clear()
            providers.clear()
        }
    }

    private fun load(
        id: ResourceId,
        json: String,
    ) {
        val definition =
            GlyphProviderDefinition.MAP_CODEC
                .codec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .result()
                .orElseThrow()
        val provider =
            definition
                .unpack()
                .left()
                .orElseThrow()
                .load(Minecraft.getInstance().resourceManager)
        check(providers.put(id, provider) == null)
        val prefix = Identifier.fromNamespaceAndPath("strata_numeric_font_oracle", "${id.namespace}/${id.path}")
        val set = FontSet(GlyphStitcher(textures, prefix))
        check(sets.put(id, set) == null)
        set.reload(listOf(GlyphProvider.Conditional(BorrowedProvider(provider), FontOption.Filter.ALWAYS_PASS)), emptySet())
    }

    private fun component(row: MinecraftNumericFontFixture.Row): Component {
        val result = Component.empty()
        row.segments.forEach { segment ->
            val description = FontDescription.Resource(Identifier.fromNamespaceAndPath(segment.font.namespace, segment.font.path))
            result.append(Component.literal(segment.text).withStyle(Style.EMPTY.withFont(description)))
        }
        return result
    }

    private class BorrowedProvider(
        private val delegate: GlyphProvider,
    ) : GlyphProvider {
        override fun getGlyph(codePoint: Int): UnbakedGlyph? = delegate.getGlyph(codePoint)

        override fun getSupportedGlyphs(): IntSet = delegate.supportedGlyphs
    }

    private class NumericTextures : TextureManager(Minecraft.getInstance().resourceManager) {
        private val registered = linkedMapOf<Identifier, AbstractTexture>()

        override fun register(
            id: Identifier,
            texture: AbstractTexture,
        ) {
            if (id == MissingTextureAtlasSprite.getLocation()) {
                // The superclass registers its own newly allocated missing texture before this subclass's fields exist.
                super.register(id, texture)
                return
            }
            if (128 <= registered.size || registered.containsKey(id)) {
                texture.close()
                error("Isolated numeric font atlas registration exceeded its bound or reused an identifier.")
            }
            registered[id] = texture
            Minecraft.getInstance().textureManager.register(id, texture)
        }

        override fun release(id: Identifier) {
            check(registered.remove(id) != null) { "Numeric glyph stitcher released an unowned texture." }
            Minecraft.getInstance().textureManager.release(id)
        }

        /**
         * Observes the positive dimensions of the actual GPU texture objects registered for these FontSets.
         */
        fun atlasSize(): IntSize {
            var width = 0
            var height = 0
            registered.values.forEach { texture ->
                width = maxOf(width, texture.texture.getWidth(0))
                height = maxOf(height, texture.texture.getHeight(0))
            }
            check(0 < width && 0 < height) { "Numeric native FontSets allocated no observable atlas." }
            return IntSize(width, height)
        }

        override fun close() {
            val releases = registered.keys.map { id -> AutoCloseable { release(id) } }
            MinecraftNumericFontCleanup.preserving({ MinecraftNumericFontCleanup.closeAll(releases) }) {
                registered.clear()
                super.close()
            }
        }
    }
}
