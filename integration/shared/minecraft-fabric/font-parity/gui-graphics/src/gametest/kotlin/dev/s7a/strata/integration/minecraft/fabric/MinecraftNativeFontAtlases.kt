package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.GL11
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the actual native fixture font atlases after native Font has baked and positioned the scene.
 * Capture is confined to the render thread, borrows but never releases native textures, and restores changed GL state.
 * The detached diagnostic PNGs are expected-side evidence only and never participate in portable font loading.
 */
internal object MinecraftNativeFontAtlases {
    /**
     * Writes raw RGBA atlas pixels and the actual texture sampling parameters under the caller-owned output directory.
     * Missing atlas names terminate a font's contiguous native atlas sequence without loading absent texture identifiers.
     * Returns the maximum width and height actually observed among this scene's native font atlas textures.
     */
    fun capture(output: Path): IntSize {
        RenderSystem.assertOnRenderThread()
        val directory = output.resolve("native-atlases")
        Files.createDirectories(directory)
        val manager = Minecraft.getInstance().textureManager
        val previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        val previousAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT)
        val metadata = StringBuilder("font\tindex\twidth\theight\tinternalFormat\tminFilter\tmagFilter\n")
        var maximumWidth = 0
        var maximumHeight = 0
        try {
            val missing = MissingTextureAtlasSprite.getTexture()
            MinecraftFontParityFixture.rows.map { it.font }.distinct().forEach { font ->
                var index = 0
                while (true) {
                    val identifier = ResourceLocation(font.namespace, "${font.path}/$index")
                    val texture = manager.getTexture(identifier, missing)
                    if (texture === missing) break
                    RenderSystem.bindTexture(texture.id)
                    val width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
                    val height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
                    val format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
                    val minFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER)
                    val magFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER)
                    maximumWidth = maxOf(maximumWidth, width)
                    maximumHeight = maxOf(maximumHeight, height)
                    metadata.appendLine("$font\t$index\t$width\t$height\t$format\t$minFilter\t$magFilter")
                    NativeImage(width, height, false).use { image ->
                        image.downloadTexture(0, false)
                        image.writeToFile(directory.resolve("${font.path.replace('/', '_')}-$index.png"))
                    }
                    index += 1
                }
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousAlignment)
            RenderSystem.bindTexture(previousTexture)
        }
        Files.writeString(directory.resolve("textures.tsv"), metadata)
        check(0 < maximumWidth && 0 < maximumHeight) { "The actual native font scene did not expose an atlas extent." }
        return IntSize(maximumWidth, maximumHeight)
    }
}
