package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

/**
 * Compares resolution reuse with the original ordered provider walk, including raster LRU and face failures.
 */
internal class MinecraftFontResolutionTest {
    @Test
    fun firstLateMissingAndChurnKeepTheOriginalRasterAccessOrder() {
        for (depth in listOf(1, 10, 32)) {
            val providers =
                List(depth) { index ->
                    if (index == depth - 1) {
                        """{"type":"space","advances":{"A":7,"B":9}}"""
                    } else {
                        """{"type":"space","advances":{"Z":${index + 1}}}"""
                    }
                }.joinToString(",")
            val snapshot = FontTestResources.snapshot(FontTestResources.font("default", providers))
            for (budget in listOf(0, 2, 16, 128)) {
                val candidateBackend = FontTestBackend()
                val referenceBackend = FontTestBackend()
                val candidate = MinecraftFontEngine(snapshot, { candidateBackend }, cacheEntries = budget)
                val reference = MinecraftFontEngine(snapshot, { referenceBackend }, cacheEntries = budget)
                try {
                    val scalars = listOf('A'.code, 'A'.code, 'B'.code, 'A'.code, 'C'.code, 'C'.code) + (0x4E00..0x4E20) + listOf('A'.code, 'B'.code, 'Z'.code, 'A'.code)
                    for (scalar in scalars) assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, scalar)
                    assertTrue(resolutionUnits(candidate) <= minOf(budget, 4096))
                    if (budget == 0) assertEquals(0, resolutionEntries(candidate))
                } finally {
                    candidate.close()
                    reference.close()
                }
                assertEquals(0, resolutionEntries(candidate))
                assertEquals(0, resolutionUnits(candidate))
                assertEquals(1, candidateBackend.closeCalls)
                assertEquals(1, referenceBackend.closeCalls)
            }
        }
    }

    @Test
    fun allDisabledProvidersArePreflightedBeforeResolutionAdmission() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"space","advances":{"A":7}},{"type":"ttf","file":"test:failed.ttf","filter":{"uniform":true}}"""),
                "assets/test/font/failed.ttf" to byteArrayOf(1),
            )
        val candidateBackend = FontTestBackend(open = { _, _ -> error("Disabled provider failed") })
        val referenceBackend = FontTestBackend(open = { _, _ -> error("Disabled provider failed") })
        MinecraftFontEngine(snapshot, { candidateBackend }).use { candidate ->
            MinecraftFontEngine(snapshot, { referenceBackend }).use { reference ->
                repeat(3) { assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, 'A'.code) }
                assertEquals(6f, candidate.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                assertEquals(0, resolutionEntries(candidate))
                assertEquals(1, candidateBackend.openCalls)
                assertEquals(referenceBackend.openCalls, candidateBackend.openCalls)
                assertEquals(1, candidate.diagnostics.size)
            }
        }
    }

    @Test
    fun poisoningAnEarlierFaceInvalidatesItsPreviouslyLateWinner() {
        val source =
            FontTestResources.source(
                FontTestResources.font("default", """{"type":"ttf","file":"test:shared.ttf"},{"type":"space","advances":{"A":7}}"""),
                "assets/test/font/shared.ttf" to byteArrayOf(1),
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility, MinecraftFontOptions(), MinecraftFontLoadLimits(maxImageBytes = 16))
        var candidateCloses = 0
        var referenceCloses = 0
        val candidateBackend = FontTestBackend(open = { _, _ -> FontTestFace({ scalar -> if (scalar == 'B'.code) raster(8) else null }, { candidateCloses++ }) })
        val referenceBackend = FontTestBackend(open = { _, _ -> FontTestFace({ scalar -> if (scalar == 'B'.code) raster(8) else null }, { referenceCloses++ }) })
        MinecraftFontEngine(snapshot, { candidateBackend }).use { candidate ->
            MinecraftFontEngine(snapshot, { referenceBackend }).use { reference ->
                repeat(2) { assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, 'A'.code) }
                assertEquals(1, resolutionEntries(candidate))
                for (scalar in listOf('B'.code, 'A'.code, 'B'.code)) {
                    val expected = assertThrows(MinecraftFontLoadLimitException::class.java) { originalGlyph(snapshot, reference, FontTestResources.defaultFont, scalar) }
                    val actual = assertThrows(MinecraftFontLoadLimitException::class.java) { candidate.glyph(FontTestResources.defaultFont, scalar) }
                    assertEquals(expected.message, actual.message)
                    assertEquals(rasterKeys(reference), rasterKeys(candidate))
                    assertEquals(0, resolutionEntries(candidate))
                }
                assertEquals(1, candidateCloses)
                assertEquals(referenceCloses, candidateCloses)
                assertEquals(referenceBackend.openCalls, candidateBackend.openCalls)
            }
        }
    }

    @Test
    fun warmLateWinnerPressureAndEarlierFaceReopenFailureMatchTheOriginalWalk() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:first.ttf"},{"type":"ttf","file":"test:second.ttf"}"""),
                FontTestResources.font("test:pressure", """{"type":"ttf","file":"test:third.ttf"}"""),
                "assets/test/font/first.ttf" to byteArrayOf(1),
                "assets/test/font/second.ttf" to byteArrayOf(2),
                "assets/test/font/third.ttf" to byteArrayOf(3),
            )
        val candidateEvents = ArrayList<String>()
        val referenceEvents = ArrayList<String>()
        val candidate = MinecraftFontEngine(snapshot, { reopeningBackend(candidateEvents) }, cacheEntries = 2, maxFaces = 1)
        val reference = MinecraftFontEngine(snapshot, { reopeningBackend(referenceEvents) }, cacheEntries = 2, maxFaces = 1)
        try {
            repeat(2) { assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, 'A'.code) }
            assertEquals(1, resolutionEntries(candidate))
            assertEquivalent(snapshot, reference, candidate, ResourceId("test", "pressure"), 'B'.code)
            val expected = assertThrows(IllegalArgumentException::class.java) { originalGlyph(snapshot, reference, FontTestResources.defaultFont, 'A'.code) }
            val actual = assertThrows(IllegalArgumentException::class.java) { candidate.glyph(FontTestResources.defaultFont, 'A'.code) }
            assertEquals(expected.message, actual.message)
            assertEquals(referenceEvents, candidateEvents)
            assertEquals(rasterKeys(reference), rasterKeys(candidate))
            assertEquals(0, resolutionEntries(candidate))
        } finally {
            candidate.close()
            reference.close()
        }
        assertEquals(referenceEvents, candidateEvents)
        assertEquals(0, resolutionEntries(candidate))
    }

    @Test
    fun filtersOptionsSnapshotsAndLegacyUniformSelectionAreEngineLocal() {
        for (filtered in listOf(false, true)) {
            for (uniform in listOf(false, true)) {
                for (advance in listOf(7, 11)) {
                    val snapshot =
                        FontTestResources.snapshot(
                            FontTestResources.font("default", """{"type":"space","advances":{"A":3},"filter":{"uniform":false}},{"type":"space","advances":{"A":$advance},"filter":{"uniform":true}}"""),
                            FontTestResources.font("uniform", """{"type":"space","advances":{"A":$advance}}"""),
                            options = MinecraftFontOptions(uniform = uniform),
                            capabilities = FontTestResources.compatibility.copy(providerFilters = filtered),
                        )
                    MinecraftFontEngine(snapshot, { FontTestBackend() }).use { candidate ->
                        MinecraftFontEngine(snapshot, { FontTestBackend() }).use { reference ->
                            repeat(3) { assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, 'A'.code) }
                            assertEquals(if (uniform) advance.toFloat() else 3f, candidate.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                            assertEquals(1, resolutionEntries(candidate))
                            assertEquals(0, resolutionEntries(reference))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun capturedJapaneseFiltersAndTrueTypeSkipVariantsRemainDistinct() {
        for (japanese in listOf(false, true)) {
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font("default", """{"type":"space","advances":{"A":3},"filter":{"jp":false}},{"type":"space","advances":{"A":7},"filter":{"jp":true}}"""),
                    options = MinecraftFontOptions(japaneseVariants = japanese),
                )
            MinecraftFontEngine(snapshot, { FontTestBackend() }).use { candidate ->
                MinecraftFontEngine(snapshot, { FontTestBackend() }).use { reference ->
                    repeat(3) { assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, 'A'.code) }
                    assertEquals(if (japanese) 7f else 3f, candidate.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                }
            }
        }
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:shared.ttf","skip":"A"},{"type":"ttf","file":"test:shared.ttf"}"""),
                "assets/test/font/shared.ttf" to byteArrayOf(1),
            )
        var candidateRasters = 0
        var referenceRasters = 0
        val candidateBackend = observingBackend(1) { candidateRasters++ }
        val referenceBackend = observingBackend(1) { referenceRasters++ }
        MinecraftFontEngine(snapshot, { candidateBackend }).use { candidate ->
            MinecraftFontEngine(snapshot, { referenceBackend }).use { reference ->
                for (scalar in listOf('A'.code, 'A'.code, 'B'.code, 'A'.code)) assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, scalar)
                assertEquals(2, candidateRasters)
                assertEquals(referenceRasters, candidateRasters)
                assertEquals(1, candidateBackend.openCalls)
            }
        }
    }

    @Test
    fun atlasRejectedLateProviderNeverFallsThroughAndRetainsNativeMetricEpochs() {
        for (replaceMetrics in listOf(false, true)) {
            val prefixes = List(9) { """{"type":"space","advances":{"B":2}}""" }.joinToString(",")
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font("default", prefixes + """,{"type":"ttf","file":"test:large.ttf"},{"type":"space","advances":{"A":9}}"""),
                    "assets/test/font/large.ttf" to byteArrayOf(1),
                    capabilities = FontTestResources.compatibility.copy(bakedGlyphMetrics = replaceMetrics),
                )
            var candidateRasters = 0
            var referenceRasters = 0
            val candidateBackend = observingBackend(257) { candidateRasters++ }
            val referenceBackend = observingBackend(257) { referenceRasters++ }
            MinecraftFontEngine(snapshot, { candidateBackend }).use { candidate ->
                MinecraftFontEngine(snapshot, { referenceBackend }).use { reference ->
                    for (scalar in listOf('A'.code, 'A'.code, 'B'.code, 'A'.code)) assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, scalar)
                    assertEquals(if (replaceMetrics) 6f else 7f, candidate.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                    assertEquals(1, candidateRasters)
                    assertEquals(referenceRasters, candidateRasters)
                    assertEquals(1, candidateBackend.openCalls)
                }
            }
        }
    }

    @Test
    fun weightedPrefixHistoryUnknownFamiliesAndCloseStayBounded() {
        val snapshot = FontTestResources.snapshot(FontTestResources.font("default", List(10) { """{"type":"space","advances":{"A":7}}""" }.joinToString(",")))
        val engine = MinecraftFontEngine(snapshot, { FontTestBackend() }, cacheEntries = 8192)
        try {
            repeat(5000) { offset ->
                engine.glyph(FontTestResources.defaultFont, 0x4E00 + offset)
                assertTrue(resolutionUnits(engine) <= 4096)
                assertTrue(resolutionEntries(engine) <= 4096 / 11)
                if (offset == 371) assertEquals(4092, resolutionUnits(engine))
            }
            val retained = resolutionEntries(engine)
            repeat(5000) { offset -> engine.glyph(ResourceId("unknown", "family_$offset"), 'A'.code) }
            assertEquals(retained, resolutionEntries(engine))
            assertEquals(6f, engine.glyph(FontTestResources.defaultFont, 0x4E00).advance)
        } finally {
            engine.close()
        }
        assertEquals(0, resolutionUnits(engine))
        assertEquals(0, resolutionEntries(engine))
        assertEquals(0, engine.retainedRasterEntries)
        assertEquals(0, engine.retainedFaces)
    }

    private fun assertEquivalent(
        snapshot: MinecraftFontSnapshot,
        reference: MinecraftFontEngine,
        candidate: MinecraftFontEngine,
        font: ResourceId,
        scalar: Int,
    ) {
        assertEquals(originalGlyph(snapshot, reference, font, scalar), candidate.glyph(font, scalar))
        assertEquals(rasterKeys(reference), rasterKeys(candidate))
        assertEquals(reference.retainedRasterBytes, candidate.retainedRasterBytes)
        assertEquals(reference.retainedFaces, candidate.retainedFaces)
        assertEquals(reference.diagnostics, candidate.diagnostics)
    }

    private fun originalGlyph(
        snapshot: MinecraftFontSnapshot,
        engine: MinecraftFontEngine,
        font: ResourceId,
        scalar: Int,
    ): MinecraftFontGlyph {
        FontJson.validateScalar(scalar)
        val selected = if (snapshot.compatibility.providerFilters.not() && snapshot.options.uniform && font == FontTestResources.defaultFont) ResourceId("minecraft", "uniform") else font
        val missing = engine.glyph(ResourceId("unknown", "reference"), scalar)
        val providers = snapshot.fonts[selected] ?: return missing
        if ((invoke(engine, "prepareFont", arrayOf(ResourceId::class.java, List::class.java), selected, providers) as Boolean).not()) return missing
        for (entry in providers) {
            val applies =
                entry.filter.all { (option, expected) ->
                    expected ==
                        when (option) {
                            FontOption.Uniform -> snapshot.options.uniform
                            FontOption.JapaneseVariants -> snapshot.options.japaneseVariants
                        }
                }
            if (applies) {
                val glyph = invoke(engine, "cachedGlyph", arrayOf(FontProviderEntry::class.java, Int::class.java), entry, scalar) as MinecraftFontGlyph?
                if (glyph != null) return glyph
            }
        }
        return missing
    }

    private fun invoke(
        engine: MinecraftFontEngine,
        name: String,
        types: Array<Class<*>>,
        vararg args: Any,
    ): Any? =
        try {
            MinecraftFontEngine::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(engine, *args)
        } catch (failure: InvocationTargetException) {
            throw checkNotNull(failure.targetException)
        }

    private fun rasterKeys(engine: MinecraftFontEngine): List<Any?> =
        (MinecraftFontEngine::class.java.getDeclaredField("rasters").apply { isAccessible = true }.get(engine) as Map<*, *>).keys.toList()

    private fun resolutionEntries(engine: MinecraftFontEngine): Int =
        (MinecraftFontEngine::class.java.getDeclaredField("resolutions").apply { isAccessible = true }.get(engine) as Map<*, *>).size

    private fun resolutionUnits(engine: MinecraftFontEngine): Int =
        MinecraftFontEngine::class.java.getDeclaredField("resolutionUnits").apply { isAccessible = true }.getInt(engine)

    private fun observingBackend(
        width: Int,
        onRaster: () -> Unit,
    ): FontTestBackend =
        FontTestBackend(
            open = { _, _ ->
                FontTestFace(
                    lookup = {
                        onRaster()
                        raster(width)
                    },
                )
            },
        )

    private fun reopeningBackend(events: MutableList<String>): FontTestBackend {
        val opens = HashMap<Int, Int>()
        return FontTestBackend(
            open = { bytes, _ ->
                val id = bytes.single().toInt()
                val count = (opens[id] ?: 0) + 1
                opens[id] = count
                events += "open-$id-$count"
                if (id == 1 && count == 3) throw IllegalArgumentException("Earlier provider reopen failed")
                FontTestFace({ if (id == 1) null else raster(1).copy(advance = id.toFloat()) }, { events += "close-$id" })
            },
            release = { events += "backend-close" },
        )
    }

    private fun raster(width: Int): MinecraftFontGlyph = MinecraftFontGlyph(7f, 0f, 0f, width.toFloat(), 1f, createDrawImage(IntSize(width, 1), IntArray(width) { -1 }))
}
