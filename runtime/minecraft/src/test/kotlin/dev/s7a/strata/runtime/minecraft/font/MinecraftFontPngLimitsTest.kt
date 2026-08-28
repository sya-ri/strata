package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies PNG dimension, byte-buffer, and expansion ceilings before any native image allocation.
 */
internal class MinecraftFontPngLimitsTest {
    @Test
    fun structuralByteBufferCapacityRemainsEnforcedWithHugeCustomImageLimits() {
        val limits = MinecraftFontLoadLimits(maxImageDimension = Int.MAX_VALUE, maxImageBytes = Long.MAX_VALUE, maxBitmapSheetBytes = Long.MAX_VALUE)
        limits.requireImageSize(1, Int.MAX_VALUE / 4)
        assertThrows(MinecraftFontLoadLimitException::class.java) { limits.requireImageSize(1, Int.MAX_VALUE / 4 + 1) }
        assertThrows(MinecraftFontLoadLimitException::class.java) { limits.requireImageSize(Int.MAX_VALUE, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { limits.requireImageSize(-1, 1) }
        val unsafe = FontTestResources.png(Int.MAX_VALUE, Int.MAX_VALUE, byteArrayOf())
        assertThrows(MinecraftFontLoadLimitException::class.java) { limits.checkPng(unsafe) }
    }

    @Test
    fun bitmapSheetCeilingIsIndependentFromGenericImageAndCacheBudgets() {
        val defaults = MinecraftFontLoadLimits()
        defaults.requireImageSize(2048, 2048)
        defaults.requireBitmapSheetSize(2048, 1024)
        assertThrows(MinecraftFontLoadLimitException::class.java) { defaults.requireBitmapSheetSize(2048, 1025) }
        val tiny = defaults.copy(maxImageDimension = 2, maxImageBytes = 16, maxBitmapSheetBytes = 4)
        tiny.requireBitmapSheetSize(1, 1)
        tiny.requireImageSize(2, 2)
        assertThrows(MinecraftFontLoadLimitException::class.java) { tiny.requireBitmapSheetSize(2, 1) }
        assertThrows(MinecraftFontLoadLimitException::class.java) { tiny.copy(maxBitmapSheetBytes = 3).requireBitmapSheetSize(1, 1) }
    }

    @Test
    fun pngChecksBoundRawBytesDimensionsAndInflatedDataAtInclusiveLimits() {
        val bytes = FontTestResources.png(1, 1, byteArrayOf(0, 1, 2, 3, -1))
        val exact = MinecraftFontLoadLimits(maxAssetBytes = bytes.size, maxImageDimension = 1, maxImageBytes = 4, maxDecompressedEntryBytes = 5, maxDecompressedBytes = 9)
        assertTrue(exact.checkPng(bytes))
        assertTrue(exact.copy(maxAssetBytes = bytes.size + 1, maxDecompressedEntryBytes = 6, maxDecompressedBytes = 10).checkPng(bytes))
        val rejected = listOf(exact.copy(maxAssetBytes = bytes.size - 1), exact.copy(maxImageDimension = 0), exact.copy(maxImageBytes = 3), exact.copy(maxDecompressedEntryBytes = 4), exact.copy(maxDecompressedBytes = 8))
        for (limits in rejected) assertThrows(MinecraftFontLoadLimitException::class.java) { limits.checkPng(bytes) }
        assertFalse(exact.checkPng(byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { exact.checkPng(bytes.copyOf(16)) }
    }

    @Test
    fun tinyPngHeadersCannotHideUnboundedCompressedImageData() {
        val bytes = FontTestResources.png(1, 1, ByteArray(128 * 1024))
        assertTrue(bytes.size < 1024)
        val budget = FontLoadBudget(MinecraftFontLoadLimits(maxDecompressedEntryBytes = 32, maxDecompressedBytes = 100))
        assertThrows(MinecraftFontLoadLimitException::class.java) {
            FontPngBounds.check(bytes, budget.limits) { amount -> budget.claim(FontLoadBudget.Kind.DecompressedBytes, amount) }
        }
        assertEquals(63L, budget.remaining(FontLoadBudget.Kind.DecompressedBytes))
        val aggregate = FontLoadBudget(budget.limits.copy(maxDecompressedEntryBytes = 1000, maxDecompressedBytes = 36))
        assertThrows(MinecraftFontLoadLimitException::class.java) {
            FontPngBounds.check(bytes, aggregate.limits) { amount -> aggregate.claim(FontLoadBudget.Kind.DecompressedBytes, amount) }
        }
        assertEquals(0L, aggregate.remaining(FontLoadBudget.Kind.DecompressedBytes))
    }

    @Test
    fun pngPixelAndInflateWorkSharesTheUnihexAggregateBudget() {
        val png = FontTestResources.png(1, 1, ByteArray(5))
        val zip = FontTestResources.archive("ignored" to ByteArray(3))
        val exact = FontLoadBudget(MinecraftFontLoadLimits(maxDecompressedBytes = 12))
        FontPngBounds.check(png, exact.limits) { amount -> exact.claim(FontLoadBudget.Kind.DecompressedBytes, amount) }
        FontUnihexData.load(zip, exact)
        assertEquals(0L, exact.remaining(FontLoadBudget.Kind.DecompressedBytes))
        val over = FontLoadBudget(exact.limits.copy(maxDecompressedBytes = 11))
        FontPngBounds.check(png, over.limits) { amount -> over.claim(FontLoadBudget.Kind.DecompressedBytes, amount) }
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(zip, over) }
        assertEquals(0L, over.remaining(FontLoadBudget.Kind.DecompressedBytes))
    }

    @Test
    fun sharedPngResourcesAreInspectedOnceAndRejectedResultsCannotBypassLimits() {
        val bitmap = """{"type":"bitmap","file":"test:shared.png","ascent":7,"chars":["A"]}"""
        val sources = listOf(FontTestResources.source(FontTestResources.font("default", bitmap), FontTestResources.font("test:other", bitmap), "assets/test/textures/shared.png" to FontTestResources.png(1, 1, ByteArray(5))))
        val limits = MinecraftFontLoadLimits(maxDecompressedBytes = 9)
        val snapshot = MinecraftFontSnapshot.load(sources, FontTestResources.compatibility, MinecraftFontOptions(), limits)
        assertEquals(setOf(FontTestResources.defaultFont, ResourceId("test", "other")), snapshot.fontIds)
        assertTrue(snapshot.diagnostics.isEmpty())
        val rejected = MinecraftFontSnapshot.load(sources, FontTestResources.compatibility, MinecraftFontOptions(), limits.copy(maxBitmapSheetBytes = 3))
        assertTrue(rejected.fontIds.isEmpty())
        assertEquals(2, rejected.diagnostics.size)
        assertTrue(rejected.diagnostics.all { diagnostic -> diagnostic.kind === MinecraftFontDiagnostic.Kind.ProviderLoadFailure })
    }

    @Test
    fun independentImagesConsumeOneSnapshotBudgetWhileSpacingOnlyBundlesRemainUsable() {
        val sources =
            listOf(
                FontTestResources.source(
                    FontTestResources.font("test:a", """{"type":"bitmap","file":"test:a.png","ascent":7,"chars":["A"]}"""),
                    FontTestResources.font("test:b", """{"type":"bitmap","file":"test:b.png","ascent":7,"chars":["A"]}"""),
                    FontTestResources.font("test:c", """{"type":"space","advances":{"A":3}}"""),
                    "assets/test/textures/a.png" to FontTestResources.png(1, 1, ByteArray(5)),
                    "assets/test/textures/b.png" to FontTestResources.png(1, 1, ByteArray(5)),
                ),
            )
        val snapshot = MinecraftFontSnapshot.load(sources, FontTestResources.compatibility, MinecraftFontOptions(), MinecraftFontLoadLimits(maxDecompressedBytes = 17))
        assertEquals(setOf(ResourceId("test", "a"), ResourceId("test", "c")), snapshot.fontIds)
        assertEquals(ResourceId("test", "b"), snapshot.diagnostics.single().font)
        MinecraftFontEngine(snapshot, { FontTestBackend() }).use { engine ->
            assertEquals(3f, engine.glyph(ResourceId("test", "c"), 'A'.code).advance)
        }
    }
}
