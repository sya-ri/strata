package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.TextureFormat
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryStack
import java.util.OptionalInt

/**
 * Implements Canvas capture for the separate-sampler Blaze3D family with native GPU fences and version-owned OpenGL queue flushing.
 *
 * Calls are render-thread confined; Minecraft owns compiled pipelines while captures retain temporary source views, vertex buffers, and extent uniforms through completion.
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
        } catch (failure: Throwable) {
            FabricNativeCanvasPartialTarget.fail(target, physicalSize, failure)
        }
        return FabricNativeCanvasTarget.create(target)
    }

    @JvmSynthetic
    override fun fence(): NativeCanvasFence {
        RenderSystem.assertOnRenderThread()
        val fence = RenderSystem.getDevice().createCommandEncoder().createFence()
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
     * Temporary source views, vertex buffers, and immutable extent uniforms transfer to the capture before native use and survive its completion fence.
     * The externally owned texture remains unchanged and no source pixels are read back to the CPU.
     *
     * @param lease stable external texture, extent, and orientation borrowed through capture completion.
     * @param target device-owned destination protected by its existing lifetime permit.
     * @param retain capture-owned resource receiver invoked before each temporary resource is used by GPU work.
     * @throws IllegalArgumentException when source extent, format, or native sampling capabilities are unsupported.
     * @throws Throwable when pipeline preparation or sampling fails; the device still fences issued capture work.
     */
    @JvmSynthetic
    internal fun copy(
        lease: MinecraftCanvasTextureLease,
        target: FabricNativeCanvasTarget,
        retain: (AutoCloseable) -> Unit,
    ) {
        RenderSystem.assertOnRenderThread()
        val texture = lease.texture
        require(texture.isClosed.not()) { "A Canvas source texture must remain open for its entire lease." }
        require(texture.depthOrLayers == 1) { "Canvas accepts only ordinary two-dimensional images." }
        require((texture.usage() and GpuTexture.USAGE_TEXTURE_BINDING) != 0) { "A Canvas texture must support texture binding." }
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
        val sourceView = device.createTextureView(texture, 0, 1)
        retain(sourceView)
        val vertexBuffer = device.createBuffer({ "Strata Canvas fullscreen triangle" }, GpuBuffer.USAGE_VERTEX, 1)
        retain(vertexBuffer)
        val uniform = createUniform(target.size)
        retain(uniform)
        device
            .createCommandEncoder()
            .createRenderPass({ "Strata Canvas capture" }, checkNotNull(target.renderTarget.colorTextureView), OptionalInt.empty())
            .use { pass ->
                pass.setPipeline(pipeline)
                pass.setUniform("CanvasCapture", uniform)
                pass.setVertexBuffer(0, vertexBuffer)
                pass.bindTexture("InSampler", sourceView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
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

    private fun createUniform(size: IntSize): GpuBuffer =
        MemoryStack.stackPush().use { stack ->
            val bytes = stack.malloc(16)
            bytes
                .putInt(size.width)
                .putInt(size.height)
                .putInt(0)
                .putInt(0)
                .flip()
            RenderSystem.getDevice().createBuffer({ "Strata Canvas capture extent" }, GpuBuffer.USAGE_UNIFORM, bytes)
        }

    private fun pipeline(origin: MinecraftCanvasTextureOrigin): RenderPipeline {
        val suffix = if (origin == MinecraftCanvasTextureOrigin.TopLeft) "top_left" else "bottom_left"
        return RenderPipeline
            .builder()
            .withLocation(minecraftResourceLocation("strata", "pipeline/canvas_$suffix"))
            .withVertexShader(minecraftResourceLocation("strata", "core/canvas"))
            .withFragmentShader(minecraftResourceLocation("strata", "core/canvas_$suffix"))
            .withSampler("InSampler")
            .withUniform("CanvasCapture", UniformType.UNIFORM_BUFFER)
            .canvasOutput()
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build()
    }

    private fun shader(
        stage: ShaderType,
        origin: MinecraftCanvasTextureOrigin,
    ): String =
        when (stage) {
            ShaderType.VERTEX -> FabricNativeCanvasShaders.vertex.replace("#version 150", "#version 330")
            ShaderType.FRAGMENT -> FabricNativeCanvasShaders.bufferedFragment(origin).replace("#version 150", "#version 330")
        }
}
