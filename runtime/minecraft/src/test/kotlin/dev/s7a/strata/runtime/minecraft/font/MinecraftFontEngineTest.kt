package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies bounded derived caches, deterministic recomputation, and owner-thread terminal resource release.
 */
internal class MinecraftFontEngineTest {
    @Test
    fun nativeWidthRoundingRetainsSignedNonFiniteAndOverflowEpochs() {
        val legacy = FontTestResources.compatibility.copy(saturatingCeil = false)
        val modern = legacy.copy(saturatingCeil = true)
        val shared = listOf(-2.5f to -2, -0f to 0, 0f to 0, 2.5f to 3, Float.MIN_VALUE to 1, -Float.MIN_VALUE to 0, Float.NaN to 0, Float.NEGATIVE_INFINITY to Int.MIN_VALUE)
        for ((advance, expected) in shared) {
            assertEquals(expected, legacy.roundedWidth(advance))
            assertEquals(expected, modern.roundedWidth(advance))
        }
        for (advance in listOf(Float.POSITIVE_INFINITY, Float.MAX_VALUE, Math.nextUp(Int.MAX_VALUE.toFloat()))) {
            assertEquals(Int.MIN_VALUE, legacy.roundedWidth(advance))
            assertEquals(Int.MAX_VALUE, modern.roundedWidth(advance))
        }
        assertEquals(Int.MAX_VALUE, legacy.roundedWidth(Int.MAX_VALUE.toFloat()))
    }

