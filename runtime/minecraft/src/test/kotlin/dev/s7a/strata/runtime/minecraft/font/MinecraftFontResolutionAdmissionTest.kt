package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.assertEquivalent
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.observingBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.originalGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.reenteringBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.resolutionEntries
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontResolutionOracle.resolutionUnits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies metadata bypass and admission refusal against the independent original provider loop.
 */
internal class MinecraftFontResolutionAdmissionTest {
    @Test
    fun coldMissesAndLongChainChurnDeferSelectionUntilTheRasterWorkingSetFits() {
        val prefixes = List(9) { """{"type":"space","advances":{"Z":3}}""" }.joinToString(",")
        val snapshot = FontTestResources.snapshot(FontTestResources.font("default", prefixes + """,{"type":"space","advances":{"A":7}}"""))
        for (budget in listOf(2, 64, 8192)) {
            val candidateBackend = FontTestBackend()
            val referenceBackend = FontTestBackend()
            MinecraftFontEngine(snapshot, { candidateBackend }, cacheEntries = budget).use { candidate ->
                MinecraftFontEngine(snapshot, { referenceBackend }, cacheEntries = budget).use { reference ->
                    for (scalar in 65..128) {
                        assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, scalar)
                        assertEquals(0, resolutionEntries(candidate))
                        assertEquals(0, resolutionUnits(candidate))
                    }
                    repeat(3) {
                        assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, 'A'.code)
                    }
                    assertEquals(if (budget == 2) 0 else 1, resolutionEntries(candidate))
                    assertEquals(if (budget == 2) 0 else 10, resolutionUnits(candidate))
                    assertEquals(referenceBackend.openCalls, candidateBackend.openCalls)
                }
            }
            assertEquals(referenceBackend.closeCalls, candidateBackend.closeCalls)
        }
    }

    @Test
    fun singleTrueTypeProviderKeepsOriginalPressureAndAtlasRejectionWithoutSelectionMetadata() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:single.ttf"}"""),
                "assets/test/font/single.ttf" to byteArrayOf(1),
            )
        val variants = listOf(1, 257).flatMap { width -> listOf(0, 2, 64).map { budget -> width to budget } }
        for ((width, budget) in variants) {
            var candidateRasters = 0
            var referenceRasters = 0
            val candidateBackend = observingBackend(width) { candidateRasters++ }
            val referenceBackend = observingBackend(width) { referenceRasters++ }
            MinecraftFontEngine(snapshot, { candidateBackend }, cacheEntries = budget).use { candidate ->
                MinecraftFontEngine(snapshot, { referenceBackend }, cacheEntries = budget).use { reference ->
                    for (scalar in listOf('A'.code, 'A'.code, 'B'.code, 'C'.code, 'A'.code)) {
                        assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, scalar)
                    }
                    assertEquals(referenceRasters, candidateRasters)
                    assertEquals(referenceBackend.openCalls, candidateBackend.openCalls)
                    assertEquals(0, resolutionEntries(candidate))
                    assertEquals(0, resolutionUnits(candidate))
                }
            }
            assertEquals(1, candidateBackend.closeCalls)
            assertEquals(referenceBackend.closeCalls, candidateBackend.closeCalls)
        }
    }

    @Test
    fun oneDisabledFailingProviderStillPreflightsBeforeReturningTheMissingGlyph() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:failed.ttf","filter":{"uniform":true}}"""),
                "assets/test/font/failed.ttf" to byteArrayOf(1),
            )
        val candidateBackend = FontTestBackend(open = { _, _ -> error("Disabled provider failed") })
        val referenceBackend = FontTestBackend(open = { _, _ -> error("Disabled provider failed") })
        MinecraftFontEngine(snapshot, { candidateBackend }).use { candidate ->
            MinecraftFontEngine(snapshot, { referenceBackend }).use { reference ->
                repeat(3) { assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, 'A'.code) }
                assertEquals(1, candidateBackend.openCalls)
                assertEquals(referenceBackend.openCalls, candidateBackend.openCalls)
                assertEquals(1, candidate.diagnostics.size)
                assertEquals(0, resolutionEntries(candidate))
                assertEquals(0, resolutionUnits(candidate))
            }
        }
        assertEquals(referenceBackend.closeCalls, candidateBackend.closeCalls)
    }

    @Test
    fun oversizedMissPrefixesKeepEveryOriginalProviderAndRasterTouch() {
        val prefixes = List(9) { """{"type":"space","advances":{"Z":3}}""" }.joinToString(",")
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", prefixes + """,{"type":"ttf","file":"test:winner.ttf"}"""),
                "assets/test/font/winner.ttf" to byteArrayOf(1),
            )
        for (budget in listOf(2, 3, 8)) {
            var candidateRasters = 0
            var referenceRasters = 0
            val candidateBackend = observingBackend(1) { candidateRasters++ }
            val referenceBackend = observingBackend(1) { referenceRasters++ }
            MinecraftFontEngine(snapshot, { candidateBackend }, cacheEntries = budget).use { candidate ->
                MinecraftFontEngine(snapshot, { referenceBackend }, cacheEntries = budget).use { reference ->
                    for (scalar in listOf('A'.code, 'A'.code, 'B'.code, 'A'.code)) {
                        assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, scalar)
                    }
                    assertEquals(referenceRasters, candidateRasters)
                    assertEquals(referenceBackend.openCalls, candidateBackend.openCalls)
                    assertEquals(0, resolutionEntries(candidate))
                    assertEquals(0, resolutionUnits(candidate))
                }
            }
            assertEquals(referenceBackend.closeCalls, candidateBackend.closeCalls)
        }
    }

    @Test
    fun reentrantRasterEpochChangeRejectsOnlyMetadataAndKeepsOriginalGlyphWork() {
        val prefixes = List(9) { """{"type":"space","advances":{"Z":3}}""" }.joinToString(",")
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"space","advances":{"Z":3}},{"type":"ttf","file":"test:reentrant.ttf"}"""),
                FontTestResources.font("test:pressure", prefixes + """,{"type":"ttf","file":"test:reentrant.ttf"}"""),
                "assets/test/font/reentrant.ttf" to byteArrayOf(1),
            )
        lateinit var candidate: MinecraftFontEngine
        lateinit var reference: MinecraftFontEngine
        val pressure = ResourceId("test", "pressure")
        val candidateBackend = reenteringBackend { scalar -> candidate.glyph(pressure, scalar) }
        val referenceBackend = reenteringBackend { scalar -> originalGlyph(snapshot, reference, pressure, scalar) }
        candidate = MinecraftFontEngine(snapshot, { candidateBackend }, cacheEntries = 2)
        reference = MinecraftFontEngine(snapshot, { referenceBackend }, cacheEntries = 2)
        try {
            assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, 'A'.code)
            assertEquals(0, resolutionEntries(candidate))
            for (scalar in listOf('A'.code, 'B'.code, 'A'.code)) {
                assertEquivalent(snapshot, reference, candidate, FontTestResources.defaultFont, scalar)
            }
            assertEquals(referenceBackend.openCalls, candidateBackend.openCalls)
        } finally {
            candidate.close()
            reference.close()
        }
        assertEquals(0, resolutionEntries(candidate))
        assertEquals(0, resolutionUnits(candidate))
        assertEquals(referenceBackend.closeCalls, candidateBackend.closeCalls)
    }
}
