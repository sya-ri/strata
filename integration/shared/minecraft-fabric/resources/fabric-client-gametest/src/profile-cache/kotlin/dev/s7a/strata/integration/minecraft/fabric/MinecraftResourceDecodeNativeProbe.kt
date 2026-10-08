package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.fabric.loadMinecraftUiImage
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ReloadableResourceManager
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Observes actual public resource loads and the existing native reload hook without retaining decoded cache arrays.
 * Ordinary packaged classes stay on the application loader; baseline archives without derived reuse remain valid controls.
 */
internal class MinecraftResourceDecodeNativeProbe {
    private var oldImage: DrawImage? = null
    private var retiredPixels: WeakReference<IntArray>? = null
    private var adapterCache: Any? = null

    /**
     * Requires fresh identities, exact detached pixels, bounded current ownership, and actual off-thread rejection.
     */
    fun beforeReload() {
        val first = loadMinecraftUiImage(asset)
        val expected = first.copyArgb()
        val cacheType =
            try {
                Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftImageDecodeCache")
            } catch (_: ClassNotFoundException) {
                null
            }
        if (cacheType != null) {
            check(cacheType.classLoader === MinecraftResourceDecodeNativeProbe::class.java.classLoader)
            val part = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftAssets__FabricMinecraftAssetLoaderKt")
            adapterCache = part.getDeclaredField("currentImageDecode").apply { isAccessible = true }.get(null)
            verifyOwnedClose()
        }
        repeat(100) {
            val image = loadMinecraftUiImage(asset)
            check(image !== first && image.copyArgb().contentEquals(expected))
            image.copyArgb().fill(0)
        }
        oldImage = first
        if (cacheType != null) {
            val entry = checkNotNull(entry())
            check(entry.javaClass.getDeclaredField("manager").apply { isAccessible = true }.get(entry) === Minecraft.getInstance().resourceManager)
            val encoded = entry.javaClass.getDeclaredField("encoded").apply { isAccessible = true }.get(entry) as ByteArray
            val decoded = entry.javaClass.getDeclaredField("decoded").apply { isAccessible = true }.get(entry)
            val pixels = decoded.javaClass.getDeclaredField("pixels").apply { isAccessible = true }.get(decoded) as IntArray
            check(encoded.size <= 8 * 1024 * 1024 && encoded.size.toLong() + pixels.size.toLong() * Int.SIZE_BYTES <= 16L * 1024 * 1024)
            retiredPixels = WeakReference(pixels)
        }
        val failure =
            CompletableFuture.supplyAsync { runCatching { loadMinecraftUiImage(asset) }.exceptionOrNull() }.get(10, TimeUnit.SECONDS)
        check(failure is IllegalStateException) { "Public image loading must reject a worker thread before source resolution." }
    }

    /**
     * Requires real reload to release the private entry before a new load, without changing the old public image.
     */
    fun afterReload() {
        if (adapterCache != null) check(entry() == null) { "The real native reload retained decoded resource pixels." }
        val previous = checkNotNull(oldImage)
        val replacement = loadMinecraftUiImage(asset)
        check(replacement !== previous && replacement.copyArgb().contentEquals(previous.copyArgb()))
    }

    /**
     * Participates in the existing bounded collection wait without owning the retired array.
     */
    fun collected(): Boolean = retiredPixels?.get() == null

    /**
     * Appends deterministic real-resource and reload conclusions to the existing receipt.
     */
    fun append(report: MutableMap<String, String>) {
        check(collected())
        report["resourceImage.currentSourceEachLoad"] = "verified"
        report["resourceImage.freshIdentityAndPixels"] = "verified"
        report["resourceImage.ownerThread"] = "verified"
        report["resourceImage.reloadAndDetachedOldImage"] = "verified"
        report["resourceImage.realOwnedManagerClose"] = if (adapterCache == null) "baseline-no-derived-entry" else "verified"
        report["resourceImage.retiredPrivatePixels"] = if (adapterCache == null) "baseline-no-derived-entry" else "collected"
    }

    /**
     * Releases the probe's public snapshot and borrowed cache reference.
     */
    fun close() {
        oldImage = null
        retiredPixels = null
        adapterCache = null
    }

    private fun verifyOwnedClose() {
        val cache = checkNotNull(adapterCache)
        val retained = checkNotNull(entry())
        val encoded = retained.javaClass.getDeclaredField("encoded").apply { isAccessible = true }.get(retained) as ByteArray
        val load = cache.javaClass.getMethod("load", Any::class.java, Function0::class.java)
        ReloadableResourceManager(PackType.CLIENT_RESOURCES).use { owned ->
            val open = { encoded.inputStream() }
            load.invoke(cache, owned, open)
            check(entry() != null)
            owned.close()
            check(entry() == null) { "The real owned manager close retained decoded resource pixels." }
        }
    }

    private fun entry(): Any? {
        val cache = checkNotNull(adapterCache)
        val state =
            (cache.javaClass.getDeclaredField("current").apply { isAccessible = true }.get(cache) as AtomicReference<*>).get()
        return state.javaClass.getDeclaredField("entry").apply { isAccessible = true }.get(state)
    }

    private companion object {
        val asset = ResourceId("strata_test", "textures/gui/coal_generator.png")
    }
}
