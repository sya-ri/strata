package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.TextureFormat
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture
import java.util.OptionalInt

/**
 * Checks whether one immutable image fits the active device's two-dimensional texture limit before direct-cache reservation.
 *
 * @param image candidate source borrowed on the render thread.
 * @return true when both source dimensions can be allocated as one RGBA texture.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftSampledImage(image: DrawImage): Boolean {
    RenderSystem.assertOnRenderThread()
    val maximum = RenderSystem.getDevice().maxTextureSize
    return image.size.width <= maximum && image.size.height <= maximum
}

/**
 * Preserves exact CPU region sampling on adapters without a generation-owned GPU lookup pass.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftExactSampling(): Boolean {
    RenderSystem.assertOnRenderThread()
    return false
}

/**
 * Enforces the capability boundary before unsupported lookup allocation or source submission.
 * The partitioner always selects CPU region sampling on this adapter.
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun initializeFabricMinecraftSampledTexture(
    indices: NativeImage,
    size: IntSize,
    source: AbstractTexture,
    retain: (AbstractTexture, NativeGuiResource) -> Unit,
): Unit = error("This adapter uses exact CPU region sampling.")

/**
 * Owns exact-adapter allocations and preserves partial initialization until generation-fenced destruction.
 * Texture-manager close is inert; only the owning generation calls [destroy].
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftPortableNativeTexture : AbstractTexture() {
    private val owned = FabricMinecraftNativeStorage()
    private var closeRequested = false

    /**
     * Allocates each native object into its owned field before the next operation can fail.
     *
     * The image is borrowed only for this render-thread upload; its outer resource owns its CPU lifetime.
     * Device sampler caches remain external, and no partial allocation is eagerly destroyed.
     */
    @JvmSynthetic
    internal fun initialize(pixels: NativeImage) {
        RenderSystem.assertOnRenderThread()
        val device = RenderSystem.getDevice()
        val maximum = device.maxTextureSize
        require(pixels.width <= maximum && pixels.height <= maximum) { "A portable GUI image exceeds the active device texture extent limit." }
        texture = owned.allocate { device.createTexture({ "Strata immutable portable layer" }, TextureFormat.RGBA8, pixels.width, pixels.height, 1) }
        setClamp(true)
        setFilter(false, false)
        device.createCommandEncoder().writeToTexture(checkNotNull(texture), pixels)
    }

    /**
     * Requests independent native releases only after initialization and GUI-use fences complete.
     *
     * Successful release steps are not repeated after another step fails.
     * Destruction probes retain the original allocated objects before their mutable fields are cleared.
     */
    @JvmSynthetic
    internal fun destroy() {
        RenderSystem.assertOnRenderThread()
        if (closeRequested) return
        owned.close()
        texture = null
        closeRequested = true
    }

    /**
     * Acknowledges all original native allocations without waiting, after every close request has succeeded.
     *
     * Unknown or incomplete physical destruction retains the generation's permit through the caller's polling policy.
     */
    @JvmSynthetic
    internal fun isDestroyed(): Boolean {
        RenderSystem.assertOnRenderThread()
        check(closeRequested) { "Portable GUI destruction is queried only after successful close." }
        return owned.isDestroyed()
    }

    @JvmSynthetic
    override fun close() = Unit

    /**
     * Initializes a generation-owned intermediate with the same format, flags and extent as an ordinary composition destination.
     * It is never a GUI output; ordered offscreen passes borrow it only before the generation seals.
     */
    @JvmSynthetic
    internal fun initializeCompositionScratch(size: IntSize) {
        RenderSystem.assertOnRenderThread()
        val device = RenderSystem.getDevice()
        texture = owned.allocate { device.createTexture({ "Strata preparation composition intermediate" }, TextureFormat.RGBA8, size.width, size.height, 1) }
        setClamp(true)
        setFilter(false, false)
    }

    /**
     * Records complete ordered RGBA8 composition into alternating owned destinations without native blending.
     * Source views belong to the full-presentation pin; owned targets and metadata transfer before use, and optional scratch belongs to the same generation.
     * Every pass covers the complete target, preserving preceding pixels outside CPU-resolved physical coverage.
     */
    @JvmSynthetic
    internal fun initializeComposition(
        indices: NativeImage,
        factors: NativeImage,
        size: IntSize,
        sources: List<AbstractTexture?>,
        scratch: AbstractTexture? = null,
    ) {
        RenderSystem.assertOnRenderThread()
        val device = RenderSystem.getDevice()
        val outputs =
            if (scratch == null) {
                (0..1).map {
                    owned.allocate { device.createTexture({ "Strata ordered composition destination" }, TextureFormat.RGBA8, size.width, size.height, 1) }
                }
            } else {
                val output = owned.allocate { device.createTexture({ "Strata ordered composition destination" }, TextureFormat.RGBA8, size.width, size.height, 1) }
                val intermediate = scratch.getTexture()
                if (sources.size % 2 == 0) listOf(output, intermediate) else listOf(intermediate, output)
            }
        val indexTexture = owned.allocate { device.createTexture({ "Strata ordered composition axes" }, TextureFormat.RGBA8, indices.width, indices.height, 1) }

        val factorTexture = owned.allocate { device.createTexture({ "Strata binary32 source factors" }, TextureFormat.RGBA8, factors.width, factors.height, 1) }

        val encoder = device.createCommandEncoder()
        encoder.writeToTexture(indexTexture, indices)
        encoder.writeToTexture(factorTexture, factors)
        encoder.clearColorTexture(outputs[0], 0)
        check(
            device
                .precompilePipeline(fabricMinecraftCompositionPipeline()) { _, stage ->
                    when (stage) {
                        ShaderType.VERTEX -> FabricMinecraftCompositionShaders.vertex
                        ShaderType.FRAGMENT -> FabricMinecraftCompositionShaders.fragment
                    }
                }.isValid,
        ) { "Ordered portable composition pipeline compilation failed." }
        val vertexBuffer = RenderSystem.getQuadVertexBuffer()
        sources.forEachIndexed { index, source ->
            val previous = outputs[index % 2]
            val target = outputs[(index + 1) % 2]
            encoder.createRenderPass(target, OptionalInt.empty()).use { pass ->
                pass.setPipeline(fabricMinecraftCompositionPipeline())
                pass.bindSampler("InSampler", source?.getTexture() ?: previous)
                pass.bindSampler("DestinationSampler", previous)
                pass.bindSampler("IndexSampler", indexTexture)
                pass.bindSampler("FactorSampler", factorTexture)
                pass.setVertexBuffer(0, vertexBuffer)
                pass.draw(index * 3, 3)
            }
        }
        texture = outputs[sources.size % 2]

        setClamp(true)
        setFilter(false, false)
    }
}
