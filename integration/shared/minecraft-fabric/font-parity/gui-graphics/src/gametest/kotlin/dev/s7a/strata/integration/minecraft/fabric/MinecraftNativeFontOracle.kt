package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonParser
import com.mojang.blaze3d.font.SheetGlyphInfo
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftGlyphChannel
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.font.glyphs.EmptyGlyph
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * Independently loads and bakes actual legacy Minecraft font providers on the client/render thread.
 * The oracle owns each provider and temporary GPU texture, restores the prior texture binding, and returns detached metrics and pixels.
 * No portable provider or raster implementation participates in the expected result.
 */
internal object MinecraftNativeFontOracle {
    /**
     * Checks that the native resource manager selected the exact original fixture inputs used by the offline engine.
     * Every borrowed resource stream is closed before returning the detached source-pack diagnostic table.
     */
    fun verifyResources(): String =
        buildString {
            appendLine("resource\tsourcePack\tbyteLength")
            MinecraftFontParityFixture.resourcePaths().forEach { path ->
                val parts = path.split('/', limit = 3)
                val identifier = ResourceLocation(parts[1], parts[2])
                val resource = Minecraft.getInstance().resourceManager.getResourceOrThrow(identifier)
                val bytes = resource.open().use { it.readAllBytes() }
                check(bytes.contentEquals(MinecraftFontParityFixture.bytes(path))) { "Native resource differs from the original fixture: $identifier" }
                appendLine("$identifier\t${resource.sourcePackId()}\t${bytes.size}")
            }
        }

    /**
     * Records the actual graphics state without changing the native renderer or candidate resources.
     */
    fun graphicsState(): String {
        RenderSystem.assertOnRenderThread()
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        val format =
            try {
                RenderSystem.bindTexture(Minecraft.getInstance().mainRenderTarget.colorTextureId)
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
            } finally {
                RenderSystem.bindTexture(previous)
            }
        return "OpenGL ${GL11.glGetString(GL11.GL_VERSION)}; vendor=${GL11.glGetString(GL11.GL_VENDOR)}; renderer=${GL11.glGetString(GL11.GL_RENDERER)}; dither=${GL11.glIsEnabled(GL11.GL_DITHER)}; framebufferSrgb=${GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB)}; subpixelBits=${GL11.glGetInteger(GL11.GL_SUBPIXEL_BITS)}; mainColorInternalFormat=$format"
    }

    /**
     * Changes only the diagnostic dither state and returns its prior value for mandatory finally restoration.
     */
    fun setDither(enabled: Boolean): Boolean {
        RenderSystem.assertOnRenderThread()
        val previous = GL11.glIsEnabled(GL11.GL_DITHER)
        if (enabled) GL11.glEnable(GL11.GL_DITHER) else GL11.glDisable(GL11.GL_DITHER)
        return previous
    }

    /**
     * Constructs the actual native styled Component used independently for both logical measurement and rendering.
     */
    fun component(row: MinecraftFontParityFixture.Row): Component = Component.literal(row.text).withStyle(Style.EMPTY.withFont(ResourceLocation(row.font.namespace, row.font.path)))

    /**
     * Measures the native logical Component and actual line height without reproducing native layout.
     */
    fun size(row: MinecraftFontParityFixture.Row): IntSize {
        val font = Minecraft.getInstance().font
        return IntSize(font.width(component(row)), font.lineHeight)
    }

    /**
     * Loads one provider definition through the game's own codec and captures the requested native glyph.
     */
    fun glyph(
        font: MinecraftFontParityFixture.FontCase,
        codePoint: Int,
    ): MinecraftFontGlyph {
        RenderSystem.assertOnRenderThread()
        val json = JsonParser.parseString(MinecraftFontParityFixture.bytes("assets/strata_font_test/font/${font.id.path}.json").decodeToString())
        val provider = json.asJsonObject.getAsJsonArray("providers")[checkNotNull(font.providerIndex)]
        val definition = MinecraftNativeFontAccess.decode(provider)
        val loader = definition.unpack().left().orElseThrow()
        return loader.load(Minecraft.getInstance().resourceManager).use { nativeProvider ->
            val native = checkNotNull(nativeProvider.getGlyph(codePoint)) { "Native provider rejected ${font.id} U+${codePoint.toString(16)}" }
            var result = MinecraftFontGlyph(native.advance, 0f, 0f, 0f, 0f, null, boldOffset = native.boldOffset, shadowOffset = native.shadowOffset)
            native.bake { sheet ->
                result =
                    MinecraftFontGlyph(
                        native.advance,
                        sheet.left,
                        MinecraftNativeFontAccess.top(sheet),
                        sheet.right,
                        MinecraftNativeFontAccess.bottom(sheet),
                        readPixels(sheet),
                        if (sheet.isColored) MinecraftGlyphChannel.Color else MinecraftGlyphChannel.Intensity,
                        native.boldOffset,
                        native.shadowOffset,
                    )
                EmptyGlyph.INSTANCE
            }
            result
        }
    }

    /**
     * Reads a positive atlas-sized native bitmap into detached pixels without permitting oversized allocations.
     * The caller must observe larger source dimensions without invoking this method.
     */
    fun readPixels(sheet: SheetGlyphInfo): DrawImage {
        require(sheet.pixelWidth in 1..256 && sheet.pixelHeight in 1..256) { "Native glyph readback must fit the actual font atlas." }
        val size = IntSize(sheet.pixelWidth, sheet.pixelHeight)
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        val texture = TextureUtil.generateTextureId()
        try {
            TextureUtil.prepareImage(texture, size.width, size.height)
            sheet.upload(0, 0)
            return NativeImage(size.width, size.height, false).use { image ->
                image.downloadTexture(0, false)
                val pixels =
                    IntArray(size.width * size.height) { index ->
                        val abgr = image.getPixelRGBA(index % size.width, index / size.width)
                        if (sheet.isColored) {
                            (abgr and 0xFF00FF00.toInt()) or ((abgr and 0xFF) shl 16) or ((abgr ushr 16) and 0xFF)
                        } else {
                            val coverage = abgr and 0xFF
                            coverage * 0x01010101
                        }
                    }
                createDrawImage(size, pixels)
            }
        } finally {
            TextureUtil.releaseTextureId(texture)
            RenderSystem.bindTexture(previous)
        }
    }
}
