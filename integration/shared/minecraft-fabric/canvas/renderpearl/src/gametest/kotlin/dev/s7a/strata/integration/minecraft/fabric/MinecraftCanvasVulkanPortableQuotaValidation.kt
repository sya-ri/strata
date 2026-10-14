package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResourceOwnerId
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResourceSet
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResources
import dev.s7a.strata.runtime.minecraft.fabric.FabricVulkanDestroyedResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture
import java.lang.reflect.InvocationTargetException

/**
 * Exercises the real portable presenter's three-generation quota after completed initialization and ordinary retirement.
 * The control borrows the actual Fabric driver and allocates through its production transfer entry, with no GUI use or explicit submission.
 * It observes all decisive releases and the fourth reservation in one client callback before the host destruction queue can advance.
 * A counterfactual without synchronous physical retirement must retain its observed refusal before this regression fails.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasVulkanPortableQuotaValidation : MinecraftCanvasInputValidation {
    // Native and assertion failures retain their identity while every independently owned cleanup is attempted.
    @Suppress("TooGenericExceptionCaught")
    override fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        val backend = context.onClient { MinecraftCanvasTestBackend.parse(RenderSystem.getDevice().deviceInfo.backendName()) }
        if (backend != MinecraftCanvasTestBackend.Vulkan) return
        val control = context.onClient { createControl() }
        var failure: Throwable? = null
        try {
            context.onClient { repeat(3) { initialize(control) } }
            context.waitFor { control.generations.all { initialized(control, it) } }
            val report = context.onClient { releaseAndReserve(control) }
            PerformanceJson.writeNew(context.outputDirectory.resolve("strata-vulkan-portable-quota.json"), report)
            check(control.physicallyReturned) { "The three initialized generations must physically retire and return every original permit inside their close boundary." }
            check(control.refusal == null) { "Completed portable retirement retained the presenter's three-generation quota: ${control.refusal}" }
            context.waitFor { control.generations.all { initialized(control, it) } }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { control.generations.forEach { control.resources.release(it.token) } } },
                {
                    context.waitFor {
                        NativeCanvasDevices.poll()
                        records(control).isEmpty()
                    }
                },
                { context.onClient { verifyPhysicalRelease(control) } },
                { context.onClient { control.generations.filter { initialized(control, it) }.forEach { it.pixels?.close() } } },
                {
                    val terminal = context.onClient { snapshot(control) }
                    PerformanceJson.writeNew(context.outputDirectory.resolve("strata-vulkan-portable-quota-terminal.json"), terminal)
                },
            )
        }
    }

    private fun createControl(): Control {
        val driverClass = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricNativeCanvasDriver", true, javaClass.classLoader)
        val driver = driverClass.getField("INSTANCE").get(null) as NativeCanvasDriver
        val resources = NativeCanvasDevices.device(driver).guiResources
        check(resources.retainedSetCount() <= 61) { "The real device must have space for all three control-owned generations." }
        return Control(resources, resources.createOwnerId())
    }

    // Partially allocated native objects and the original failure remain owned until the registry's real fence settles.
    @Suppress("TooGenericExceptionCaught")
    private fun initialize(
        control: Control,
        token: NativeGuiResourceSet = control.resources.reserve(control.owner, listOf(IntSize(1, 1))),
    ) {
        val generation = Generation(token)
        control.generations.add(generation)
        var failure: Throwable? = null
        try {
            val pixels = NativeImage(1, 1, false)
            generation.pixels = pixels
            pixels.setPixel(0, 0, 0xFFFF0000.toInt())
            val retain: (AbstractTexture, NativeGuiResource) -> Unit = { texture, storage ->
                control.resources.add(token, storage)
                generation.texture = texture
                generation.storage = storage
            }
            initializeTexture(pixels, retain)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                {
                    generation.texture?.let { texture ->
                        val owned = checkNotNull(read(texture, "owned"))
                        val allocations = read(owned, "resources") as Array<*>
                        generation.nativeTexture = allocations.filterIsInstance<VulkanGpuTexture>().singleOrNull()
                        generation.nativeView = allocations.filterIsInstance<VulkanGpuTextureView>().singleOrNull()
                    }
                },
                { control.resources.seal(token) },
            )
        }
        checkNotNull(generation.nativeTexture)
        checkNotNull(generation.nativeView)
    }

    @Suppress("StringLiteralComparison") // The method selector is JVM adapter metadata, not a domain discriminator.
    private fun initializeTexture(
        pixels: NativeImage,
        retain: (AbstractTexture, NativeGuiResource) -> Unit,
    ) {
        val type = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftPortableTextureOwnershipKt", true, javaClass.classLoader)
        val method = type.declaredMethods.single { it.name == "initializeFabricMinecraftPortableTexture" && it.parameterCount == 2 }
        check(method.trySetAccessible()) { "The actual portable texture transfer entry is inaccessible." }
        try {
            method.invoke(null, pixels, retain)
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.cause ?: wrapped
        }
    }

    private fun initialized(
        control: Control,
        generation: Generation,
    ): Boolean {
        val record = records(control).singleOrNull { read(it, "token") === generation.token } ?: return true
        val fence = read(record, "initializationFence") as? NativeCanvasFence
        return fence == null || fence.isSignalled()
    }

    private fun releaseAndReserve(control: Control): JsonObject {
        val initial = snapshot(control)
        check(records(control).size == 3 && control.generations.all { generation -> generation.nativeResources.all { it.strataCanvasResourceDestroyed().not() } })
        check(records(control).all { unused(it) }) { "This control isolates initialized resources that were never borrowed by GUI output." }
        control.generations.forEach { control.resources.release(it.token) }
        val released = snapshot(control)
        control.physicallyReturned = records(control).isEmpty() && control.generations.all { generation -> generation.nativeResources.all { it.strataCanvasResourceDestroyed() } }
        val fourth =
            try {
                control.resources.reserve(control.owner, listOf(IntSize(1, 1)))
            } catch (refused: IllegalStateException) {
                control.refusal = refused
                null
            }
        fourth?.let { initialize(control, it) }
        val outcome = snapshot(control)
        val origins =
            LoadedArtifactMetadata.capture(
                javaClass.classLoader,
                mapOf(
                    "fixture" to javaClass.name,
                    "driver" to "dev.s7a.strata.runtime.minecraft.fabric.FabricNativeCanvasDriver",
                    "portableOwner" to checkNotNull(control.generations.first().storage).javaClass.name,
                    "registry" to NativeGuiResources::class.java.name,
                    "nativeTexture" to VulkanGpuTexture::class.java.name,
                    "nativeView" to VulkanGpuTextureView::class.java.name,
                ),
                emptySet(),
            )
        LoadedArtifactMetadata.verifyComplete(origins)
        return JsonObject().apply {
            addProperty("contract", "strata-vulkan-portable-quota-v1")
            addProperty("backend", RenderSystem.getDevice().deviceInfo.toString())
            addProperty("max_heap_bytes", Runtime.getRuntime().maxMemory())
            addProperty("owner", read(control.owner, "value") as Long)
            addProperty("gui_borrowed", false)
            addProperty("fourth_reserved", control.refusal == null)
            addProperty("original_permits_physically_returned", control.physicallyReturned)
            control.refusal?.let {
                addProperty("refusal_class", it.javaClass.name)
                addProperty("refusal_message", it.message)
                addProperty("refusal_stack", it.stackTraceToString())
            }
            add("initial", initial)
            add("after_release", released)
            add("after_fourth", outcome)
            add("origins", origins)
        }
    }

    private fun snapshot(control: Control): JsonObject =
        JsonObject().apply {
            addProperty("device_sets", control.resources.retainedSetCount())
            addProperty("owner_sets", records(control).size)
            add(
                "sets",
                JsonArray().apply {
                    records(control).forEach { record ->
                        add(
                            JsonObject().apply {
                                listOf("sealed", "retired", "quarantined", "pendingGui", "referencesReleased").forEach { name -> addProperty(name, read(record, name) as Boolean) }
                                addProperty("pins", read(record, "pins") as Int)
                                val fence = read(record, "initializationFence") as? NativeCanvasFence
                                addProperty("initialization_fence_present", fence != null)
                                fence?.let { addProperty("initialization_signalled", it.isSignalled()) }
                                addProperty("gui_completion_present", read(record, "guiCompletion") != null)
                                add(
                                    "resource_release_states",
                                    JsonArray().apply { (read(record, "resources") as List<*>).forEach { add(read(checkNotNull(it), "release").toString()) } },
                                )
                            },
                        )
                    }
                },
            )
            add("native_destroyed", JsonArray().apply { control.generations.forEach { generation -> add(JsonArray().apply { generation.nativeResources.forEach { add(it.strataCanvasResourceDestroyed()) } }) } })
        }

    private fun unused(record: Any): Boolean = read(record, "pins") == 0 && (read(record, "pendingGui") as Boolean).not() && read(record, "guiCompletion") == null && (read(record, "quarantined") as Boolean).not()

    private fun records(control: Control): List<Any> = (read(control.resources, "sets") as Map<*, *>).values.filterNotNull().filter { read(it, "owner") == read(control.owner, "value") }

    private fun verifyPhysicalRelease(control: Control) {
        control.generations.forEach { generation ->
            check(generation.nativeResources.all { it.strataCanvasResourceDestroyed() }) { "The actual control-owned native allocations must physically retire before terminal success." }
            generation.nativeView?.destroy()
            generation.nativeTexture?.destroy()
            check(generation.nativeResources.all { it.strataCanvasResourceDestroyed() }) { "A later host destroy dispatch must preserve successful physical acknowledgement." }
        }
    }

    @Suppress("StringLiteralComparison") // Reflection reads exact JVM field names from the real adapter and registry.
    private fun read(
        owner: Any,
        name: String,
    ): Any? {
        val field = checkNotNull(generateSequence<Class<*>>(owner.javaClass) { it.superclass }.firstNotNullOfOrNull { type -> type.declaredFields.singleOrNull { it.name == name } })
        check(field.trySetAccessible()) { "The real resource field is inaccessible: $name" }
        return field.get(owner)
    }

    private class Control(
        val resources: NativeGuiResources,
        val owner: NativeGuiResourceOwnerId,
    ) {
        val generations = ArrayList<Generation>(4)
        var refusal: IllegalStateException? = null
        var physicallyReturned = false
    }

    private class Generation(
        val token: NativeGuiResourceSet,
    ) {
        var pixels: NativeImage? = null
        var texture: AbstractTexture? = null
        var storage: NativeGuiResource? = null
        var nativeTexture: VulkanGpuTexture? = null
        var nativeView: VulkanGpuTextureView? = null

        val nativeResources: List<FabricVulkanDestroyedResource>
            get() = listOfNotNull(nativeTexture, nativeView).map { it as FabricVulkanDestroyedResource }
    }
}
