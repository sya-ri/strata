package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.opengl.GlTextureView
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Retains the target's color, depth, and view resources until their native destruction is acknowledged.
 *
 * The render-thread caller must construct this probe before closing [target], which clears its attachment fields.
 * OpenGL releases these owned resources synchronously; Vulkan acknowledgements come from successful native destroy callbacks.
 * The bounded resource list belongs only to this target and no global retention map is created.
 * Unknown native resource implementations fail during probing so their permits cannot be released without proof.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun trackCanvasDestruction(target: RenderTarget): FabricNativeCanvasDestruction {
    val resources = listOfNotNull(target.colorTexture, target.depthTexture, target.colorTextureView, target.depthTextureView)
    return trackNativeDestruction(resources)
}

/**
 * Retains every allocated portable texture and view until its physical native destruction is acknowledged.
 *
 * The render-thread owner supplies only its allocated resources, including a partial texture without a view when initialization failed.
 * The list is copied before native close clears mutable fields; no sampler-cache object, screen, or global map is retained.
 * OpenGL destruction is synchronous, while Vulkan probes the actual native destroy callbacks after close and queue retirement.
 * Unknown native implementations fail during probing so an unproven release cannot return the generation's permit.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun trackPortableDestruction(resources: List<AutoCloseable>): FabricNativeCanvasDestruction = trackNativeDestruction(resources.toList())

@OptIn(InternalStrataRuntimeApi::class)
private fun trackNativeDestruction(resources: List<AutoCloseable>): FabricNativeCanvasDestruction =
    FabricNativeCanvasDestruction {
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
