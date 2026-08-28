package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonParser
import com.mojang.blaze3d.font.GlyphInfo
import com.mojang.blaze3d.font.GlyphProvider
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.resource.ResourceId
import it.unimi.dsi.fastutil.ints.IntSet
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.font.FontSet
import net.minecraft.client.gui.font.glyphs.EmptyGlyph
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.GL11

/**
 * Owns isolated actual legacy providers and FontSets, leaving the startup font manager untouched.
 * All native work is client/render-thread confined and all generated texture registrations are bounded and removed on close.
 * The real provider supplies raw metrics and pixels, and the real Font supplies width, selection, fallback, and drawing.
 */
internal class MinecraftNativeNumericFonts : AutoCloseable {
    private val textures = NumericTextures()
    private val providers = linkedMapOf<ResourceId, GlyphProvider>()
    private val sets = linkedMapOf<ResourceId, FontSet>()
    private var closed = false

    /**
     * The ordinary unfiltered native Font, resolving only this session's isolated identifiers.
     */
    val font: Font = Font({ id -> checkNotNull(sets[ResourceId(id.namespace, id.path)]) { "Unknown isolated native numeric font: $id" } }, false)

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
     * Captures the original provider result, stopping before every oversized raster upload.
     * Missing code points and spacing-only glyphs remain distinct observations.
     */
    fun glyph(
        case: MinecraftNumericFontFixture.Case,
        codePoint: Int,
    ): MinecraftNumericFontGlyph {
        RenderSystem.assertOnRenderThread()
        val native = checkNotNull(providers[case.id]).getGlyph(codePoint) ?: return MinecraftNumericFontGlyph(false)
        var result = MinecraftNumericFontGlyph(true, native.advance, boldOffset = native.boldOffset, shadowOffset = native.shadowOffset)
        native.bake { sheet ->
            val width = sheet.pixelWidth
            val height = sheet.pixelHeight
            check(0 < width && 0 < height) { "Native ink glyph has non-positive source dimensions." }
            val image = if (width <= 256 && height <= 256) MinecraftNativeFontOracle.readPixels(sheet) else null
            result =
                MinecraftNumericFontGlyph(
                    true,
                    native.advance,
                    sheet.left,
                    MinecraftNativeFontAccess.top(sheet),
                    sheet.right,
                    MinecraftNativeFontAccess.bottom(sheet),
                    native.boldOffset,
                    native.shadowOffset,
                    width,
                    height,
                    image,
                )
            EmptyGlyph.INSTANCE
        }
        return result
    }

    /**
     * Returns the native signed result without projecting it into non-negative layout geometry.
     */
    fun width(row: MinecraftNumericFontFixture.Row): Int = font.width(component(row))

    /**
     * Draws the numeric scene through unchanged native GUI Font rendering.
     */
    fun draw(graphics: GuiGraphics) {
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), MinecraftFontParityFixture.background)
        MinecraftNumericFontFixture.rows.forEach { row -> graphics.drawString(font, component(row), row.left, row.top, row.color, row.shadow) }
    }

    /**
     * Measures the actual allocated atlas textures used by this isolated native scene, never the driver maximum.
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
        val definition = MinecraftNativeFontAccess.decode(JsonParser.parseString(json))
        val provider =
            definition
                .unpack()
                .left()
                .orElseThrow()
                .load(Minecraft.getInstance().resourceManager)
        check(providers.put(id, provider) == null)
        val set = FontSet(textures, ResourceLocation("strata_numeric_font_oracle", "${id.namespace}/${id.path}"))
        check(sets.put(id, set) == null)
        MinecraftNativeFontAccess.reloadNumericSet(set, BorrowedProvider(provider))
    }

    private fun component(row: MinecraftNumericFontFixture.Row): Component {
        val result = Component.empty()
        row.segments.forEach { segment ->
            result.append(Component.literal(segment.text).withStyle(Style.EMPTY.withFont(ResourceLocation(segment.font.namespace, segment.font.path))))
        }
        return result
    }

    private class BorrowedProvider(
        private val delegate: GlyphProvider,
    ) : GlyphProvider {
        override fun getGlyph(codePoint: Int): GlyphInfo? = delegate.getGlyph(codePoint)

        override fun getSupportedGlyphs(): IntSet = delegate.supportedGlyphs
    }

    private class NumericTextures : TextureManager(Minecraft.getInstance().resourceManager) {
        private val registered = linkedMapOf<ResourceLocation, AbstractTexture>()

        override fun register(
            id: ResourceLocation,
            texture: AbstractTexture,
        ) {
            if (128 <= registered.size || registered.containsKey(id)) {
                texture.close()
                error("Isolated numeric font atlas registration exceeded its bound or reused an identifier.")
            }
            registered[id] = texture
            Minecraft.getInstance().textureManager.register(id, texture)
        }

        /**
         * Reads only the textures registered by this session and restores the previous binding.
         */
        fun atlasSize(): IntSize {
            val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
            try {
                var width = 0
                var height = 0
                registered.values.forEach { texture ->
                    RenderSystem.bindTexture(texture.id)
                    width = maxOf(width, GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH))
                    height = maxOf(height, GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT))
                }
                check(0 < width && 0 < height) { "Numeric native FontSets allocated no observable atlas." }
                return IntSize(width, height)
            } finally {
                RenderSystem.bindTexture(previous)
            }
        }

        override fun close() {
            // Legacy FontSet releases IDs idempotently; TextureManager.release additionally removes its otherwise retained registrations.
            val releases = registered.keys.map { id -> AutoCloseable { Minecraft.getInstance().textureManager.release(id) } }
            MinecraftNumericFontCleanup.preserving({ MinecraftNumericFontCleanup.closeAll(releases) }) {
                registered.clear()
                super.close()
            }
        }
    }
}
