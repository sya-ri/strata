package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.font.TextRenderable
import net.minecraft.util.LightCoordsUtil
import org.joml.Matrix4f
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap

/**
 * Detaches the actual prepared native GUI glyph vertices and their referenced atlas textures through public typed APIs.
 * No private reflection, portable shaping, glyph rasterization, or candidate image is used to build this expected-side evidence.
 * Capture borrows game textures on the render thread and owns every temporary readback buffer and native image.
 */
internal object MinecraftNativeFontVertices {
    /**
     * Writes actual native vertex and atlas evidence and returns the maximum observed atlas width and height.
     */
    fun capture(output: Path): IntSize {
        RenderSystem.assertOnRenderThread()
        val directory = output.resolve("native-atlases")
        Files.createDirectories(directory)
        val atlases = IdentityHashMap<GpuTexture, Int>()
        val vertices = StringBuilder("row\tatlas\tvertex\tx\ty\tz\tu\tv\targb\n")
        val textures = StringBuilder("index\twidth\theight\tformat\n")
        var maximumWidth = 0
        var maximumHeight = 0
        MinecraftFontParityFixture.rows.forEachIndexed { rowIndex, row ->
            val prepared =
                Minecraft.getInstance().font.prepareText(
                    MinecraftNativeFontOracle.component(row).visualOrderText,
                    MinecraftFontParityFixture.LEFT.toFloat(),
                    row.top.toFloat(),
                    row.color,
                    row.shadow,
                    false,
                    0,
                )
            prepared.visit(
                object : Font.GlyphVisitor {
                    override fun acceptGlyph(glyph: TextRenderable.Styled) = captureRenderable(glyph)

                    override fun acceptEffect(effect: TextRenderable) = captureRenderable(effect)

                    private fun captureRenderable(renderable: TextRenderable) {
                        val view = checkNotNull(renderable.textureView()) { "The original font scene unexpectedly contains an untextured native renderable." }
                        val texture = view.texture()
                        val atlas =
                            atlases.getOrPut(texture) {
                                val index = atlases.size
                                val width = texture.getWidth(0)
                                val height = texture.getHeight(0)
                                maximumWidth = maxOf(maximumWidth, width)
                                maximumHeight = maxOf(maximumHeight, height)
                                textures.appendLine("$index\t$width\t$height\t${texture.format}")
                                writeAtlas(texture, directory.resolve("atlas-$index.png"))
                                index
                            }
                        val collector = Collector(rowIndex, atlas, vertices)
                        // GlyphRenderState.buildVertices uses the same full-bright light and GUI=true arguments.
                        renderable.render(Matrix4f(), collector, LightCoordsUtil.FULL_BRIGHT, true)
                        collector.finish()
                    }
                },
            )
        }
        check(0 < maximumWidth && 0 < maximumHeight) { "The actual native font scene did not expose an atlas extent." }
        Files.writeString(output.resolve("native-vertices.tsv"), vertices)
        Files.writeString(directory.resolve("textures.tsv"), textures)
        return IntSize(maximumWidth, maximumHeight)
    }

    private fun writeAtlas(
        texture: GpuTexture,
        output: Path,
    ) {
        val width = texture.getWidth(0)
        val height = texture.getHeight(0)
        val components =
            when (texture.format) {
                GpuFormat.R8_UNORM -> 1
                GpuFormat.RGBA8_UNORM -> 4
                else -> error("Native fixture atlas has an unsupported diagnostic format: ${texture.format}")
            }
        val rowBytes = (width * components + 3) / 4 * 4
        val device = RenderSystem.getDevice()
        device.createBuffer({ "Strata actual native font atlas readback" }, GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_READ, rowBytes.toLong() * height).use { buffer ->
            val encoder = device.createCommandEncoder()
            encoder.copyTextureToBuffer(texture, buffer, 0L, {}, 0)
            encoder.createFence().use { fence ->
                encoder.submit()
                check(fence.awaitCompletion(5_000_000_000L)) { "Actual native atlas readback did not finish before its bounded GPU timeout." }
            }
            buffer.map(true, false).use { mapped ->
                writeAtlasPixels(output, texture, mapped.data(), rowBytes, components)
            }
        }
    }

    private fun writeAtlasPixels(
        output: Path,
        texture: GpuTexture,
        bytes: ByteBuffer,
        rowBytes: Int,
        components: Int,
    ) {
        val width = texture.getWidth(0)
        val height = texture.getHeight(0)
        NativeImage(width, height, false).use { image ->
            for (y in 0 until height) {
                for (x in 0 until width) image.setPixel(x, y, atlasPixel(bytes, y * rowBytes + x * components, texture.format))
            }
            image.writeToFile(output)
        }
    }

    private fun atlasPixel(
        bytes: ByteBuffer,
        offset: Int,
        format: GpuFormat,
    ): Int {
        val red = bytes.get(offset).toInt() and 0xFF
        return if (format == GpuFormat.R8_UNORM) {
            red * 0x01010101
        } else {
            val green = bytes.get(offset + 1).toInt() and 0xFF
            val blue = bytes.get(offset + 2).toInt() and 0xFF
            val alpha = bytes.get(offset + 3).toInt() and 0xFF
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    private class Collector(
        private val row: Int,
        private val atlas: Int,
        private val output: StringBuilder,
    ) : VertexConsumer {
        private var pending = false
        private var index = 0
        private var x = 0f
        private var y = 0f
        private var z = 0f
        private var u = 0f
        private var v = 0f
        private var argb = 0

        override fun addVertex(
            x: Float,
            y: Float,
            z: Float,
        ): VertexConsumer {
            finish()
            pending = true
            this.x = x
            this.y = y
            this.z = z
            return this
        }

        override fun setColor(
            red: Int,
            green: Int,
            blue: Int,
            alpha: Int,
        ): VertexConsumer = setColor((alpha shl 24) or (red shl 16) or (green shl 8) or blue)

        override fun setColor(color: Int): VertexConsumer {
            argb = color
            return this
        }

        override fun setUv(
            u: Float,
            v: Float,
        ): VertexConsumer {
            this.u = u
            this.v = v
            return this
        }

        override fun setUv1(
            u: Int,
            v: Int,
        ): VertexConsumer = this

        override fun setUv2(
            u: Int,
            v: Int,
        ): VertexConsumer = this

        override fun setNormal(
            x: Float,
            y: Float,
            z: Float,
        ): VertexConsumer = this

        override fun setLineWidth(width: Float): VertexConsumer = this

        fun finish() {
            if (pending) {
                output.appendLine("$row\t$atlas\t${index++}\t$x\t$y\t$z\t$u\t$v\t${argb.toUInt().toString(16)}")
                pending = false
            }
        }
    }
}
