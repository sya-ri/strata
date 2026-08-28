package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import java.util.EnumMap
import java.util.IdentityHashMap
import java.util.function.Consumer

/**
 * Preserves native GUI preparation, sorting, batching, and vertex generation while declaring the owned capture target's format.
 * Only the final element traversal substitutes a pipeline; all element data and geometry remain delegated to the native element.
 * The render thread owns this state and borrows [pipelines] for one capture.
 */
internal class MinecraftNativeFontCaptureState(
    private val pipelines: Pipelines,
    private val format: GpuFormat,
) : GuiRenderState() {
    override fun forEachElement(
        consumer: Consumer<GuiElementRenderState>,
        range: TraverseRange,
    ) {
        super.forEachElement({ element -> consumer.accept(Element(element, pipelines.adapt(element.pipeline(), format))) }, range)
    }

    /**
     * Reuses at most two format declarations for each of eight native pipelines across this test's three GUI scales.
     * Native programs remain owned by the game device and are released by its normal shutdown; closing this render-thread owner releases its references.
     * Unsupported formats, extra color attachments, capacity overflow, and reuse after close fail the capture instead of changing native rendering behavior.
     */
    internal class Pipelines : AutoCloseable {
        private val originals = IdentityHashMap<RenderPipeline, EnumMap<GpuFormat, RenderPipeline>>()
        private var closed = false

        /**
         * Returns a stable declaration retaining every original pipeline field except the single color attachment's format.
         * The caller remains on the render thread and does not own the returned native device program.
         */
        fun adapt(
            original: RenderPipeline,
            format: GpuFormat,
        ): RenderPipeline {
            check(closed.not()) { "Native capture pipelines are already closed." }
            check(format == GpuFormat.RGBA8_UNORM || format == GpuFormat.RGBA32_FLOAT) { "Unsupported native capture format: $format" }
            val formats =
                originals.getOrPut(original) {
                    check(originals.size < MAX_ORIGINAL_PIPELINES) { "Native capture exceeded its bounded pipeline set." }
                    EnumMap(GpuFormat::class.java)
                }
            return formats.getOrPut(format) { Pipeline(original, format) }
        }

        override fun close() {
            originals.clear()
            closed = true
        }

        private companion object {
            private const val MAX_ORIGINAL_PIPELINES = 8
        }
    }

    private class Element(
        original: GuiElementRenderState,
        private val adaptedPipeline: RenderPipeline,
    ) : GuiElementRenderState by original {
        override fun pipeline(): RenderPipeline = adaptedPipeline
    }

    private class Pipeline(
        original: RenderPipeline,
        format: GpuFormat,
    ) : RenderPipeline(
            original.location,
            original.vertexShader,
            original.fragmentShader,
            original.shaderDefines,
            original.bindGroupLayouts,
            colorTargets(original, format),
            original.depthStencilState,
            original.polygonMode,
            original.isCull,
            original.vertexFormatBindings,
            original.primitiveTopology,
            original.sortKey,
        ) {
        private companion object {
            private fun colorTargets(
                original: RenderPipeline,
                format: GpuFormat,
            ): Array<ColorTargetState> {
                check(original.colorTargetStates.size == 1) { "Native font capture requires exactly one color attachment." }
                val target = checkNotNull(original.colorTargetState) { "Native font capture requires a color attachment declaration." }
                return arrayOf(ColorTargetState(target.blendFunction(), format, target.writeMask()))
            }
        }
    }
}
