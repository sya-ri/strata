package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.backend.opengl.GlTexture
import com.mojang.renderpearl.backend.opengl.GlTextureView
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Retains the target's color, depth, and view resources until their native destruction is acknowledged.
 *
 * The render-thread caller must construct this probe before closing [target], which clears its attachment fields.
 * OpenGL releases these owned resources synchronously; Vulkan acknowledgements come from successful native destroy callbacks.
 * The bounded resource list belongs only to this target and no global retention map is created.
 * Unknown native resource implementations fail during probing so their permits cannot be released without proof.
 */
@JvmSynthetic
internal fun trackCanvasDestruction(target: RenderTarget): FabricNativeCanvasDestruction {
    val resources = listOfNotNull(target.colorTexture, target.depthTexture, target.colorTextureView, target.depthTextureView)
    return FabricNativeCanvasResourceDestruction(resources)
}

/**
 * Retains every allocated portable texture and view until its physical native destruction is acknowledged.
 *
 * The render-thread owner supplies only its allocated resources, including a partial texture without a view when initialization failed.
 * The list is copied before native close clears mutable fields; no sampler-cache object, screen, or global map is retained.
 * OpenGL destruction is synchronous; Vulkan portable storage completes physical retirement at its already-fenced close boundary.
 * The later host destruction queue remains safe because successful native destruction is acknowledged and its repeated dispatch is inert.
 * Unknown native implementations fail during probing so an unproven release cannot return the generation's permit.
 */
@JvmSynthetic
internal fun trackPortableDestruction(resources: List<AutoCloseable>): FabricNativeCanvasDestruction = FabricNativeCanvasResourceDestruction(resources.toList())

/**
 * Retains only one storage owner's fixed native resources until successful physical destruction.
 *
 * This implementation stays private to the adapter; callers receive only the existing destruction contract.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught")
private class FabricNativeCanvasResourceDestruction(
    private val resources: List<AutoCloseable>,
) : FabricNativeCanvasDestruction {
    // Native callbacks may fail independently; preserve the first failure after finishing other eligible releases.
    override fun completeRetirement() {
        var failure: Throwable? = null
        resources.asReversed().forEach { resource ->
            if (resource is VulkanGpuTexture || resource is VulkanGpuTextureView) {
                try {
                    checkNotNull(resource as? FabricVulkanDestroyedResource) {
                        "Strata Vulkan destruction acknowledgement is unavailable."
                    }.strataDestroyCompletedResource()
                } catch (caught: Throwable) {
                    val primary = failure
                    if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
                }
            }
        }
        failure?.let { throw it }
    }

    override fun isDestroyed(): Boolean =
        resources.all { resource ->
            when (resource) {
                is VulkanGpuTexture, is VulkanGpuTextureView -> {
                    checkNotNull(resource as? FabricVulkanDestroyedResource) {
                        "Strata Vulkan destruction acknowledgement is unavailable."
                    }.strataCanvasResourceDestroyed()
                }

                is GlTexture, is GlTextureView -> {
                    true
                }

                else -> {
                    error("Strata cannot establish physical destruction of this native resource.")
                }
            }
        }
}
