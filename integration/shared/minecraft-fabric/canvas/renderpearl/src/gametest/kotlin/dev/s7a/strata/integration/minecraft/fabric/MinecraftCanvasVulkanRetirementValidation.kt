package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonObject
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.GpuFence
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.FabricVulkanDestroyedResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.joml.Vector4f

/**
 * Checks the actual Vulkan physical-destruction entry after real upload completion, outside performance sampling.
 *
 * A close request must not acknowledge physical release, and an unclosed resource cannot be retired.
 * Re-entering the host destroy method used by its queued callbacks must be inert after successful physical release.
 * The runner waits for the real initialization fence through the existing client scheduler without submitting GPU work itself.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasVulkanRetirementValidation : MinecraftCanvasInputValidation {
    // Expected and independent native failures must keep their original identity while every owned close is attempted.
    @Suppress("TooGenericExceptionCaught")
    override fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        val selected = context.onClient { MinecraftCanvasTestBackend.parse(RenderSystem.getDevice().deviceInfo.backendName()) }
        if (selected != MinecraftCanvasTestBackend.Vulkan) return
        captureOrigins(context)
        val resources = context.onClient { createResources() }
        var failure: Throwable? = null
        try {
            context.waitFor { resources.third.awaitCompletion(0L) }
            context.onClient { verifyRetirement(resources.first, resources.second) }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { resources.third.close() } },
                { context.onClient { resources.second.close() } },
                { context.onClient { resources.first.close() } },
            )
        }
    }

    private fun captureOrigins(context: MinecraftCanvasTestContext) {
        val report =
            context.onClient {
                MinecraftNativeArtifactOrigins
                    .capture(
                        javaClass.classLoader,
                        mapOf(
                            "fixture" to javaClass.name,
                            "fabric" to FabricMinecraftScreen::class.java.name,
                            "bridge" to FabricVulkanDestroyedResource::class.java.name,
                            "nativeTexture" to VulkanGpuTexture::class.java.name,
                            "nativeView" to VulkanGpuTextureView::class.java.name,
                        ),
                    ).apply {
                        addProperty("max_heap_bytes", Runtime.getRuntime().maxMemory())
                        addProperty("native_driver", RenderSystem.getDevice().deviceInfo.toString())
                        add(
                            "registered_mixin_resources",
                            JsonObject().apply {
                                ArtifactIdentity
                                    .loadedResourceTree(javaClass.classLoader, "strata.canvas.vulkan.mixins.json")
                                    .forEach { (key, value) -> addProperty(key, value) }
                            },
                        )
                    }
            }
        PerformanceJson.writeNew(context.outputDirectory.resolve("strata-vulkan-retirement-origins.json"), report)
        LoadedArtifactMetadata.verifyComplete(report)
    }

    // Failed preparation can still leave owned partial allocations; ordinary close preserves the host queue's safety.
    @Suppress("TooGenericExceptionCaught")
    private fun createResources(): Triple<VulkanGpuTexture, VulkanGpuTextureView, GpuFence> {
        val device = RenderSystem.getDevice()
        val texture = device.createTexture("Strata fenced Vulkan retirement control", GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1)
        var view: GpuTextureView? = null
        try {
            val nativeTexture = checkNotNull(texture as? VulkanGpuTexture)
            val allocatedView = device.createTextureView(nativeTexture)
            view = allocatedView
            val ownedView = checkNotNull(allocatedView as? VulkanGpuTextureView)
            val encoder = device.createCommandEncoder()
            encoder.clearColorTexture(texture, Vector4f(1f, 0f, 0f, 1f))
            return Triple(nativeTexture, ownedView, encoder.createFence())
        } catch (caught: Throwable) {
            runCanvasTestCleanup(caught, { view?.close() }, { texture.close() })
            throw caught
        }
    }

    private fun verifyRetirement(
        texture: VulkanGpuTexture,
        view: VulkanGpuTextureView,
    ) {
        val textureState = texture as FabricVulkanDestroyedResource
        val viewState = view as FabricVulkanDestroyedResource
        requireUnclosedRefused(viewState)
        requireUnclosedRefused(textureState)
        check(viewState.strataCanvasResourceDestroyed().not() && textureState.strataCanvasResourceDestroyed().not())
        view.close()
        texture.close()
        check(viewState.strataCanvasResourceDestroyed().not() && textureState.strataCanvasResourceDestroyed().not()) {
            "Logical Vulkan close must not acknowledge physical destruction."
        }
        viewState.strataDestroyCompletedResource()
        textureState.strataDestroyCompletedResource()
        check(viewState.strataCanvasResourceDestroyed() && textureState.strataCanvasResourceDestroyed())
        view.destroy()
        texture.destroy()
        check(viewState.strataCanvasResourceDestroyed() && textureState.strataCanvasResourceDestroyed()) {
            "The later queued destroy entry must preserve successful physical acknowledgement."
        }
    }

    private fun requireUnclosedRefused(resource: FabricVulkanDestroyedResource) {
        val refused =
            try {
                resource.strataDestroyCompletedResource()
                false
            } catch (expected: IllegalStateException) {
                true
            }
        check(refused) { "An unclosed Vulkan resource must not be physically retired." }
    }
}