    @Test
    fun atlasRejectionRetainsItsSelectedProviderAndCachesOnlyTheEffectiveMissingRaster() {
        val raw =
            listOf(
                MinecraftFontGlyph(19f, 0f, 0f, 257f, 1f, null, orientation = SampledImageOrientation.Normal, oversizedRasterSize = IntSize(257, 1)),
                MinecraftFontGlyph(Float.NaN, 0f, 0f, 1f, 257f, null, orientation = SampledImageOrientation.Normal, oversizedRasterSize = IntSize(1, 257)),
                rasterGlyph('C'.code, width = 257).copy(advance = -3f),
            )
        val boundary = rasterGlyph('D'.code, width = 256)
        for (replaceMetrics in listOf(false, true)) {
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font("default", """{"type":"ttf","file":"test:large.ttf"},{"type":"space","advances":{"A":2,"B":3,"C":4}}"""),
                    "assets/test/font/large.ttf" to byteArrayOf(1),
                    capabilities = FontTestResources.compatibility.copy(bakedGlyphMetrics = replaceMetrics),
                )
            var requests = 0
            val backend =
                backend { codePoint ->
                    requests++
                    if (codePoint == 'D'.code) boundary else raw.getOrNull(codePoint - 'A'.code)
                }
            MinecraftFontEngine(snapshot, { backend }, cacheEntries = 1, cacheBytes = 160).use { engine ->
                val missing = engine.glyph(ResourceId("unknown", "font"), 'A'.code)
                raw.forEachIndexed { index, original ->
                    val codePoint = 'A'.code + index
                    val glyph = engine.glyph(FontTestResources.defaultFont, codePoint)
                    assertEquals(if (replaceMetrics) 6f else original.advance, glyph.advance)
                    assertEquals(missing.image, glyph.image)
                    assertEquals(listOf(0f, 0f, 5f, 8f), listOf(glyph.left, glyph.top, glyph.right, glyph.bottom))
                    assertNull(glyph.oversizedRasterSize)
                    assertSame(glyph, engine.glyph(FontTestResources.defaultFont, codePoint))
                    assertEquals(1, engine.retainedRasterEntries)
                    assertEquals(160L, engine.retainedRasterBytes)
                }
                assertEquals(3, requests)
                assertSame(boundary, engine.glyph(FontTestResources.defaultFont, 'D'.code))
                val restored = engine.glyph(FontTestResources.defaultFont, 'A'.code)
                assertEquals(if (replaceMetrics) 6f else 19f, restored.advance)
                assertEquals(5, requests)
                assertTrue(engine.diagnostics.isEmpty())
            }
            assertEquals(1, backend.closeCalls)
        }
    }

    @Test
    fun glyphEntryAndPixelBudgetsStayBoundedAcrossArbitraryNewScalars() {
        var requests = 0
        val backend =
            backend { codePoint ->
                requests++
                rasterGlyph(codePoint)
            }
        val engine = MinecraftFontEngine(trueTypeSnapshot("default"), MinecraftFontBackendFactory { backend }, cacheEntries = 2, cacheBytes = 8)
        val original = engine.glyph(FontTestResources.defaultFont, '日'.code)
        val korean = engine.glyph(FontTestResources.defaultFont, '한'.code)
        assertSame(original, engine.glyph(FontTestResources.defaultFont, '日'.code))
        engine.glyph(FontTestResources.defaultFont, 0x1F600)
        assertEquals(korean, engine.glyph(FontTestResources.defaultFont, '한'.code))
        assertEquals(4, requests)
        repeat(5_000) { index ->
            engine.glyph(FontTestResources.defaultFont, 0x4E00 + index)
            assertTrue(engine.retainedRasterEntries <= 2)
            assertTrue(engine.retainedRasterBytes <= 8)
        }
        assertEquals(original, engine.glyph(FontTestResources.defaultFont, '日'.code))
        engine.close()
        assertEquals(0, engine.retainedRasterEntries)
        assertEquals(0L, engine.retainedRasterBytes)
        assertEquals(0, engine.retainedFaces)
        assertEquals(0xFF000000.toInt() or '日'.code, requireNotNull(original.image).argbAt(0, 0))
    }

    @Test
    fun oversizedRastersBypassTheCacheWithoutChangingTheirReturnedPixels() {
        var requests = 0
        val backend =
            backend { codePoint ->
                requests++
                rasterGlyph(codePoint, width = 5)
            }
        MinecraftFontEngine(trueTypeSnapshot("default"), MinecraftFontBackendFactory { backend }, cacheBytes = 16).use { engine ->
            val first = engine.glyph(FontTestResources.defaultFont, '日'.code)
            assertEquals(first, engine.glyph(FontTestResources.defaultFont, '日'.code))
            assertEquals(2, requests)
            assertEquals(0, engine.retainedRasterEntries)
            assertEquals(0L, engine.retainedRasterBytes)
        }
    }

    @Test
    fun bitmapSheetsAndGlyphsShareBothBudgetsWithoutChangingUncachedResults() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "default",
                    """
                    {"type":"bitmap","file":"test:cache.png","height":7,"ascent":6,"chars":["日한"]},
                    {"type":"ttf","file":"test:cache.ttf"}
                    """.trimIndent(),
                ),
                "assets/test/textures/cache.png" to byteArrayOf(1),
                "assets/test/font/cache.ttf" to byteArrayOf(2),
            )
        val sheet = createDrawImage(IntSize(4, 2), IntArray(8) { index -> 0x80CC4422.toInt() or index })
        val lookup: (Int) -> MinecraftFontGlyph = { codePoint -> rasterGlyph(codePoint).copy(advance = 2.25f, left = -0.25f, right = 0.75f) }
        val cachedBackend = backend(decode = { sheet }, lookup = lookup)
        val uncachedBackend = backend(decode = { sheet }, lookup = lookup)
        val cached = MinecraftFontEngine(snapshot, { cachedBackend }, cacheEntries = 3, cacheBytes = 48)
        val uncached = MinecraftFontEngine(snapshot, { uncachedBackend }, cacheEntries = 0, cacheBytes = 0)
        try {
            val original = cached.glyph(FontTestResources.defaultFont, '日'.code)
            assertEquals(uncached.glyph(FontTestResources.defaultFont, '日'.code), original)
            assertSame(original, cached.glyph(FontTestResources.defaultFont, '日'.code))
            assertEquals(2, cached.retainedRasterEntries)
            assertEquals(48L, cached.retainedRasterBytes)
            assertEquals(uncached.glyph(FontTestResources.defaultFont, '한'.code), cached.glyph(FontTestResources.defaultFont, '한'.code))
            val reloaded = cached.glyph(FontTestResources.defaultFont, '日'.code)
            assertNotSame(original, reloaded)
            assertEquals(uncached.glyph(FontTestResources.defaultFont, '日'.code), reloaded)
            assertEquals(1, cachedBackend.decodeCalls)
            assertEquals(uncached.glyph(FontTestResources.defaultFont, 0x1F600), cached.glyph(FontTestResources.defaultFont, 0x1F600))
            assertEquals(2, cached.retainedRasterEntries)
            assertEquals(20L, cached.retainedRasterBytes)
            assertEquals(uncached.glyph(FontTestResources.defaultFont, '한'.code), cached.glyph(FontTestResources.defaultFont, '한'.code))
            assertEquals(2, cachedBackend.decodeCalls)
            assertEquals(original, cached.glyph(FontTestResources.defaultFont, '日'.code))
            repeat(32) { index ->
                val codePoint = 0x4E00 + index
                assertEquals(uncached.glyph(FontTestResources.defaultFont, codePoint), cached.glyph(FontTestResources.defaultFont, codePoint))
                assertTrue(cached.retainedRasterEntries <= 3)
                assertTrue(cached.retainedRasterBytes <= 48)
                assertEquals(0, uncached.retainedRasterEntries)
                assertEquals(0L, uncached.retainedRasterBytes)
            }
            assertTrue(cachedBackend.decodeCalls < uncachedBackend.decodeCalls)
            cached.close()
            assertEquals(0, uncachedBackend.closeCalls)
            assertEquals(original, uncached.glyph(FontTestResources.defaultFont, '日'.code))
        } finally {
            cached.close()
            uncached.close()
        }
        for (engine in listOf(cached, uncached)) {
            assertEquals(0, engine.retainedRasterEntries)
            assertEquals(0L, engine.retainedRasterBytes)
            assertEquals(0, engine.retainedFaces)
        }
        assertEquals(1, cachedBackend.closeCalls)
        assertEquals(1, uncachedBackend.closeCalls)
    }

    @Test
    fun providerMissesAreCachedButUnknownFontHistoryIsNotRetained() {
        var requests = 0
        val backend =
            backend {
                requests++
                null
            }
        MinecraftFontEngine(trueTypeSnapshot("default"), MinecraftFontBackendFactory { backend }).use { engine ->
            repeat(2) { engine.glyph(FontTestResources.defaultFont, '日'.code) }
            assertEquals(1, requests)
            assertEquals(1, engine.retainedRasterEntries)
            repeat(5_000) { index -> engine.glyph(ResourceId("unknown", "font_$index"), 0x4E00 + index) }
            assertEquals(1, engine.retainedRasterEntries)
            assertEquals(0L, engine.retainedRasterBytes)
            assertTrue(engine.diagnostics.isEmpty())
        }
    }

    @Test
    fun nativeFaceEvictionClosesBeforeOpeningAndCloseRemainsIdempotent() {
        val events = ArrayList<Pair<Event, Int>>()
        val backend =
            FontTestBackend(
                open = { bytes, _ ->
                    val identity = bytes.single().toInt()
                    events += Event.Open to identity
                    object : MinecraftTrueTypeFace {
                        override fun glyph(codePoint: Int): MinecraftFontGlyph = rasterGlyph(codePoint)

                        override fun close() {
                            events += Event.Close to identity
                        }
                    }
                },
                release = { events += Event.BackendClose to 0 },
            )
        val snapshot = trueTypeSnapshot("default", "second")
        var backendOpens = 0
        val factory =
            MinecraftFontBackendFactory {
                backendOpens++
                backend
            }
        assertThrows(IllegalArgumentException::class.java) {
            MinecraftFontEngine(snapshot, factory, maxFaces = 17).use {}
        }
        assertEquals(0, backendOpens)
        val engine = MinecraftFontEngine(snapshot, factory, cacheEntries = 0, maxFaces = 1)
        engine.glyph(FontTestResources.defaultFont, '日'.code)
        engine.glyph(ResourceId("minecraft", "second"), '한'.code)
        engine.glyph(FontTestResources.defaultFont, 0x1F600)
        assertEquals(1, engine.retainedFaces)
        engine.close()
        engine.close()
        assertEquals(
            listOf(Event.Open to 1, Event.Close to 1, Event.Open to 2, Event.Close to 2, Event.Open to 1, Event.Close to 1, Event.BackendClose to 0),
            events,
        )
        assertThrows(IllegalStateException::class.java) { engine.glyph(FontTestResources.defaultFont, '日'.code) }
        assertThrows(IllegalStateException::class.java) { engine.visualOrder("日") }
    }

    @Test
    fun cleanupReleasesAllResourcesAndSuppressesEachDistinctFailureOnce() {
        val first = IllegalStateException("first face")
        val second = IllegalArgumentException("second face")
        var releasedFaces = 0
        val backend =
            FontTestBackend(
                open = { bytes, _ ->
                    val failure = if (bytes.single().toInt() == 1) first else second
                    object : MinecraftTrueTypeFace {
                        override fun glyph(codePoint: Int): MinecraftFontGlyph = rasterGlyph(codePoint)

                        override fun close() {
                            releasedFaces++
                            throw failure
                        }
                    }
                },
                release = { throw second },
            )
        val engine = MinecraftFontEngine(trueTypeSnapshot("default", "second"), MinecraftFontBackendFactory { backend })
        engine.glyph(FontTestResources.defaultFont, '日'.code)
        engine.glyph(ResourceId("minecraft", "second"), '한'.code)
        val failure = assertThrows(IllegalStateException::class.java, engine::close)
        assertSame(first, failure)
        assertEquals(listOf(second), failure.suppressed.toList())
        assertEquals(2, releasedFaces)
        assertEquals(1, backend.closeCalls)
        assertEquals(0, engine.retainedFaces)
        assertEquals(0, engine.retainedRasterEntries)
        engine.close()
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun foreignThreadsCannotReadOrCloseTheOwnerEngine() {
        val backend = backend { codePoint -> rasterGlyph(codePoint) }
        val engine = MinecraftFontEngine(trueTypeSnapshot("default"), MinecraftFontBackendFactory { backend })
        try {
            val work =
                FutureTask {
                    assertThrows(IllegalStateException::class.java) { engine.glyph(FontTestResources.defaultFont, '日'.code) }
                    assertThrows(IllegalStateException::class.java, engine::close)
                    assertThrows(IllegalStateException::class.java) { engine.retainedFaces }
                }
            Thread(work).start()
            work.get(10, TimeUnit.SECONDS)
            assertEquals(0, backend.closeCalls)
            assertEquals(1.0f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
        } finally {
            engine.close()
        }
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun visualGlyphsPreserveLogicalUtf16OffsetsAndNeverRetainInputRuns() {
        MinecraftFontEngine(trueTypeSnapshot("default"), MinecraftFontBackendFactory { FontTestBackend() }).use { engine ->
            val glyphs = engine.visualGlyphs("日😀한\uD800")
            assertEquals(listOf('日'.code, 0x1F600, '한'.code, 0xFFFD), glyphs.map(MinecraftVisualGlyph::codePoint))
            assertEquals(listOf(0, 1, 3, 4), glyphs.map(MinecraftVisualGlyph::sourceIndex))
            assertThrows(UnsupportedOperationException::class.java) { (glyphs as MutableList<MinecraftVisualGlyph>).clear() }
            assertEquals(0, engine.retainedRasterEntries)
            assertThrows(IllegalArgumentException::class.java) { engine.glyph(FontTestResources.defaultFont, 0xD800) }
        }
    }

    @Test
    fun bitmapCellsScanOnceAndNeverCopyAtlasRejectedPixels() {
        for (size in listOf(IntSize(256, 1), IntSize(257, 1), IntSize(1, 257))) {
            var samples = 0
            val cell =
                FontBitmapCell.read(size) { x, _ ->
                    samples++
                    if (x == size.width - 1) -1 else 0
                }
            assertEquals(size.width * size.height, samples)
            assertEquals(size.width - 1, cell.rightmost)
            if (size.width <= 256 && size.height <= 256) {
                assertEquals(size, requireNotNull(cell.image).size)
                assertNull(cell.oversizedRasterSize)
            } else {
                assertNull(cell.image)
                assertEquals(size, cell.oversizedRasterSize)
            }
        }
    }

    @Test
    fun oversizedBitmapCellsKeepNativeAdvanceEpochsAndReuseTheirBoundedSheetAfterGlyphEviction() {
        for (size in listOf(IntSize(256, 1), IntSize(257, 1), IntSize(1, 257))) {
            for (bakedMetrics in listOf(false, true)) verifyBitmapAtlasCell(size, bakedMetrics)
        }
    }

    @Test
    fun defaultBitmapSheetCeilingRejectsBeforeDecodingInEveryCacheMode() {
        val source =
            FontTestResources.source(
                FontTestResources.font("default", """{"type":"bitmap","file":"test:large.png","ascent":7,"chars":["AB"]}"""),
                FontTestResources.font("test:other", """{"type":"space","advances":{"A":3}}"""),
                "assets/test/textures/large.png" to FontTestResources.png(2048, 1025, ByteArray(5)),
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility)
        for (cacheEntries in listOf(4096, 0)) {
            val backend = FontTestBackend()
            MinecraftFontEngine(snapshot, { backend }, cacheEntries = cacheEntries).use { engine ->
                repeat(3) {
                    assertEquals(6f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                    assertEquals(6f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
                    assertEquals(3f, engine.glyph(ResourceId("test", "other"), 'A'.code).advance)
                }
                assertEquals(0, backend.decodeCalls)
                assertEquals(1, engine.diagnostics.size)
            }
            assertEquals(1, backend.closeCalls)
        }
    }

    @Test
    fun atLimitBitmapSheetDecodesOnceWhileUncachedPixelsAndTerminalReleaseRemainIdentical() {
        val size = IntSize(2048, 1024)
        val sheet = createDrawImage(size, IntArray(size.width * size.height) { index -> 0xFF000000.toInt() or index })
        val rows = listOf("AB" + "\\u0000".repeat(6)) + List(3) { "\\u0000".repeat(8) }
        val chars = rows.joinToString(",") { row -> "\"$row\"" }
        val source =
            FontTestResources.source(
                FontTestResources.font("default", """{"type":"bitmap","file":"test:limit.png","ascent":7,"chars":[$chars]}"""),
                "assets/test/textures/limit.png" to FontTestResources.png(size.width, size.height, ByteArray((size.width * 4 + 1) * size.height)),
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility)
        val cachedBackend = FontTestBackend(decode = { sheet })
        val uncachedBackend = FontTestBackend(decode = { sheet })
        val cached = MinecraftFontEngine(snapshot, { cachedBackend })
        val uncached = MinecraftFontEngine(snapshot, { uncachedBackend }, cacheEntries = 0, cacheBytes = 0)
        cached.use { retained ->
            uncached.use { recomputed ->
                repeat(3) {
                    assertEquals(retained.glyph(FontTestResources.defaultFont, 'A'.code), recomputed.glyph(FontTestResources.defaultFont, 'A'.code))
                    assertEquals(retained.glyph(FontTestResources.defaultFont, 'B'.code), recomputed.glyph(FontTestResources.defaultFont, 'B'.code))
                }
                assertEquals(1, cachedBackend.decodeCalls)
                assertTrue(1 < uncachedBackend.decodeCalls)
                assertEquals(8L * 1024 * 1024 + 2L * 256 * 256 * 4, retained.retainedRasterBytes)
                assertTrue(retained.retainedRasterBytes <= 16L * 1024 * 1024)
                assertEquals(0L, recomputed.retainedRasterBytes)
            }
        }
        assertEquals(0L, cached.retainedRasterBytes)
        assertEquals(0, cached.retainedRasterEntries)
        assertEquals(0L, uncached.retainedRasterBytes)
        assertEquals(1, cachedBackend.closeCalls)
        assertEquals(1, uncachedBackend.closeCalls)
    }

    @Test
    fun oldCustomDecoderResultsAreCheckedBeforeRetentionWithoutBypassingDelegatedCallbacks() {
        val snapshot =
            MinecraftFontSnapshot.load(
                listOf(FontTestResources.source(FontTestResources.font("default", """{"type":"bitmap","file":"test:custom.png","ascent":7,"chars":["AB"]}"""), "assets/test/textures/custom.png" to byteArrayOf(1))),
                FontTestResources.compatibility,
                MinecraftFontOptions(),
                MinecraftFontLoadLimits(maxBitmapSheetBytes = 8),
            )
        val backend = FontTestBackend(decode = { createDrawImage(IntSize(2, 2), IntArray(4) { -1 }) })
        var interceptions = 0
        val delegated =
            object : MinecraftFontBackend by backend {
                override fun decodePng(bytes: ByteArray): DrawImage {
                    interceptions++
                    return backend.decodePng(bytes)
                }
            }
        MinecraftFontEngine(snapshot, { delegated }).use { engine ->
            assertEquals(6f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
            assertEquals(6f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
            assertEquals(1, backend.decodeCalls)
            assertEquals(1, interceptions)
            assertEquals(0L, engine.retainedRasterBytes)
            assertEquals(MinecraftFontDiagnostic.Kind.ProviderLoadFailure, engine.diagnostics.single().kind)
        }
    }

    private fun verifyBitmapAtlasCell(
        size: IntSize,
        bakedMetrics: Boolean,
    ) {
        val sheetSize = IntSize(size.width * 2, size.height)
        val backend = FontTestBackend(decode = { createDrawImage(sheetSize, IntArray(sheetSize.width * sheetSize.height) { -1 }) })
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"bitmap","file":"test:atlas.png","height":${size.height},"ascent":${size.height},"chars":["AB"]}"""),
                "assets/test/textures/atlas.png" to byteArrayOf(1),
                capabilities = FontTestResources.compatibility.copy(bakedGlyphMetrics = bakedMetrics),
            )
        val rejected = 256 < size.width || 256 < size.height
        val expectedAdvance = if (rejected && bakedMetrics) 6f else size.width.toFloat() + 1
        val engine = MinecraftFontEngine(snapshot, { backend }, cacheEntries = 2)
        engine.use {
            val first = engine.glyph(FontTestResources.defaultFont, 'A'.code)
            assertEquals(expectedAdvance, first.advance)
            assertEquals(if (rejected) IntSize(5, 8) else size, requireNotNull(first.image).size)
            assertSame(first, engine.glyph(FontTestResources.defaultFont, 'A'.code))
            assertEquals(expectedAdvance, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
            assertEquals(first, engine.glyph(FontTestResources.defaultFont, 'A'.code))
            assertEquals(1, backend.decodeCalls)
            assertEquals(2, engine.retainedRasterEntries)
            val glyphBytes = if (rejected) 160L else size.width.toLong() * size.height * 4
            assertEquals(sheetSize.width.toLong() * sheetSize.height * 4 + glyphBytes, engine.retainedRasterBytes)
        }
        assertEquals(0L, engine.retainedRasterBytes)
        assertEquals(1, backend.closeCalls)
    }

    private fun trueTypeSnapshot(vararg names: String): MinecraftFontSnapshot {
        val files =
            names.flatMapIndexed { index, name ->
                listOf(
                    FontTestResources.font(name, """{"type":"ttf","file":"test:$name.ttf"}"""),
                    "assets/test/font/$name.ttf" to byteArrayOf((index + 1).toByte()),
                )
            }
        return FontTestResources.snapshot(*files.toTypedArray())
    }

    private fun backend(
        decode: (ByteArray) -> DrawImage = { error("No PNG decoder was expected.") },
        lookup: (Int) -> MinecraftFontGlyph?,
    ): FontTestBackend =
        FontTestBackend(
            decode = decode,
            open = { _, _ ->
                object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph? = lookup(codePoint)

                    override fun close() = Unit
                }
            },
        )

    private fun rasterGlyph(
        codePoint: Int,
        width: Int = 1,
    ): MinecraftFontGlyph =
        MinecraftFontGlyph(
            width.toFloat(),
            0.0f,
            0.0f,
            width.toFloat(),
            1.0f,
            createDrawImage(IntSize(width, 1), IntArray(width) { 0xFF000000.toInt() or codePoint }),
        )

    private enum class Event {
        Open,
        Close,
        BackendClose,
    }
}
