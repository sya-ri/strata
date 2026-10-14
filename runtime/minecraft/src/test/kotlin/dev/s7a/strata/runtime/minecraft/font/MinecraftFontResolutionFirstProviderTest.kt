package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.assertEquivalent
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.observingBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.originalGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.raster
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.rasterKeys
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.resolutionEntries
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.resolutionUnits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Compares first-provider bypass and fallback continuation with the independent original provider loop.
 */
internal class MinecraftFontResolutionFirstProviderTest {
    @Test
    fun firstWinnerAndAtlasRejectionKeepOriginalPressureWithoutSelectionMetadata() {
        val suffix = List(9) { """{"type":"space","advances":{"A":99}}""" }.joinToString(",")
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:first.ttf"},""" + suffix),
                FontTestResources.font("test:pressure", """{"type":"ttf","file":"test:first.ttf"}"""),
                "assets/test/font/first.ttf" to byteArrayOf(1),
            )
        for (width in listOf(1, 257)) {
            for (budget in listOf(0, 2, 64)) verifyFirstWinner(snapshot, width, budget)
        }
    }

    @Test
    fun fallbackRetainsEveryDuplicateProviderAndMissTouchWithoutRepeatingTheFirstAttempt() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:first.ttf"},{"type":"space","advances":{"Z":3}},{"type":"ttf","file":"test:first.ttf"},{"type":"ttf","file":"test:last.ttf"}"""),
                "assets/test/font/first.ttf" to byteArrayOf(1),
                "assets/test/font/last.ttf" to byteArrayOf(2),
            )
        for (budget in listOf(0, 2, 8)) {
            val candidateEvents = ArrayList<String>()
            val referenceEvents = ArrayList<String>()
            val candidateBackend = fallbackBackend(candidateEvents)
            val referenceBackend = fallbackBackend(referenceEvents)
            val candidateOwner = MinecraftFontEngine(snapshot, { candidateBackend }, cacheEntries = budget)
            candidateOwner.use { candidate ->
                MinecraftFontEngine(snapshot, { referenceBackend }, cacheEntries = budget).use { reference ->
                    for (scalar in listOf('A'.code, 'A'.code, 'B'.code, 'A'.code, 'Z'.code, 'A'.code)) {
                        assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, scalar)
                        assertEquals(referenceEvents, candidateEvents)
                        assertEquals(referenceBackend.openCalls, candidateBackend.openCalls)
                    }
                    if (budget == 8) {
                        assertEquals(1, candidateEvents.count { it == "lookup-1-${'A'.code}" })
                        assertTrue(0 < resolutionEntries(candidate))
                    }
                }
            }
            assertEquals(referenceEvents, candidateEvents)
            assertEquals(0, resolutionEntries(candidateOwner))
        }
    }

    @Test
    fun filteredAndSkippedPrefixesPreserveFallbackWithoutEmptySelectionEntries() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"space","advances":{"A":99},"filter":{"uniform":true}},{"type":"ttf","file":"test:shared.ttf","skip":"AB"},{"type":"ttf","file":"test:shared.ttf"}"""),
                "assets/test/font/shared.ttf" to byteArrayOf(1),
            )
        var candidateRasters = 0
        var referenceRasters = 0
        val candidateBackend = observingBackend(1) { candidateRasters++ }
        val referenceBackend = observingBackend(1) { referenceRasters++ }
        MinecraftFontEngine(snapshot, { candidateBackend }).use { candidate ->
            MinecraftFontEngine(snapshot, { referenceBackend }).use { reference ->
                for (scalar in listOf('A'.code, 'A'.code, 'B'.code, 'A'.code)) {
                    assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, scalar)
                    assertEquals(0, resolutionEntries(candidate))
                    assertEquals(0, resolutionUnits(candidate))
                }
                assertEquals(2, candidateRasters)
                assertEquals(referenceRasters, candidateRasters)
                assertEquals(1, candidateBackend.openCalls)
            }
        }
        assertEquals(referenceBackend.closeCalls, candidateBackend.closeCalls)
    }

    @Test
    fun firstFaceTerminalCallbacksKeepDetachedFallbackAndExactCleanupFailure() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:terminal.ttf"},{"type":"space","advances":{"A":9}}"""),
                "assets/test/font/terminal.ttf" to byteArrayOf(1),
            )
        for (budget in listOf(0, 8)) {
            for (missing in listOf(false, true)) {
                for (failure in listOf(null, IllegalStateException("Terminal first face failed"), MinecraftFontLoadLimitException("Terminal first face limit"))) {
                    verifyTerminalFirst(snapshot, budget, missing, failure, original = true)
                    verifyTerminalFirst(snapshot, budget, missing, failure, original = false)
                }
            }
        }
    }

    private fun verifyFirstWinner(
        snapshot: MinecraftFontSnapshot,
        width: Int,
        budget: Int,
    ) {
        var candidateRasters = 0
        var referenceRasters = 0
        val candidateBackend = observingBackend(width) { candidateRasters++ }
        val referenceBackend = observingBackend(width) { referenceRasters++ }
        MinecraftFontEngine(snapshot, { candidateBackend }, cacheEntries = budget).use { candidate ->
            MinecraftFontEngine(snapshot, { referenceBackend }, cacheEntries = budget).use { reference ->
                for (scalar in listOf('A'.code, 'A'.code, 'B'.code, 'A'.code, 'C'.code, 'A'.code)) {
                    assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, scalar)
                    assertEquals(0, resolutionEntries(candidate))
                }
                assertEquivalent(snapshot, reference, candidate, ResourceId("test", "pressure"), 'D'.code)
                assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, 'A'.code)
                assertEquals(referenceRasters, candidateRasters)
                assertEquals(if (budget == 0) 8 else 4, candidateRasters)
                assertEquals(0, resolutionUnits(candidate))
            }
        }
        assertEquals(1, candidateBackend.openCalls)
        assertEquals(referenceBackend.closeCalls, candidateBackend.closeCalls)
    }

    private fun fallbackBackend(events: MutableList<String>): FontTestBackend =
        FontTestBackend(
            open = { bytes, _ ->
                val id = bytes.single().toInt()
                events += "open-$id"
                FontTestFace(
                    lookup = { scalar ->
                        events += "lookup-$id-$scalar"
                        if (id == 1) null else raster(1)
                    },
                    release = { events += "close-$id" },
                )
            },
            release = { events += "backend-close" },
        )

    private fun verifyTerminalFirst(
        snapshot: MinecraftFontSnapshot,
        budget: Int,
        missing: Boolean,
        failure: Throwable?,
        original: Boolean,
    ) {
        lateinit var owner: MinecraftFontEngine
        val detached = MinecraftFontGlyph(7f, 0f, 0f, 0f, 0f, null)
        var faceCloses = 0
        var glyphCalls = 0
        val backend =
            FontTestBackend(
                open = { _, _ ->
                    FontTestFace(
                        lookup = {
                            glyphCalls++
                            owner.close()
                            if (missing) null else detached
                        },
                        release = { faceCloses++ },
                    )
                },
                release = { if (failure != null) throw failure },
            )
        owner = MinecraftFontEngine(snapshot, { backend }, cacheEntries = budget)
        try {
            val result =
                runCatching {
                    if (original) originalGlyph(snapshot, owner, FontTestResources.defaultFont, 'A'.code) else owner.glyph(FontTestResources.defaultFont, 'A'.code)
                }
            if (failure == null) {
                val expected = if (missing) MinecraftFontGlyph(9f, 0f, 0f, 0f, 0f, null) else detached
                assertEquals(expected, result.getOrThrow())
            } else {
                assertTrue(result.exceptionOrNull() === failure)
            }
            assertEquals(1, glyphCalls)
            assertEquals(1, faceCloses)
            assertEquals(1, backend.closeCalls)
            assertEquals(0, resolutionEntries(owner))
            assertEquals(0, resolutionUnits(owner))
            assertTrue(rasterKeys(owner).isEmpty())
            val rejected = assertThrows(IllegalStateException::class.java) { owner.glyph(FontTestResources.defaultFont, 'A'.code) }
            assertEquals("Font engine is closed.", rejected.message)
        } finally {
            owner.close()
        }
    }
}
