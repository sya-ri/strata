package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.TextureFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.lwjgl.opengl.GL11
import java.util.OptionalInt

/**
 * Implements the initial Blaze3D GPU-texture Canvas family with native GPU fences and version-owned OpenGL queue flushing.
 *
 * Calls are render-thread confined; Minecraft owns compiled pipelines and its immutable fullscreen vertex buffer.
 * Texture capture preserves straight RGBA through texelFetch and never changes the external texture's sampling parameters.
 * Native failures propagate to the device so leased inputs and owned targets remain fenced before cleanup.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught")
internal object FabricNativeCanvasDriver : NativeCanvasDriver {
    private val topLeftPipeline = pipeline(MinecraftCanvasTextureOrigin.TopLeft)
    private val bottomLeftPipeline = pipeline(MinecraftCanvasTextureOrigin.BottomLeft)

    @JvmSynthetic
    override fun createTarget(
        physicalSize: IntSize,
        depth: Boolean,
    ): NativeCanvasTarget {
        RenderSystem.assertOnRenderThread()
        FabricNativeCanvasShaders.requireSupportedExtent(physicalSize)
        val target = OffscreenTarget(depth)
        try {
            target.createBuffers(physicalSize.width, physicalSize.height)
            target.setFilterMode(FilterMode.NEAREST)
        } catch (failure: Throwable) {
            FabricNativeCanvasPartialTarget.fail(target, physicalSize, failure)
        }
        return FabricNativeCanvasTarget.create(target)
    }

    @JvmSynthetic
    override fun fence(): NativeCanvasFence {
        RenderSystem.assertOnRenderThread()
        val fence = GpuFence()
        GL11.glFlush()
        return FabricNativeCanvasGpuFence(fence)
    }

    @JvmSynthetic
    override fun finish() {
        RenderSystem.assertOnRenderThread()
        GL11.glFinish()
    }

    /**
     * Samples one leased RGBA8 image into the reserved offscreen target on the render thread.
     *
     * The device owns its compiled pipeline and fullscreen buffer; this family allocates no per-capture texture view.
     * The externally owned texture remains unchanged and no source pixels are read back to the CPU.
     *
     * @param lease stable external texture, extent, and orientation borrowed through capture completion.
     * @param target device-owned destination protected by its existing lifetime permit.
     * @param retain unused ownership callback retained for the shared capture contract; this family creates no temporary capture resources.
     * @throws IllegalArgumentException when source extent, format, or native sampling capabilities are unsupported.
     * @throws Throwable when pipeline preparation or sampling fails; the device still fences issued capture work.
     */
    @Suppress("UNUSED_PARAMETER")
    @JvmSynthetic
    internal fun copy(
        lease: MinecraftCanvasTextureLease,
        target: FabricNativeCanvasTarget,
        retain: (AutoCloseable) -> Unit,
    ) {
        RenderSystem.assertOnRenderThread()
        val texture = lease.texture
        require(texture.isClosed.not()) { "A Canvas source texture must remain open for its entire lease." }
        require(texture.format == TextureFormat.RGBA8) { "Canvas accepts only RGBA8 straight-alpha color textures." }
        FabricNativeCanvasShaders.requireSupportedExtent(lease.size)
        require(texture.getWidth(0) == lease.size.width && texture.getHeight(0) == lease.size.height) {
            "A Canvas lease extent must match native mip level zero."
        }
        val origin = lease.origin
        val pipeline = if (origin == MinecraftCanvasTextureOrigin.TopLeft) topLeftPipeline else bottomLeftPipeline
        val device = RenderSystem.getDevice()
        check(device.precompilePipeline(pipeline) { _, stage -> shader(stage, origin) }.isValid) {
            "Canvas capture pipeline compilation failed."
        }
        device.createCommandEncoder().createRenderPass(checkNotNull(target.renderTarget.colorTexture), OptionalInt.empty()).use { pass ->
            pass.setPipeline(pipeline)
            pass.setUniform("CanvasTargetExtent", target.size.width, target.size.height, 0)
            pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer())
            pass.bindSampler("InSampler", texture)
            pass.draw(0, 3)
        }
    }

    /**
     * Clears and borrows an offscreen target for one render-thread callback without reading GPU pixels.
     *
     * The device owns the target and renderer; the callback borrows its target and encoder only until the context expires.
     * Issued work remains covered by the capture fence even if the callback fails.
     *
     * @param target exclusively borrowed target with its reserved lifetime permit.
     * @param logicalSize final positive logical destination extent.
     * @param frameTime timestamp shared by the actual native presentation.
     * @param renderer attachment-owned renderer invoked exactly once after clearing the target.
     * @return the renderer's optional immutable physical-size, top-left snapshot.
     * @throws Throwable when target setup or rendering fails; the borrowed context still expires before return.
     */
    @JvmSynthetic
    internal fun render(
        target: FabricNativeCanvasTarget,
        logicalSize: IntSize,
        frameTime: FrameTime,
        renderer: MinecraftCanvasRenderer,
    ): DrawImage? {
        RenderSystem.assertOnRenderThread()
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val color = checkNotNull(target.renderTarget.colorTexture)
        val depth = target.renderTarget.depthTexture
        if (depth == null) encoder.clearColorTexture(color, 0) else encoder.clearColorAndDepthTextures(color, 0, depth, 1.0)
        val context = MinecraftCanvasContext.create(target.renderTarget, encoder, logicalSize, target.size, frameTime)
        return try {
            renderer.render(context)
        } finally {
            context.expire()
        }
    }

    @JvmSynthetic
    override fun drainRetirements() = Unit

    private class OffscreenTarget(
        depth: Boolean,
    ) : RenderTarget("Strata Canvas", depth)

    private fun pipeline(origin: MinecraftCanvasTextureOrigin): RenderPipeline {
        val suffix = if (origin == MinecraftCanvasTextureOrigin.TopLeft) "top_left" else "bottom_left"
        return RenderPipeline
            .builder()
            .withLocation(minecraftResourceLocation("strata", "pipeline/canvas_$suffix"))
            .withVertexShader(minecraftResourceLocation("strata", "core/canvas"))
            .withFragmentShader(minecraftResourceLocation("strata", "core/canvas_$suffix"))
            .withSampler("InSampler")
            .withUniform("CanvasTargetExtent", UniformType.IVEC3)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withoutBlend()
            .withCull(false)
            .withVertexFormat(VertexFormat.builder().build(), VertexFormat.Mode.TRIANGLES)
            .build()
    }

    private fun shader(
        stage: ShaderType,
        origin: MinecraftCanvasTextureOrigin,
    ): String =
        when (stage) {
            ShaderType.VERTEX -> FabricNativeCanvasShaders.vertex
            ShaderType.FRAGMENT -> FabricNativeCanvasShaders.fragment(origin)
        }
}
