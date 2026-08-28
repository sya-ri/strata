package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonParser
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.font.GlyphBitmap
import com.mojang.blaze3d.font.GlyphInfo
import com.mojang.blaze3d.font.UnbakedGlyph
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.serialization.JsonOps
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftGlyphChannel
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.font.glyphs.BakedGlyph
import net.minecraft.client.gui.font.glyphs.EmptyGlyph
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * Independently loads and bakes actual Minecraft 26.2 font providers on the client/render thread.
 * Each provider, GPU texture, readback buffer, and mapped view is owned and released within one capture.
 * Native pixels are expected values only; the portable engine loads the original assets separately.
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
                val identifier = Identifier.fromNamespaceAndPath(parts[1], parts[2])
                val resource = Minecraft.getInstance().resourceManager.getResourceOrThrow(identifier)
                val bytes = resource.open().use { it.readAllBytes() }
                check(bytes.contentEquals(MinecraftFontParityFixture.bytes(path))) { "Native resource differs from the original fixture: $identifier" }
                appendLine("$identifier\t${resource.sourcePackId()}\t${bytes.size}")
            }
        }

    /**
     * Reports the actual native framebuffer state without changing the renderer or candidate inputs.
     */
    fun graphicsState(): String {
        RenderSystem.assertOnRenderThread()
        return "OpenGL ${GL11.glGetString(GL11.GL_VERSION)}; vendor=${GL11.glGetString(GL11.GL_VENDOR)}; renderer=${GL11.glGetString(GL11.GL_RENDERER)}; dither=${GL11.glIsEnabled(GL11.GL_DITHER)}; framebufferSrgb=${GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB)}; subpixelBits=${GL11.glGetInteger(GL11.GL_SUBPIXEL_BITS)}; mainColorFormat=${Minecraft.getInstance().gameRenderer.mainRenderTarget().colorTexture?.format}"
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
     * Constructs the real native styled Component used independently for logical measurement and rendering.
     */
    fun component(row: MinecraftFontParityFixture.Row): Component {
        val font = FontDescription.Resource(Identifier.fromNamespaceAndPath(row.font.namespace, row.font.path))
        return Component.literal(row.text).withStyle(Style.EMPTY.withFont(font))
    }

    /**
     * Measures the native logical Component and actual line height without reproducing native layout.
     */
    fun size(row: MinecraftFontParityFixture.Row): IntSize {
        val font = Minecraft.getInstance().font
        return IntSize(font.width(component(row)), font.lineHeight)
    }

    /**
     * Uses the native provider codec and stitch callback to detach one glyph's metrics and raster.
     */
    fun glyph(
        font: MinecraftFontParityFixture.FontCase,
        codePoint: Int,
    ): MinecraftFontGlyph {
        RenderSystem.assertOnRenderThread()
        val json = JsonParser.parseString(MinecraftFontParityFixture.bytes("assets/strata_font_test/font/${font.id.path}.json").decodeToString())
        val provider = json.asJsonObject.getAsJsonArray("providers")[checkNotNull(font.providerIndex)]
        val definition =
            GlyphProviderDefinition.MAP_CODEC
                .codec()
                .parse(JsonOps.INSTANCE, provider)
                .result()
                .orElseThrow()
        val loader = definition.unpack().left().orElseThrow()
        return loader.load(Minecraft.getInstance().resourceManager).use { nativeProvider ->
            val native = checkNotNull(nativeProvider.getGlyph(codePoint)) { "Native provider rejected ${font.id} U+${codePoint.toString(16)}" }
            val info = native.info()
            var result = MinecraftFontGlyph(info.advance, 0f, 0f, 0f, 0f, null, boldOffset = info.boldOffset, shadowOffset = info.shadowOffset)
            native.bake(
                object : UnbakedGlyph.Stitcher {
                    override fun stitch(
                        info: GlyphInfo,
                        bitmap: GlyphBitmap,
                    ): BakedGlyph {
                        result =
                            MinecraftFontGlyph(
                                info.advance,
                                bitmap.left,
                                bitmap.top,
                                bitmap.right,
                                bitmap.bottom,
                                readPixels(bitmap),
                                if (bitmap.isColored) MinecraftGlyphChannel.Color else MinecraftGlyphChannel.Intensity,
                                info.boldOffset,
                                info.shadowOffset,
                            )
                        return EmptyGlyph(info.advance).bake(this)
                    }

                    override fun getMissing(): BakedGlyph = error("Native fixture glyph unexpectedly requested missing-glyph stitching.")
                },
            )
            result
        }
    }

    /**
     * Reads a positive atlas-sized native bitmap into detached pixels without permitting oversized allocations.
     * The caller must observe larger source dimensions without invoking this method.
     */
    fun readPixels(bitmap: GlyphBitmap): DrawImage {
        require(bitmap.pixelWidth in 1..256 && bitmap.pixelHeight in 1..256) { "Native glyph readback must fit the actual font atlas." }
        val size = IntSize(bitmap.pixelWidth, bitmap.pixelHeight)
        val components = if (bitmap.isColored) 4 else 1
        // Native OpenGL copyTextureToBuffer keeps four-byte pack alignment for single-channel rows.
        val readbackWidth = if (bitmap.isColored) size.width else (size.width + 3) / 4 * 4
        val device = RenderSystem.getDevice()
        val format = if (bitmap.isColored) GpuFormat.RGBA8_UNORM else GpuFormat.R8_UNORM
        device.createTexture("Strata native glyph oracle", GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_COPY_SRC, format, readbackWidth, size.height, 1, 1).use { texture ->
            bitmap.upload(0, 0, texture)
            device.createBuffer({ "Strata native glyph readback" }, GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_READ, (readbackWidth * size.height * components).toLong()).use { buffer ->
                val encoder = device.createCommandEncoder()
                encoder.copyTextureToBuffer(texture, buffer, 0L, {}, 0)
                encoder.createFence().use { fence ->
                    encoder.submit()
                    check(fence.awaitCompletion(5_000_000_000L)) { "Native glyph readback did not complete before the bounded GPU fence timeout." }
                }
                return buffer.map(true, false).use { mapped ->
                    val bytes = mapped.data()
                    val pixels =
                        IntArray(size.width * size.height) { index ->
                            val offset = (index / size.width * readbackWidth + index % size.width) * components
                            val red = bytes.get(offset).toInt() and 0xFF
                            if (bitmap.isColored) {
                                val green = bytes.get(offset + 1).toInt() and 0xFF
                                val blue = bytes.get(offset + 2).toInt() and 0xFF
                                val alpha = bytes.get(offset + 3).toInt() and 0xFF
                                (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                            } else {
                                red * 0x01010101
                            }
                        }
                    createDrawImage(size, pixels)
                }
            }
        }
    }
}
