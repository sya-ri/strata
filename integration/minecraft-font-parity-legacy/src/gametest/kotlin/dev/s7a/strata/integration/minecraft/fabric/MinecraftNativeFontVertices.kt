package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import org.joml.Matrix4f

/**
 * Records actual native Font vertex submission without replacing any shaping, placement, or provider algorithm.
 * Capture runs on the client thread and returns detached text only; the collector never submits GPU draws.
 * Each row has a fresh collector and matrix, while glyphs are baked through the real loaded native FontSet.
 */
internal object MinecraftNativeFontVertices {
    /**
     * Returns global positions, atlas texel coordinates, and packed tint for every actual native glyph vertex.
     */
    fun capture(): String {
        RenderSystem.assertOnRenderThread()
        return buildString {
            MinecraftFontParityFixture.rows.forEachIndexed { index, row ->
                appendLine("row=$index font=${row.font} style=${row.style} text=${row.text}")
                val collector = Collector()
                Minecraft.getInstance().font.drawInBatch(
                    MinecraftNativeFontOracle.component(row),
                    MinecraftFontParityFixture.LEFT.toFloat(),
                    row.top.toFloat(),
                    row.color,
                    row.shadow,
                    Matrix4f(),
                    MultiBufferSource { collector },
                    Font.DisplayMode.NORMAL,
                    0,
                    LightTexture.FULL_BRIGHT,
                )
                append(collector.vertices)
            }
        }
    }

    private class Collector : VertexConsumer {
        val vertices = StringBuilder()
        private var x = 0.0
        private var y = 0.0
        private var z = 0.0
        private var u = 0f
        private var v = 0f
        private var argb = 0
        private var index = 0

        override fun vertex(
            x: Double,
            y: Double,
            z: Double,
        ): VertexConsumer {
            this.x = x
            this.y = y
            this.z = z
            return this
        }

        override fun color(
            red: Int,
            green: Int,
            blue: Int,
            alpha: Int,
        ): VertexConsumer {
            argb = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            return this
        }

        override fun uv(
            u: Float,
            v: Float,
        ): VertexConsumer {
            this.u = u
            this.v = v
            return this
        }

        override fun overlayCoords(
            u: Int,
            v: Int,
        ): VertexConsumer = this

        override fun uv2(
            u: Int,
            v: Int,
        ): VertexConsumer = this

        override fun normal(
            x: Float,
            y: Float,
            z: Float,
        ): VertexConsumer = this

        override fun endVertex() {
            vertices.appendLine("${index++}\t$x\t$y\t$z\t${u * 256f}\t${v * 256f}\t${argb.toUInt().toString(16)}")
        }

        override fun defaultColor(
            red: Int,
            green: Int,
            blue: Int,
            alpha: Int,
        ) {
            color(red, green, blue, alpha)
        }

        override fun unsetDefaultColor() = Unit
    }
}
