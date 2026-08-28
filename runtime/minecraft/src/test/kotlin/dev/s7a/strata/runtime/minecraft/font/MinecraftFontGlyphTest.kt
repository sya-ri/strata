package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies provider-specific scalar mapping, native metrics, and detached pipeline-ready pixels.
 */
internal class MinecraftFontGlyphTest {
    @Test
    fun nativeNonFiniteMetricsKeepStrictOffsetsAndAtlasMarkerValidation() {
        val glyph =
            MinecraftFontGlyph(
                Float.NaN,
                Float.NaN,
                Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY,
                Float.NaN,
                null,
                orientation = SampledImageOrientation.Normal,
                oversizedRasterSize = IntSize(257, 1),
            )
        assertTrue(glyph.advance.isNaN())
        assertTrue(glyph.left.isNaN())
        assertEquals(Float.NEGATIVE_INFINITY, glyph.top)
        assertThrows(IllegalArgumentException::class.java) { glyph.copy(left = 2f, right = 1f) }
        assertThrows(IllegalArgumentException::class.java) { glyph.copy(boldOffset = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { glyph.copy(shadowOffset = -1f) }
        assertThrows(IllegalArgumentException::class.java) { glyph.copy(oversizedRasterSize = IntSize(256, 256)) }
        assertThrows(IllegalArgumentException::class.java) { glyph.copy(oversizedRasterSize = IntSize(257, 0)) }
        assertThrows(IllegalArgumentException::class.java) { glyph.copy(image = createDrawImage(IntSize(1, 1), intArrayOf(-1))) }
    }

    @Test
    fun bitmapCellsUseUnicodeScalarsFullColorAndNativeRounding() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"bitmap","file":"test:font/bitmap.png","height":8,"ascent":6,"chars":["日한😀"]}"""),
                "assets/test/textures/font/bitmap.png" to byteArrayOf(1),
            )
        val pixels = IntArray(9 * 4)
        pixels[0] = 0x804080C0.toInt()
        pixels[1] = 0x01010203
        pixels[3 + 2] = -1
        pixels[6] = 0xFFABCDEF.toInt()
        val sheet = createDrawImage(IntSize(9, 4), pixels)
        val backend = FontTestBackend(decode = { sheet })
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }).use { engine ->
            val japanese = engine.glyph(FontTestResources.defaultFont, '日'.code)
            assertEquals(5.0f, japanese.advance)
            assertEquals(0.0f, japanese.left)
            assertEquals(1.0f, japanese.top)
            assertEquals(6.0f, japanese.right)
            assertEquals(9.0f, japanese.bottom)
            assertEquals(IntSize(3, 4), requireNotNull(japanese.image).size)
            assertEquals(0x804080C0.toInt(), requireNotNull(japanese.image).argbAt(0, 0))
            assertEquals(0x01010203, requireNotNull(japanese.image).argbAt(1, 0))
            assertEquals(7.0f, engine.glyph(FontTestResources.defaultFont, '한'.code).advance)
            assertEquals(3.0f, engine.glyph(FontTestResources.defaultFont, 0x1F600).advance)
            assertEquals(1, backend.decodeCalls)
        }
    }

    @Test
    fun emptyBitmapCellsRemainOneUnitWideAndNullCellsAllowFallback() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "default",
                    """
                    {"type":"bitmap","file":"test:empty.png","ascent":7,"chars":["A\u0000"]},
                    {"type":"space","advances":{"\u0000":4.5}}
                    """.trimIndent(),
                ),
                "assets/test/textures/empty.png" to byteArrayOf(1),
            )
        val backend = FontTestBackend(decode = { createDrawImage(IntSize(2, 1), intArrayOf(0x00123456, 0)) })
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }).use { engine ->
            val empty = engine.glyph(FontTestResources.defaultFont, 'A'.code)
            assertEquals(1.0f, empty.advance)
            assertEquals(0x00123456, requireNotNull(empty.image).argbAt(0, 0))
            assertEquals(4.5f, engine.glyph(FontTestResources.defaultFont, 0).advance)
        }
    }

    @Test
    fun bitmapBoundsPreserveNativeReciprocalOversampleAndOriginRounding() {
        for ((rasterizer, bottom) in listOf(MinecraftTrueTypeRasterizer.Stb to 8.0f, MinecraftTrueTypeRasterizer.FreeType to 7.9999995f)) {
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font("default", """{"type":"bitmap","file":"test:fractional.png","height":7,"ascent":6,"chars":["日"]}"""),
                    "assets/test/textures/fractional.png" to byteArrayOf(1),
                    capabilities = FontTestResources.compatibility.copy(rasterizer = rasterizer),
                )
            val backend = FontTestBackend(decode = { createDrawImage(IntSize(8, 8), IntArray(64) { -1 }) })
            MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }).use { engine ->
                val glyph = engine.glyph(FontTestResources.defaultFont, '日'.code)
                assertEquals(8.0f, glyph.advance)
                assertEquals(1.0f, glyph.top)
                assertEquals(6.9999995f, glyph.right)
                assertEquals(bottom, glyph.bottom)
            }
        }
    }

    @Test
    fun zeroHeightBitmapGlyphsKeepTheirNativeAdvanceAndEmptyQuad() {
        for (rasterizer in MinecraftTrueTypeRasterizer.entries) {
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font("default", """{"type":"bitmap","file":"test:zero.png","height":0,"ascent":0,"chars":["日"]}"""),
                    "assets/test/textures/zero.png" to byteArrayOf(1),
                    capabilities = FontTestResources.compatibility.copy(rasterizer = rasterizer),
                )
            val backend = FontTestBackend(decode = { createDrawImage(IntSize(8, 8), IntArray(64) { -1 }) })
            MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }).use { engine ->
                val glyph = engine.glyph(FontTestResources.defaultFont, '日'.code)
                assertEquals(1.0f, glyph.advance)
                assertEquals(0.0f, glyph.left)
                assertEquals(0.0f, glyph.right)
                assertEquals(7.0f, glyph.top)
                assertEquals(7.0f, glyph.bottom)
            }
        }
    }

    @Test
    fun negativeBitmapHeightKeepsNativeAdvanceRoundingAndUnmodifiedSourcePixels() {
        val pixels = IntArray(64) { 0xFF000000.toInt() or it }
        for ((rasterizer, top) in listOf(MinecraftTrueTypeRasterizer.Stb to 7f, MinecraftTrueTypeRasterizer.FreeType to 7.0000005f)) {
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font("default", """{"type":"bitmap","file":"test:negative.png","height":-7,"ascent":-7,"chars":["日"]}"""),
                    "assets/test/textures/negative.png" to byteArrayOf(1),
                    capabilities = FontTestResources.compatibility.copy(rasterizer = rasterizer),
                )
            val backend = FontTestBackend(decode = { createDrawImage(IntSize(8, 8), pixels) })
            MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }).use { engine ->
                val glyph = engine.glyph(FontTestResources.defaultFont, '日'.code)
                assertEquals(-5f, glyph.advance)
                assertEquals(-6.9999995f, glyph.left)
                assertEquals(0f, glyph.right)
                assertEquals(top, glyph.top)
                assertEquals(14f, glyph.bottom)
                assertEquals(SampledImageOrientation.FlipBoth, glyph.orientation)
                assertArrayEquals(pixels, checkNotNull(glyph.image).copyArgb())
                assertTrue(engine.diagnostics.isEmpty())
            }
        }
    }

    @Test
    fun unihexAdvanceTruncationDoesNotChangeFractionalQuadBounds() {
        val hex = "0045:${"00".repeat(16)}\n0046:${"80".repeat(16)}\n"
        for (fractional in listOf(false, true)) {
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font("default", """{"type":"unihex","hex_file":"test:font/odd.zip"}"""),
                    "assets/test/font/odd.zip" to FontTestResources.archive("odd.hex" to hex.toByteArray()),
                    capabilities = FontTestResources.compatibility.copy(fractionalUnihexAdvance = fractional),
                )
            MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { FontTestBackend() }).use { engine ->
                val empty = engine.glyph(FontTestResources.defaultFont, 'E'.code)
                assertEquals(if (fractional) 5.5f else 5.0f, empty.advance)
                assertEquals(4.5f, empty.right)
                assertEquals(IntSize(9, 16), requireNotNull(empty.image).size)
                val single = engine.glyph(FontTestResources.defaultFont, 'F'.code)
                assertEquals(if (fractional) 1.5f else 1.0f, single.advance)
                assertEquals(0.5f, single.right)
                assertEquals(-1, requireNotNull(single.image).argbAt(0, 0))
            }
        }
    }

    @Test
    fun unihexCropsSparseRowsAndKeepsEmptyGlyphNativeWidth() {
        val hex =
            "65E5:${"60".repeat(16)}\n" +
                "D55C:${"0060".repeat(16)}\n" +
                "1F600:${"800001".repeat(16)}\n" +
                "10FFFF:${"00000001".repeat(16)}\n" +
                "0020:${"00".repeat(16)}\n"
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"unihex","hex_file":"test:font/custom.zip"}"""),
                "assets/test/font/custom.zip" to FontTestResources.archive("unicode.hex" to hex.toByteArray()),
            )
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { FontTestBackend() }).use { engine ->
            val japanese = engine.glyph(FontTestResources.defaultFont, '日'.code)
            assertEquals(2.0f, japanese.advance)
            assertEquals(1.0f, japanese.right)
            assertEquals(8.0f, japanese.bottom)
            assertEquals(0.5f, japanese.boldOffset)
            assertEquals(0.5f, japanese.shadowOffset)
            assertEquals(IntSize(2, 16), requireNotNull(japanese.image).size)
            assertEquals(-1, requireNotNull(japanese.image).argbAt(0, 0))
            assertEquals(2.0f, engine.glyph(FontTestResources.defaultFont, '한'.code).advance)
            val emoji = engine.glyph(FontTestResources.defaultFont, 0x1F600)
            assertEquals(13.0f, emoji.advance)
            assertEquals(0, requireNotNull(emoji.image).argbAt(1, 0))
            assertEquals(-1, requireNotNull(emoji.image).argbAt(23, 15))
            assertEquals(1.5f, engine.glyph(FontTestResources.defaultFont, 0x10FFFF).advance)
            val empty = engine.glyph(FontTestResources.defaultFont, ' '.code)
            assertEquals(5.5f, empty.advance)
            assertEquals(IntSize(9, 16), requireNotNull(empty.image).size)
            assertEquals(0, requireNotNull(empty.image).argbAt(8, 15))
        }
    }

    @Test
    fun firstUnihexOverrideWinsAndInclusiveBoundsPreserveTransparentPadding() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "default",
                    """
                    {"type":"unihex","hex_file":"test:font/custom.zip","size_overrides":[
                        {"from":"日","to":"旦","left":0,"right":3},
                        {"from":"日","to":"旦","left":0,"right":7}
                    ]}
                    """.trimIndent(),
                ),
                "assets/test/font/custom.zip" to FontTestResources.archive("unicode.hex" to "65E5:${"80".repeat(16)}\n".toByteArray()),
            )
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { FontTestBackend() }).use { engine ->
            val glyph = engine.glyph(FontTestResources.defaultFont, '日'.code)
            assertEquals(3.0f, glyph.advance)
            assertEquals(IntSize(4, 16), requireNotNull(glyph.image).size)
            assertEquals(-1, requireNotNull(glyph.image).argbAt(0, 0))
            assertEquals(0, requireNotNull(glyph.image).argbAt(3, 15))
        }
    }

    @Test
    fun unihexOverridesPadBothSidesOutsideTheNativeBitfieldWithTransparency() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "default",
                    """
                    {"type":"unihex","hex_file":"test:font/padded.zip","size_overrides":[
                        {"from":"日","to":"旦","left":-1,"right":32}
                    ]}
                    """.trimIndent(),
                ),
                "assets/test/font/padded.zip" to FontTestResources.archive("padded.hex" to "65E5:${"80000001".repeat(16)}".toByteArray()),
            )
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { FontTestBackend() }).use { engine ->
            val glyph = engine.glyph(FontTestResources.defaultFont, '日'.code)
            val image = requireNotNull(glyph.image)
            assertEquals(18.0f, glyph.advance)
            assertEquals(IntSize(34, 16), image.size)
            assertEquals(0, image.argbAt(0, 0))
            assertEquals(-1, image.argbAt(1, 0))
            assertEquals(-1, image.argbAt(32, 15))
            assertEquals(0, image.argbAt(33, 15))
        }
    }

    @Test
    fun trueTypeSettingsAndSkipScalarsAreAppliedBeforeCallingTheFace() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "default",
                    """
                    {"type":"ttf","file":"test:custom.ttf","size":12.5,"oversample":1.75,"shift":[-0.2,0.6],"skip":["A","😀"]},
                    {"type":"space","advances":{"A":1,"😀":2}}
                    """.trimIndent(),
                ),
                "assets/test/font/custom.ttf" to byteArrayOf(3),
            )
        val requested = ArrayList<Int>()
        val expected = MinecraftFontGlyph(4.25f, -0.5f, 1.5f, 2.5f, 7.0f, null, boldOffset = 0.75f, shadowOffset = 0.75f)
        val backend =
            FontTestBackend(open = { bytes, settings ->
                assertEquals(3, bytes.single().toInt())
                assertEquals(MinecraftTrueTypeSettings(12.5f, 1.75f, -0.2f, 0.6f), settings)
                object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph {
                        requested += codePoint
                        return expected
                    }

                    override fun close() = Unit
                }
            })
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }).use { engine ->
            assertEquals(1.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
            assertEquals(2.0f, engine.glyph(FontTestResources.defaultFont, 0x1F600).advance)
            assertEquals(expected, engine.glyph(FontTestResources.defaultFont, '日'.code))
            assertEquals(listOf('日'.code), requested)
            assertEquals(1, backend.openCalls)
        }
    }

    @Test
    fun signedAndZeroTrueTypeSettingsReachTheBackendWithoutDiscardingSiblingProviders() {
        val cases = listOf(0f to 2f, -1f to 2f, 1f to 0f, 1f to -0f, 0f to 0f, -1f to -2f)
        for ((size, oversample) in cases) {
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font(
                        "default",
                        """
                        {"type":"space","advances":{"A":3}},
                        {"type":"ttf","file":"test:zero.ttf","size":$size,"oversample":$oversample},
                        {"type":"space","advances":{"日":9,"B":5}}
                        """.trimIndent(),
                    ),
                    "assets/test/font/zero.ttf" to byteArrayOf(1),
                )
            val expected = MinecraftFontGlyph(size / oversample, 0f, 0f, 0f, 0f, null)
            var requests = 0
            val backend =
                FontTestBackend(open = { _, settings ->
                    assertEquals(MinecraftTrueTypeSettings(size, oversample), settings)
                    object : MinecraftTrueTypeFace {
                        override fun glyph(codePoint: Int): MinecraftFontGlyph? {
                            requests++
                            return if (codePoint == '日'.code) expected else null
                        }

                        override fun close() = Unit
                    }
                })
            MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }).use { engine ->
                assertEquals(3f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                repeat(2) { assertSame(expected, engine.glyph(FontTestResources.defaultFont, '日'.code)) }
                assertEquals(5f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
                assertTrue(engine.diagnostics.isEmpty())
                assertEquals(2, requests)
            }
            assertEquals(1, backend.openCalls)
        }
    }

    @Test
    fun invalidDisabledBitmapFailsPreflightOnceWithoutUsingEarlierSpaceProviders() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "default",
                    """
                    {"type":"space","advances":{"A":1}},
                    {"type":"bitmap","file":"test:small.png","ascent":7,"chars":["AB"],"filter":{"uniform":true}}
                    """.trimIndent(),
                ),
                "assets/test/textures/small.png" to byteArrayOf(1),
            )
        val backend = FontTestBackend(decode = { createDrawImage(IntSize(1, 1), intArrayOf(-1)) })
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }).use { engine ->
            repeat(5) { assertEquals(6.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance) }
            assertEquals(1, backend.decodeCalls)
            assertEquals(MinecraftFontDiagnostic.Kind.ProviderLoadFailure, engine.diagnostics.single().kind)
        }
    }

    @Test
    fun missingGlyphOutlineIsNativeAndSpaceProvidersHaveNoPixels() {
        val snapshot = FontTestResources.snapshot(FontTestResources.font("default", """{"type":"space","advances":{" ":4}}"""))
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { FontTestBackend() }).use { engine ->
            assertNull(engine.glyph(FontTestResources.defaultFont, ' '.code).image)
            val missing = engine.glyph(FontTestResources.defaultFont, '日'.code)
            assertEquals(6.0f, missing.advance)
            val image = requireNotNull(missing.image)
            assertEquals(IntSize(5, 8), image.size)
            assertEquals(-1, image.argbAt(0, 1))
            assertEquals(-1, image.argbAt(2, 7))
            assertEquals(0, image.argbAt(2, 3))
            assertTrue(engine.diagnostics.isEmpty())
        }
    }

    @Test
    fun successfulProvidersInFailedBundlesRemainSharedUntilEngineClose() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("test:shared", """{"type":"ttf","file":"test:shared.ttf"}"""),
                FontTestResources.font(
                    "default",
                    """
                    {"type":"reference","id":"test:shared"},
                    {"type":"bitmap","file":"test:invalid.png","ascent":7,"chars":["A"]}
                    """.trimIndent(),
                ),
                "assets/test/font/shared.ttf" to byteArrayOf(1),
                "assets/test/textures/invalid.png" to byteArrayOf(2),
            )
        var closedFaces = 0
        val backend =
            FontTestBackend(
                decode = { throw IllegalArgumentException("invalid PNG") },
                open = { _, _ ->
                    object : MinecraftTrueTypeFace {
                        override fun glyph(codePoint: Int): MinecraftFontGlyph = MinecraftFontGlyph(4.0f, 0.0f, 0.0f, 0.0f, 0.0f, null)

                        override fun close() {
                            closedFaces++
                        }
                    }
                },
            )
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }).use { engine ->
            assertEquals(6.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
            assertEquals(4.0f, engine.glyph(FontJson.identifier("test:shared"), 'A'.code).advance)
            assertEquals(1, backend.openCalls)
            assertEquals(0, closedFaces)
        }
        assertEquals(1, closedFaces)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun propagatedRasterFailuresStillPermitCompleteScopedCleanup() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:font.ttf"}"""),
                "assets/test/font/font.ttf" to byteArrayOf(1),
            )
        val expected = IllegalStateException("rasterization failed")
        var closedFaces = 0
        val backend =
            FontTestBackend(open = { _, _ ->
                object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph = throw expected

                    override fun close() {
                        closedFaces++
                    }
                }
            })
        val engine = MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend })
        val actual =
            assertThrows(IllegalStateException::class.java) {
                engine.use { current -> current.glyph(FontTestResources.defaultFont, '日'.code) }
            }
        assertSame(expected, actual)
        assertEquals(1, closedFaces)
        assertEquals(1, backend.closeCalls)
        assertEquals(0, engine.retainedFaces)
        assertEquals(0, engine.retainedRasterEntries)
    }

    @Test
    fun fatalPreflightFailuresPropagateWithoutHidingOrLeakingPreviouslyOpenedFaces() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "default",
                    """
                    {"type":"ttf","file":"test:font.ttf"},
                    {"type":"bitmap","file":"test:font.png","ascent":7,"chars":["A"]}
                    """.trimIndent(),
                ),
                "assets/test/font/font.ttf" to byteArrayOf(1),
                "assets/test/textures/font.png" to byteArrayOf(2),
            )
        val expected = LinkageError("native decoder missing")
        var closedFaces = 0
        val backend =
            FontTestBackend(
                decode = { throw expected },
                open = { _, _ ->
                    object : MinecraftTrueTypeFace {
                        override fun glyph(codePoint: Int): MinecraftFontGlyph? = null

                        override fun close() {
                            closedFaces++
                        }
                    }
                },
            )
        val engine = MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend })
        val actual =
            assertThrows(LinkageError::class.java) {
                engine.use { current -> current.glyph(FontTestResources.defaultFont, 'A'.code) }
            }
        assertSame(expected, actual)
        assertTrue(engine.diagnostics.isEmpty())
        assertEquals(1, closedFaces)
        assertEquals(1, backend.closeCalls)
        assertEquals(0, engine.retainedFaces)
    }
}
