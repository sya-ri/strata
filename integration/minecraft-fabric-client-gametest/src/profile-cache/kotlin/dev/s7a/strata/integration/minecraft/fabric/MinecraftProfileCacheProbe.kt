package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * Measures fresh extraction and ordinary screen opens, then verifies real reload and immutable host ownership.
 *
 * Screen and host operations run only on the Minecraft client thread. The runner awaits reload and collection without blocking that thread.
 * Timings are diagnostic; identity, native hook, unchanged pixel, and weak-reference release checks are mandatory.
 *
 * @param currentScreen compiler-selected native screen getter.
 * @param clearScreen compiler-selected native screen removal operation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftProfileCacheProbe(
    private val currentScreen: (Minecraft) -> Screen?,
    private val clearScreen: (Minecraft) -> Unit,
) : AutoCloseable {
    private val retiredFonts = ArrayList<WeakReference<MinecraftFontSnapshot>>()
    private val report = sortedMapOf<String, String>()
    private var retainedHost: MinecraftUiHost? = null
    private var closedHost: MinecraftUiHost? = null
    private var oldFonts: WeakReference<MinecraftFontSnapshot>? = null
    private var currentFonts: WeakReference<MinecraftFontSnapshot>? = null
    private var oldPixels: ByteArray? = null

    /**
     * Measures public fresh extraction and repeated real opens, pins an old host, and starts a normal Minecraft resource reload.
     *
     * Call on the client thread, then await the returned future from the runner thread before [afterReload].
     * Failure leaves any created host owned by this probe and released by [close].
     */
    fun begin(minecraft: Minecraft): CompletableFuture<Void> {
        check(minecraft.isSameThread)
        report.putAll(MinecraftProfileCacheMixinRuntime.verify())
        val fresh = measureFreshExtraction()
        val start = System.nanoTime()
        val profile = openProfile(minecraft)
        report["open.first.nanos"] = (System.nanoTime() - start).toString()
        val snapshot = MinecraftProfileCacheInspection.fonts(profile)
        report["snapshot.primitiveArrayBytes"] = MinecraftProfileCacheInspection.primitivePayloadBytes(snapshot).toString()
        report["snapshot.fontFamilies"] = snapshot.fontIds.size.toString()
        report["extraction.fresh.nanos"] = fresh.joinToString(",")
        report["open.warm.nanos"] = measureWarmOpens(minecraft, profile).joinToString(",")
        retiredFonts += WeakReference(snapshot)
        oldFonts = WeakReference(snapshot)
        retainedHost = createMinecraftUiHost(definition(), profile, LwjglMinecraftFontBackendFactory)
        val host = checkNotNull(retainedHost)
        host.attach()
        oldPixels = rasterizeHeadless(host.frame(viewport).drawCommands, viewport).encodePng()
        return minecraft.reloadResourcePacks()
    }

    /**
     * Verifies replacement, old-host pixels, changed options, native failure/close hooks, and terminal host release.
     *
     * Call on the client thread only after the normal reload future completed successfully.
     * Successful return keeps the closed old host object alive to prove it no longer retains its resource snapshot.
     */
    fun afterReload(minecraft: Minecraft) {
        check(minecraft.isSameThread)
        check(MinecraftProfileCacheInspection.profile() == null) { "A native resource reload did not clear the normal-open cache." }
        val retained = checkNotNull(oldFonts?.get()) { "A live old host lost its immutable font snapshot." }
        val host = checkNotNull(retainedHost)
        check(rasterizeHeadless(host.frame(viewport).drawCommands, viewport).encodePng().contentEquals(oldPixels)) {
            "An old host's pixels changed when native resources reloaded."
        }
        val start = System.nanoTime()
        val replacement = openProfile(minecraft)
        report["open.afterReload.nanos"] = (System.nanoTime() - start).toString()
        check(MinecraftProfileCacheInspection.fonts(replacement) !== retained) { "A new open reused a retired font snapshot." }
        retiredFonts += WeakReference(MinecraftProfileCacheInspection.fonts(replacement))
        verifyOptionReplacement(minecraft, replacement)
        MinecraftProfileCacheNativeHooks.verify(checkNotNull(MinecraftProfileCacheInspection.profile()))
        val current = openProfile(minecraft)
        currentFonts = WeakReference(MinecraftProfileCacheInspection.fonts(current))
        check(rasterizeHeadless(host.frame(viewport).drawCommands, viewport).encodePng().contentEquals(oldPixels))
        host.close()
        closedHost = host
        retainedHost = null
        oldPixels = null
        report["retired.snapshots"] = retiredFonts.size.toString()
        report["liveHostSnapshotAcrossReload"] = "verified"
        report["nativePrePrepareFailure"] = "verified"
        report["nativeOwnedManagerCloseEviction"] = "verified"
        report["nativeOwnedCloseKeepsClientCacheOpen"] = "verified"
        report["foreignServerManagerIsolation"] = "verified"
    }

    /**
     * Requests collection and reports whether every retired snapshot was released while the single current snapshot remains live.
     *
     * The runner bounds repeated attempts by its normal timeout; this method stores no strong snapshot reference.
     */
    @Suppress("ExplicitGarbageCollectionCall")
    fun collected(): Boolean {
        System.gc()
        return retiredFonts.all { it.get() == null } && currentFonts?.get() != null
    }

    /**
     * Writes a fresh verified receipt only after actual weak-reference collection succeeded.
     *
     * @param output current loaded-run evidence directory.
     * @throws IllegalStateException if any retired snapshot remains live or the current snapshot was lost.
     */
    fun writeReceipt(output: Path) {
        check(retiredFonts.all { it.get() == null }) { "Retired resource snapshots are still reachable." }
        check(currentFonts?.get() != null) { "The one current resource snapshot was not retained." }
        report["retired.collected"] = retiredFonts.size.toString()
        report["current.snapshots"] = "1"
        report["closedHostStillReachable"] = (closedHost != null).toString()
        report["status"] = "verified"
        report["verifiedAt"] = Instant.now().toString()
        Files.createDirectories(output)
        Files.writeString(output.resolve("profile-cache.properties"), report.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
    }

    override fun close() {
        val host = retainedHost
        retainedHost = null
        closedHost = null
        oldPixels = null
        host?.close()
    }

    private fun measureFreshExtraction(): LongArray {
        val elapsed = LongArray(2)
        var previous: WeakReference<MinecraftUiProfile>? = null
        elapsed.indices.forEach { index ->
            val start = System.nanoTime()
            val profile = extractMinecraftUiProfile()
            elapsed[index] = System.nanoTime() - start
            check(profile !== previous?.get()) { "The explicit public extraction factory unexpectedly cached its result." }
            previous = WeakReference(profile)
            retiredFonts += WeakReference(MinecraftProfileCacheInspection.fonts(profile))
        }
        return elapsed
    }

    private fun measureWarmOpens(
        minecraft: Minecraft,
        profile: MinecraftUiProfile,
    ): LongArray =
        LongArray(8) {
            val start = System.nanoTime()
            check(openProfile(minecraft) === profile) { "Repeated normal opens extracted another immutable profile." }
            System.nanoTime() - start
        }

    private fun verifyOptionReplacement(
        minecraft: Minecraft,
        previous: MinecraftUiProfile,
    ) {
        val original = minecraft.options.forceUnicodeFont().get()
        try {
            minecraft.options.forceUnicodeFont().set(original.not())
            val changed = openProfile(minecraft)
            check(changed !== previous) { "Changing font options reused the previous profile." }
            retiredFonts += WeakReference(MinecraftProfileCacheInspection.fonts(changed))
        } finally {
            minecraft.options.forceUnicodeFont().set(original)
        }
        val restored = openProfile(minecraft)
        check(restored !== previous) { "The cache retained a historical option selection." }
        retiredFonts += WeakReference(MinecraftProfileCacheInspection.fonts(restored))
    }

    private fun openProfile(minecraft: Minecraft): MinecraftUiProfile {
        definition().use { it.open() }
        val screen = currentScreen(minecraft) as? FabricMinecraftScreen ?: error("Normal opening did not install a Fabric screen.")
        try {
            return checkNotNull(MinecraftProfileCacheInspection.profile()) { "Normal opening did not publish its profile." }
        } finally {
            try {
                clearScreen(minecraft)
            } finally {
                screen.close()
            }
        }
    }

    private fun definition(): ScreenDefinition = ScreenDefinition("Resource profile cache") { Stack { Text("日本語 한글 🙂") } }

    private companion object {
        private val viewport = IntSize(240, 40)
    }
}
