package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Frozen source preparation shared byte-for-byte by baseline and candidate collectors.
 * Input files are independently retained by the existing testkit's ordinary external-input manifest.
 */
internal object FreeTypeGrayscaleAssets {
    /**
     * Requires an immutable native admission input for collection; ordinary untimed checks may prepare it first.
     */
    internal fun requireAdmission() {
        if (System.getProperty("strata.performance.inputs") != null) {
            check(JmhFixtureSelection.inputs().containsKey("freetype-grayscale-admission")) { "Freeze complete native admission before grayscale collection" }
        }
    }

    /**
     * Borrows the backend-owned genuine runtime delegate for untimed native fixture/oracle access.
     * The managed wrapper remains the sole owner and releases this delegate at backend close.
     */
    internal fun delegate(face: MinecraftTrueTypeFace): MinecraftTrueTypeFace = face.javaClass
        .getDeclaredField("delegate")
        .apply { isAccessible = true }
        .get(face) as MinecraftTrueTypeFace

    /**
     * Reads the existing registered immutable CC0 font before sampling.
     */
    internal fun font(): ByteArray = Files.readAllBytes(Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))))

    /**
     * Prepares one real TTF provider at the exact selected size, oversampling and shift.
     */
    internal fun source(settings: MinecraftTrueTypeSettings): MinecraftMemoryFontAssetSource =
        MinecraftMemoryFontAssetSource(
            "freetype-grayscale-v1",
            mapOf(
                "assets/minecraft/font/default.json" to
                    """{"providers":[{"type":"ttf","file":"strata_benchmark:fixture.ttf","size":${settings.size},"oversample":${settings.oversample},"shift":[${settings.shiftX},${settings.shiftY}]}]}""".toByteArray(Charsets.UTF_8),
                "assets/strata_benchmark/font/fixture.ttf" to font(),
            ),
        )

    /**
     * Uses the actual loader, with all acquisition/JSON preparation excluded from timed glyph and frame operations.
     */
    internal fun snapshot(
        source: MinecraftMemoryFontAssetSource,
        limits: MinecraftFontLoadLimits = MinecraftFontLoadLimits(),
    ): MinecraftFontSnapshot =
        MinecraftFontSnapshot.load(listOf(source), FontPerformanceAssets.compatibility(FontWorkload.FreeTypeCached), MinecraftFontOptions(), limits).also {
            check(it.diagnostics.isEmpty()) { "FreeType fixture load failed: ${it.diagnostics}" }
        }
}
