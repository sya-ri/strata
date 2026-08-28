package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies canonical resource-derived work, descriptor reuse, and bounded failures independently of provider declaration identity.
 */
internal class MinecraftFontResourceCacheTest {
    @Test
    fun bitmapDescriptorChecksSurviveZeroAndSingleEntryRasterCaches() {
        val providers =
            """
            {"type":"bitmap","file":"test:a.png","ascent":7,"chars":["A"],"filter":{"uniform":true}},
            {"type":"bitmap","file":"test:b.png","ascent":7,"chars":["A"],"filter":{"uniform":true}},
            {"type":"bitmap","file":"test:a.png","ascent":7,"chars":["B"],"filter":{"uniform":true}},
            {"type":"space","advances":{"A":3,"B":4}}
            """.trimIndent()
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", providers),
                FontTestResources.font("test:other", providers),
                "assets/test/textures/a.png" to byteArrayOf(1),
                "assets/test/textures/b.png" to byteArrayOf(2),
            )
        for (entries in listOf(0, 1)) {
            val decoded = ArrayList<Int>()
            val backend =
                FontTestBackend(decode = { bytes ->
                    decoded.add(bytes.single().toInt())
                    createDrawImage(IntSize(1, 1), intArrayOf(-1))
                })
            MinecraftFontEngine(snapshot, { backend }, cacheEntries = entries, cacheBytes = 4).use { engine ->
                repeat(3) {
                    assertEquals(3f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                    assertEquals(4f, engine.glyph(ResourceId("test", "other"), 'B'.code).advance)
                }
                assertEquals(listOf(1, 2), decoded)
                assertTrue(engine.retainedRasterEntries <= entries)
                assertTrue(engine.diagnostics.isEmpty())
            }
            assertEquals(1, backend.closeCalls)
        }
    }

