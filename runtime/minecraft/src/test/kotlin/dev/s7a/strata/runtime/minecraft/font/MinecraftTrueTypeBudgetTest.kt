package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies inclusive face-descriptor work and weighted live-input retention without native allocation.
 */
internal class MinecraftTrueTypeBudgetTest {
    @Test
    fun distinctSettingsChargeFaceCountAndInputEvenWhenTheyShareOneResource() {
        val file = "assets/test/font/shared.ttf" to ByteArray(3) { 1 }
        val fonts =
            listOf(
                FontTestResources.font("test:a", """{"type":"ttf","file":"test:shared.ttf","size":11}"""),
                FontTestResources.font("test:b", """{"type":"ttf","file":"test:shared.ttf","size":12}"""),
            )
        for ((count, expected) in listOf(0 to 0, 1 to 1, 2 to 2, 3 to 2)) {
            assertAdmittedFaces(fonts + file, MinecraftFontLoadLimits(maxTrueTypeFaces = count), expected)
        }
        for ((bytes, expected) in listOf(0L to 0, 2L to 0, 3L to 1, 5L to 1, 6L to 2, 7L to 2)) {
            assertAdmittedFaces(fonts + file, MinecraftFontLoadLimits(maxTrueTypeInputBytes = bytes), expected)
        }
    }

