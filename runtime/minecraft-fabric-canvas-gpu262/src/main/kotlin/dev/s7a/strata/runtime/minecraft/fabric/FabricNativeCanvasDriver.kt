package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.vulkan.VulkanDevice
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.runtime.minecraft.fabric.mixin.vulkan.FabricVulkanCanvasDeviceAccessor
import dev.s7a.strata.runtime.minecraft.fabric.mixin.vulkan.FabricVulkanCanvasEncoderAccessor
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack
import java.util.Optional

/**
 * Implements Canvas capture through explicitly submitted Blaze3D commands on OpenGL or Vulkan.
 *
 * Calls run on the owning render thread and never use a backend-specific handle or CPU readback.
 * The device owns compiled pipelines, captures own temporary source views and extent uniforms, and the Canvas manager owns color/depth targets.
 * Successful and failing callbacks leave recorded work for the manager's immediately following capture fence to submit once.
 * Ordinary paths perform no explicit wait; backend submission may apply backpressure to already-submitted GPU work.
 * No operation waits for unsubmitted or unconsumed GUI commands.
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
        return FabricNativeCanvasGpuFence(submittedFence())
    }

    @JvmSynthetic
    override fun finish() {
        RenderSystem.assertOnRenderThread()
        submittedFence().use { fence -> check(fence.awaitCompletion(Long.MAX_VALUE)) { "Canvas device completion failed." } }
    }

    @JvmSynthetic
    override fun drainRetirements() {
        RenderSystem.assertOnRenderThread()
        val backend = (RenderSystem.getDevice() as FabricVulkanCanvasDeviceAccessor).strataCanvasBackend()
        if (backend is VulkanDevice) {
            val encoder = backend.createCommandEncoder() as FabricVulkanCanvasEncoderAccessor
            encoder.strataCanvasDestructionQueue().close()
        }
    }

    /**
     * Captures a leased ordinary RGBA8 image through a nearest offscreen sample pass, including sample-only inputs.
     *
     * Temporary source views and immutable extent uniforms transfer to the capture before GPU use and remain open until its completion fence.
     * Unsupported input shape, format, or usage throws before sampling; submission failures leave the manager responsible for quarantine.
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
        require(texture.format == GpuFormat.RGBA8_UNORM) { "Canvas accepts only RGBA8 straight-alpha color textures." }
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
        val uniform = createUniform(target.size)
        retain(uniform)
        val encoder = device.createCommandEncoder()
        encoder.createRenderPass({ "Strata Canvas capture" }, checkNotNull(target.renderTarget.colorTextureView), Optional.empty()).use { pass ->
            pass.setPipeline(pipeline)
            pass.setUniform("CanvasCapture", uniform)
            pass.bindTexture("InSampler", sourceView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
            pass.draw(3, 1, 0, 0)
        }
    }

    /**
     * Clears and lends a target and encoder for exactly one custom-renderer invocation.
     *
     * The context expires before the manager's capture-fence submission, even on failure.
     * The manager preserves callback exceptions as primary when subsequent fence submission also fails.
     * Returned immutable pixels describe this same physical target generation and are never manufactured by a readback.
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
        // The no-argument vector constructor initializes its alpha component to one.
        val transparent = Vector4f(0f, 0f, 0f, 0f)
        if (depth == null) encoder.clearColorTexture(color, transparent) else encoder.clearColorAndDepthTextures(color, transparent, depth, 1.0)
        val context = MinecraftCanvasContext.create(target.renderTarget, encoder, logicalSize, target.size, frameTime)
        return try {
            renderer.render(context)
        } finally {
            context.expire()
        }
    }

    private class OffscreenTarget(
        depth: Boolean,
    ) : RenderTarget("Strata Canvas", depth, GpuFormat.RGBA8_UNORM)

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

    private fun submittedFence(): GpuFence {
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val fence = encoder.createFence()
        try {
            encoder.submit()
        } catch (failure: Throwable) {
            try {
                fence.close()
            } catch (cleanup: Throwable) {
                FabricMinecraftFailures.addSuppressed(failure, cleanup)
            }
            throw failure
        }
        return fence
    }

    private fun pipeline(origin: MinecraftCanvasTextureOrigin): RenderPipeline {
        val suffix = if (origin == MinecraftCanvasTextureOrigin.TopLeft) "top_left" else "bottom_left"
        return RenderPipeline
            .builder()
            .withLocation(minecraftResourceLocation("strata", "pipeline/canvas_$suffix"))
            .withVertexShader(minecraftResourceLocation("strata", "core/canvas"))
            .withFragmentShader(minecraftResourceLocation("strata", "core/canvas_$suffix"))
            .withBindGroupLayout(
                BindGroupLayout
                    .builder()
                    .withSampler("InSampler")
                    .withUniform("CanvasCapture", UniformType.UNIFORM_BUFFER)
                    .build(),
            ).withDepthStencilState(Optional.empty())
            .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
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