    @Test
    fun duplicateBitmapDeclarationsShareOneCellScanAcrossMetricsAndScalarMappings() {
        val files =
            (1..96).map { height ->
                FontTestResources.font("test:height_$height", """{"type":"bitmap","file":"test:shared.png","height":$height,"ascent":${height - 1},"chars":["${if (height % 2 == 0) 'A' else 'B'}"]}""")
            } + ("assets/test/textures/shared.png" to byteArrayOf(1))
        val snapshot = FontTestResources.snapshot(*files.toTypedArray())
        val backend = FontTestBackend(decode = { createDrawImage(IntSize(1, 1), intArrayOf(0xC0442288.toInt())) })
        val engine = MinecraftFontEngine(snapshot, { backend }, cacheEntries = 2)
        engine.use {
            val original = requireNotNull(engine.glyph(ResourceId("test", "height_1"), 'B'.code).image)
            for (height in 1..96) {
                val codePoint = if (height % 2 == 0) 'A'.code else 'B'.code
                val glyph = engine.glyph(ResourceId("test", "height_$height"), codePoint)
                assertEquals(height.toFloat() + 1, glyph.advance)
                assertSame(original, glyph.image)
                assertEquals(2, engine.retainedRasterEntries)
                assertEquals(8L, engine.retainedRasterBytes)
            }
            assertEquals(1, backend.decodeCalls)
            assertTrue(engine.diagnostics.isEmpty())
        }
        assertEquals(0L, engine.retainedRasterBytes)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun bitmapGridIdentityKeepsDistinctCellsWhileSharingTheDecodedResource() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"bitmap","file":"test:grid.png","height":1,"ascent":1,"chars":["AB"]}"""),
                FontTestResources.font("test:other", """{"type":"bitmap","file":"test:grid.png","height":1,"ascent":1,"chars":["CDEF"]}"""),
                "assets/test/textures/grid.png" to byteArrayOf(1),
            )
        val backend = FontTestBackend(decode = { createDrawImage(IntSize(4, 1), intArrayOf(-1, -2, -3, -4)) })
        MinecraftFontEngine(snapshot, { backend }).use { engine ->
            val wide = engine.glyph(FontTestResources.defaultFont, 'A'.code)
            val narrow = engine.glyph(ResourceId("test", "other"), 'C'.code)
            assertEquals(3f, wide.advance)
            assertEquals(2f, narrow.advance)
            assertEquals(IntSize(2, 1), requireNotNull(wide.image).size)
            assertEquals(IntSize(1, 1), requireNotNull(narrow.image).size)
            assertNotSame(wide.image, narrow.image)
            assertEquals(1, backend.decodeCalls)
        }
    }

    @Test
    fun trueTypeDescriptorChecksSurviveSingleFaceEvictionAndInterleavedDuplicates() {
        val providers =
            """
            {"type":"ttf","file":"test:a.ttf","filter":{"uniform":true}},
            {"type":"ttf","file":"test:b.ttf","filter":{"uniform":true}},
            {"type":"ttf","file":"test:a.ttf","skip":"A","filter":{"uniform":true}},
            {"type":"space","advances":{"A":3}}
            """.trimIndent()
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", providers),
                FontTestResources.font("test:other", providers),
                "assets/test/font/a.ttf" to byteArrayOf(1),
                "assets/test/font/b.ttf" to byteArrayOf(2),
            )
        val opened = ArrayList<Int>()
        val closed = ArrayList<Int>()
        val backend =
            FontTestBackend(open = { bytes, _ ->
                val identity = bytes.single().toInt()
                opened.add(identity)
                FontTestFace({ error("Filtered faces must not perform glyph lookup.") }, { closed.add(identity) })
            })
        MinecraftFontEngine(snapshot, { backend }, maxFaces = 1).use { engine ->
            assertEquals(3f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
            assertEquals(3f, engine.glyph(ResourceId("test", "other"), 'A'.code).advance)
            assertEquals(listOf(1, 2), opened)
            assertEquals(listOf(1), closed)
            assertEquals(1, engine.retainedFaces)
            assertTrue(engine.diagnostics.isEmpty())
        }
        assertEquals(opened, closed)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun trueTypeSkipsRemainProviderSpecificWhileNativeFacesAndGlyphsAreShared() {
        val duplicateProviders = List(512) { """{"type":"ttf","file":"test:shared.ttf","skip":"AB"}""" }.joinToString(",")
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:shared.ttf","skip":"A"},{"type":"space","advances":{"A":3}}"""),
                FontTestResources.font("test:other", """{"type":"ttf","file":"test:shared.ttf","skip":"B"},{"type":"space","advances":{"B":4}}"""),
                FontTestResources.font("test:duplicates", "$duplicateProviders," + """{"type":"space","advances":{"A":5}}"""),
                "assets/test/font/shared.ttf" to byteArrayOf(1),
            )
        val requested = ArrayList<Int>()
        val backend =
            FontTestBackend(open = { _, _ ->
                FontTestFace({ codePoint ->
                    requested.add(codePoint)
                    rasterGlyph()
                })
            })
        MinecraftFontEngine(snapshot, { backend }).use { engine ->
            assertEquals(11f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
            assertEquals(11f, engine.glyph(ResourceId("test", "other"), 'A'.code).advance)
            assertEquals(3f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
            assertEquals(4f, engine.glyph(ResourceId("test", "other"), 'B'.code).advance)
            assertEquals(5f, engine.glyph(ResourceId("test", "duplicates"), 'A'.code).advance)
            assertEquals(listOf('B'.code, 'A'.code), requested)
            assertEquals(1, backend.openCalls)
            assertTrue(engine.diagnostics.isEmpty())
        }
    }

    @Test
    fun failedSharedDescriptorsDoNotRepeatNativeWorkAndKeepBundleDiagnosticsIndependent() {
        val providers = """{"type":"bitmap","file":"test:bad.png","ascent":7,"chars":["A"]},{"type":"ttf","file":"test:bad.ttf"}"""
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", providers),
                FontTestResources.font("test:other", providers),
                "assets/test/textures/bad.png" to byteArrayOf(1),
                "assets/test/font/bad.ttf" to byteArrayOf(2),
            )
        val backend = FontTestBackend(decode = { throw IllegalArgumentException("bad bitmap") }, open = { _, _ -> throw IllegalArgumentException("bad face") })
        MinecraftFontEngine(snapshot, { backend }, cacheEntries = 0).use { engine ->
            repeat(3) {
                assertEquals(6f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                assertEquals(6f, engine.glyph(ResourceId("test", "other"), 'A'.code).advance)
            }
            assertEquals(1, backend.decodeCalls)
            assertEquals(1, backend.openCalls)
            assertEquals(mapOf(FontTestResources.defaultFont to 2, ResourceId("test", "other") to 2), engine.diagnostics.groupingBy(MinecraftFontDiagnostic::font).eachCount())
            assertEquals(0, engine.retainedRasterEntries)
        }
    }

    @Test
    fun customReturnedImagesPoisonTheirFaceBeforeRepeatedOrOtherScalarAllocations() {
        for (entries in listOf(0, 1, 3)) {
            verifyReturnedGlyphFailure(IntSize(2, 1), MinecraftFontLoadLimits(maxImageDimension = 1, maxImageBytes = 8), entries)
            verifyReturnedGlyphFailure(IntSize(1, 2), MinecraftFontLoadLimits(maxImageDimension = 2, maxImageBytes = 4), entries)
        }
    }

    @Test
    fun preallocationFailuresUseBoundedEntriesWithoutDisablingHealthyFaces() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:a.ttf"}"""),
                "assets/test/font/a.ttf" to byteArrayOf(1),
            )
        var attempts = 0
        val backend =
            FontTestBackend(open = { _, _ ->
                FontTestFace({ codePoint ->
                    if (codePoint == 'A'.code) {
                        attempts++
                        throw MinecraftFontLoadLimitException("No image was allocated.")
                    }
                    rasterGlyph()
                })
            })
        MinecraftFontEngine(snapshot, { backend }, cacheEntries = 1).use { engine ->
            repeat(2) { assertThrows(MinecraftFontLoadLimitException::class.java) { engine.glyph(FontTestResources.defaultFont, 'A'.code) } }
            assertEquals(1, attempts)
            assertEquals(1, engine.retainedRasterEntries)
            assertEquals(0L, engine.retainedRasterBytes)
            assertEquals(11f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
            assertThrows(MinecraftFontLoadLimitException::class.java) { engine.glyph(FontTestResources.defaultFont, 'A'.code) }
            assertEquals(2, attempts)
            assertEquals(1, backend.openCalls)
            assertEquals(1, engine.retainedFaces)
        }
    }

    @Test
    fun poisoningPreservesTheImageLimitFailureWhenNativeFaceCleanupAlsoFails() {
        val source =
            FontTestResources.source(
                FontTestResources.font("default", """{"type":"ttf","file":"test:a.ttf"}"""),
                "assets/test/font/a.ttf" to byteArrayOf(1),
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility, MinecraftFontOptions(), MinecraftFontLoadLimits(maxImageDimension = 1))
        val closeFailure = IllegalStateException("face cleanup")
        var closes = 0
        val backend =
            FontTestBackend(open = { _, _ ->
                FontTestFace(
                    { rasterGlyph(IntSize(2, 1)) },
                    {
                        closes++
                        throw closeFailure
                    },
                )
            })
        val engine = MinecraftFontEngine(snapshot, { backend }, cacheEntries = 0)
        engine.use {
            val failure = assertThrows(MinecraftFontLoadLimitException::class.java) { engine.glyph(FontTestResources.defaultFont, 'A'.code) }
            assertSame(closeFailure, failure.suppressed.single())
            assertEquals(0, engine.retainedFaces)
            assertEquals(1, closes)
            assertThrows(MinecraftFontLoadLimitException::class.java) { engine.glyph(FontTestResources.defaultFont, 'B'.code) }
            assertEquals(1, backend.openCalls)
        }
        assertEquals(1, closes)
        assertEquals(1, backend.closeCalls)
    }

    private fun verifyReturnedGlyphFailure(
        size: IntSize,
        limits: MinecraftFontLoadLimits,
        entries: Int,
    ) {
        val source =
            FontTestResources.source(
                FontTestResources.font("default", """{"type":"ttf","file":"test:a.ttf"}"""),
                FontTestResources.font("test:other", """{"type":"ttf","file":"test:b.ttf"}"""),
                FontTestResources.font("test:third", """{"type":"ttf","file":"test:c.ttf"}"""),
                "assets/test/font/a.ttf" to byteArrayOf(1),
                "assets/test/font/b.ttf" to byteArrayOf(2),
                "assets/test/font/c.ttf" to byteArrayOf(3),
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility, MinecraftFontOptions(), limits)
        var rejectedAllocations = 0
        var faceCloses = 0
        val opened = ArrayList<Int>()
        val backend =
            FontTestBackend(open = { bytes, _ ->
                opened.add(bytes.single().toInt())
                FontTestFace(
                    { codePoint ->
                        if (codePoint == 'A'.code) {
                            rejectedAllocations++
                            rasterGlyph(size)
                        } else {
                            rasterGlyph()
                        }
                    },
                    { faceCloses++ },
                )
            })
        val engine = MinecraftFontEngine(snapshot, { backend }, cacheEntries = entries, maxFaces = 1)
        engine.use {
            val valid = engine.glyph(FontTestResources.defaultFont, 'B'.code)
            assertEquals(IntSize(1, 1), requireNotNull(valid.image).size)
            assertThrows(MinecraftFontLoadLimitException::class.java) { engine.glyph(FontTestResources.defaultFont, 'A'.code) }
            assertEquals(0, engine.retainedFaces)
            assertEquals(1, faceCloses)
            repeat(16) { index ->
                val font = ResourceId("test", if (index % 2 == 0) "other" else "third")
                assertEquals(11f, engine.glyph(font, 'C'.code + index).advance)
                assertThrows(MinecraftFontLoadLimitException::class.java) { engine.glyph(FontTestResources.defaultFont, 'A'.code + index) }
                assertTrue(engine.retainedRasterEntries <= entries)
            }
            assertEquals(1, rejectedAllocations)
            assertEquals(17, backend.openCalls)
            assertEquals(1, opened.count { identity -> identity == 1 })
            assertThrows(MinecraftFontLoadLimitException::class.java) { engine.glyph(FontTestResources.defaultFont, 'A'.code) }
            assertEquals(1, rejectedAllocations)
        }
        assertEquals(0, engine.retainedRasterEntries)
        assertEquals(17, faceCloses)
        assertEquals(1, backend.closeCalls)
    }

    private fun rasterGlyph(size: IntSize = IntSize(1, 1)): MinecraftFontGlyph = MinecraftFontGlyph(11f, 0f, 0f, size.width.toFloat(), size.height.toFloat(), createDrawImage(size, IntArray(size.width * size.height) { -1 }))
}