    @Test
    fun duplicateSettingsAndSkipVariantsConsumeOneDescriptorWhileSignedZeroRemainsDistinct() {
        val providers = List(128) { index -> """{"type":"ttf","file":"test:shared.ttf","skip":"${if (index % 2 == 0) 'A' else 'B'}"}""" }.joinToString(",")
        val source =
            FontTestResources.source(
                FontTestResources.font("default", providers),
                "assets/test/font/shared.ttf" to byteArrayOf(1, 2, 3),
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility, MinecraftFontOptions(), MinecraftFontLoadLimits(maxTrueTypeFaces = 1, maxTrueTypeInputBytes = 3))
        val backend = FontTestBackend(open = { _, settings -> FontTestFace({ glyph(settings.size) }) })
        MinecraftFontEngine(snapshot, { backend }).use { engine ->
            assertEquals(11f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
            assertEquals(11f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
            assertEquals(1, backend.openCalls)
            assertTrue(engine.diagnostics.isEmpty())
        }
        val resource = FontResource(ResourceId("test", "font/shared.ttf"), "test", byteArrayOf(1))
        val positive = FontFaceKey(resource, MinecraftTrueTypeSettings(shiftX = 0f))
        val negative = FontFaceKey(resource, MinecraftTrueTypeSettings(shiftX = -0f))
        assertEquals(2, setOf(positive, negative).size)
    }

    @Test
    fun rejectedFaceKeysDoNotResetWeightedWorkOrPoisonAnAlreadyAdmittedSibling() {
        val source =
            FontTestResources.source(
                FontTestResources.font("test:a", """{"type":"ttf","file":"test:shared.ttf","size":11}"""),
                FontTestResources.font("test:b", """{"type":"ttf","file":"test:shared.ttf","size":12},{"type":"ttf","file":"test:shared.ttf","size":12},{"type":"space","advances":{"A":2}}"""),
                FontTestResources.font("test:c", """{"type":"ttf","file":"test:small.ttf","size":13}"""),
                FontTestResources.font("test:d", """{"type":"ttf","file":"test:shared.ttf","size":11,"skip":"B"}"""),
                "assets/test/font/shared.ttf" to byteArrayOf(1, 2, 3),
                "assets/test/font/small.ttf" to byteArrayOf(4),
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility, MinecraftFontOptions(), MinecraftFontLoadLimits(maxTrueTypeInputBytes = 5))
        val backend = FontTestBackend(open = { _, settings -> FontTestFace({ glyph(settings.size) }) })
        MinecraftFontEngine(snapshot, { backend }).use { engine ->
            assertEquals(11f, engine.glyph(ResourceId("test", "a"), 'A'.code).advance)
            assertEquals(6f, engine.glyph(ResourceId("test", "b"), 'A'.code).advance)
            assertEquals(6f, engine.glyph(ResourceId("test", "c"), 'A'.code).advance)
            assertEquals(11f, engine.glyph(ResourceId("test", "d"), 'A'.code).advance)
            assertEquals(1, backend.openCalls)
            assertEquals(mapOf(ResourceId("test", "b") to 2, ResourceId("test", "c") to 1), snapshot.diagnostics.groupingBy(MinecraftFontDiagnostic::font).eachCount())
        }
    }

    @Test
    fun weightedNativeFaceRetentionEvictsBeforeOpenIndependentlyOfTheSixteenFaceCount() {
        for (size in listOf(255, 256, 257)) verifyWeightedRetention(size)
    }

    private fun assertAdmittedFaces(
        files: List<Pair<String, ByteArray>>,
        limits: MinecraftFontLoadLimits,
        expected: Int,
    ) {
        val snapshot = MinecraftFontSnapshot.load(listOf(FontTestResources.source(*files.toTypedArray())), FontTestResources.compatibility, MinecraftFontOptions(), limits)
        val backend = FontTestBackend(open = { _, settings -> FontTestFace({ glyph(settings.size) }) })
        MinecraftFontEngine(snapshot, { backend }).use { engine ->
            assertEquals(if (0 < expected) 11f else 6f, engine.glyph(ResourceId("test", "a"), 'A'.code).advance)
            assertEquals(if (1 < expected) 12f else 6f, engine.glyph(ResourceId("test", "b"), 'A'.code).advance)
            assertEquals(expected, backend.openCalls)
            assertEquals(2 - expected, snapshot.diagnostics.size)
        }
        assertEquals(1, backend.closeCalls)
    }

    private fun verifyWeightedRetention(size: Int) {
        val files =
            (1..3).flatMap { index ->
                listOf(
                    FontTestResources.font("test:font_$index", """{"type":"ttf","file":"test:face_$index.ttf"}"""),
                    "assets/test/font/face_$index.ttf" to ByteArray(size) { index.toByte() },
                )
            }
        val snapshot =
            MinecraftFontSnapshot.load(
                listOf(FontTestResources.source(*files.toTypedArray())),
                FontTestResources.compatibility,
                MinecraftFontOptions(),
                MinecraftFontLoadLimits(maxAssetBytes = 512),
            )
        var liveBytes = 0
        var peakBytes = 0
        var closedFaces = 0
        val backend =
            FontTestBackend(
                open = { bytes, _ ->
                    liveBytes += bytes.size
                    peakBytes = maxOf(peakBytes, liveBytes)
                    assertTrue(liveBytes <= 512)
                    FontTestFace(
                        { glyph(11f) },
                        {
                            liveBytes -= size
                            closedFaces++
                        },
                    )
                },
                release = { assertEquals(0, liveBytes) },
            )
        val engine = MinecraftFontEngine(snapshot, { backend }, cacheEntries = 0, maxFaces = 16)
        engine.use {
            for (index in listOf(1, 2, 3, 1)) assertEquals(11f, engine.glyph(ResourceId("test", "font_$index"), 'A'.code).advance)
            assertEquals(4, backend.openCalls)
            val expectedFaces = if (size * 2 <= 512) 2 else 1
            assertEquals(expectedFaces, engine.retainedFaces)
            assertEquals(size * expectedFaces, liveBytes)
            assertEquals(size * expectedFaces, peakBytes)
            assertTrue(engine.diagnostics.isEmpty())
        }
        engine.close()
        assertEquals(0, engine.retainedFaces)
        assertEquals(0, liveBytes)
        assertEquals(4, closedFaces)
        assertEquals(1, backend.closeCalls)
    }

    private fun glyph(advance: Float): MinecraftFontGlyph = MinecraftFontGlyph(advance, 0f, 0f, 1f, 1f, createDrawImage(IntSize(1, 1), intArrayOf(-1)))
}
