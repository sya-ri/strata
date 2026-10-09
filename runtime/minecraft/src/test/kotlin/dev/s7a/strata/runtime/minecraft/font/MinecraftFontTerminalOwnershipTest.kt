package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies callback-driven terminal ownership without native dependencies or a loaded game.
 * Explicit native-call and release observations supplement the independent original provider walk.
 */
internal class MinecraftFontTerminalOwnershipTest {
    @Test
    fun completedImageNullAndMissingGlyphsCannotReadmitTerminalRasters() {
        val snapshot = trueTypeSnapshot()
        for (entries in listOf(0, 2, 4_096)) {
            for (missing in listOf(false, true)) {
                for (failure in failures()) {
                    for (original in listOf(false, true)) verifyGlyphClose(snapshot, entries, missing, failure, original)
                }
            }
        }
    }

    private fun verifyGlyphClose(
        snapshot: MinecraftFontSnapshot,
        entries: Int,
        missing: Boolean,
        failure: Throwable?,
        original: Boolean,
    ) {
        lateinit var owner: MinecraftFontEngine
        var closes = 0
        var glyphs = 0
        val detached = MinecraftFontGlyph(7f, 0f, 0f, 0f, 0f, null)
        val backend =
            FontTestBackend(
                open = { _, _ ->
                    FontTestFace(
                        {
                            glyphs++
                            owner.close()
                            if (missing) null else detached
                        },
                        {
                            closes++
                            owner.close()
                        },
                    )
                },
                release = {
                    owner.close()
                    if (failure != null) throw failure
                },
            )
        owner = MinecraftFontEngine(snapshot, { backend }, cacheEntries = entries, maxFaces = 1)
        val expected = if (missing) owner.glyph(ResourceId("unknown", "terminal"), 'A'.code) else detached
        try {
            val result = runCatching { FontTerminalTestSupport.glyph(snapshot, owner, original) }
            if (failure == null) assertEquals(expected, result.getOrThrow()) else assertSame(failure, result.exceptionOrNull())
            FontTerminalTestSupport.assertTerminal(owner)
            assertEquals(1, backend.openCalls)
            assertEquals(1, backend.closeCalls)
            assertEquals(1, closes)
            assertEquals(1, glyphs)
        } finally {
            owner.close()
        }
    }

    @Test
    fun successfulFaceReturnedAfterCloseIsReleasedWithoutPreflightAdmission() {
        for (entries in listOf(0, 2, 4_096)) {
            for (disabled in listOf(false, true)) {
                for (failure in failures()) {
                    for (original in listOf(false, true)) verifyLateFace(entries, disabled, failure, original)
                }
            }
        }
    }

    private fun verifyLateFace(
        entries: Int,
        disabled: Boolean,
        failure: Throwable?,
        original: Boolean,
    ) {
        val extra = if (disabled) """, "filter":{"uniform":true}""" else ""
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:face.ttf"$extra},{"type":"ttf","file":"test:unused.ttf"}"""),
                "assets/test/font/face.ttf" to byteArrayOf(1),
                "assets/test/font/unused.ttf" to byteArrayOf(2),
            )
        lateinit var owner: MinecraftFontEngine
        var active = 0
        var closes = 0
        var glyphs = 0
        val backend =
            FontTestBackend(
                open = { _, _ ->
                    active++
                    val face =
                        FontTestFace(
                            {
                                glyphs++
                                MinecraftFontGlyph(7f, 0f, 0f, 0f, 0f, null)
                            },
                            {
                                active--
                                closes++
                                owner.close()
                                if (failure != null) throw failure
                            },
                        )
                    owner.close()
                    face
                },
                release = { owner.close() },
            )
        owner = MinecraftFontEngine(snapshot, { backend }, cacheEntries = entries, maxFaces = 1)
        val missing = owner.glyph(ResourceId("unknown", "terminal"), 'A'.code)
        try {
            val result = runCatching { FontTerminalTestSupport.glyph(snapshot, owner, original) }
            if (failure == null) assertEquals(missing, result.getOrThrow()) else assertSame(failure, result.exceptionOrNull())
            assertEquals(0, active)
            assertEquals(0, glyphs)
            assertEquals(1, closes)
            assertEquals(1, backend.openCalls)
            assertEquals(1, backend.closeCalls)
            assertTrue(owner.diagnostics.isEmpty())
            FontTerminalTestSupport.assertTerminal(owner)
        } finally {
            owner.close()
        }
    }

    @Test
    fun lateDecodeAndOpenFailuresKeepOrdinaryDiagnosticsAndFatalIdentityWithoutRetainingKeys() {
        for (entries in listOf(0, 2, 4_096)) {
            for (bitmap in listOf(false, true)) {
                for (failure in failures()) {
                    for (original in listOf(false, true)) verifyLoadingClose(entries, bitmap, failure, original)
                }
            }
        }
    }

    private fun verifyLoadingClose(
        entries: Int,
        bitmap: Boolean,
        failure: Throwable?,
        original: Boolean,
    ) {
        if (bitmap.not() && failure == null) return
        val snapshot = if (bitmap) bitmapSnapshot() else trueTypeSnapshot()
        val detached = createDrawImage(IntSize(8, 8), IntArray(64) { -1 })
        lateinit var owner: MinecraftFontEngine
        val backend =
            FontTestBackend(
                decode = {
                    owner.close()
                    if (failure != null) throw failure
                    detached
                },
                open = { _, _ ->
                    owner.close()
                    throw checkNotNull(failure)
                },
                release = { owner.close() },
            )
        owner = MinecraftFontEngine(snapshot, { backend }, cacheEntries = entries, maxFaces = 1)
        val missing = owner.glyph(ResourceId("unknown", "terminal"), 'A'.code)
        try {
            val result = runCatching { FontTerminalTestSupport.glyph(snapshot, owner, original) }
            if (failure == null || failure is Exception) {
                assertEquals(missing, result.getOrThrow())
                if (failure != null) {
                    assertEquals(MinecraftFontDiagnostic.Kind.ProviderLoadFailure, owner.diagnostics.single().kind)
                    assertEquals(failure.message, owner.diagnostics.single().message)
                } else {
                    assertTrue(owner.diagnostics.isEmpty())
                }
            } else {
                assertSame(failure, result.exceptionOrNull())
                assertTrue(owner.diagnostics.isEmpty())
            }
            assertEquals(if (bitmap) 1 else 0, backend.decodeCalls)
            assertEquals(if (bitmap) 0 else 1, backend.openCalls)
            assertEquals(1, backend.closeCalls)
            assertEquals(-1, detached.argbAt(0, 0))
            FontTerminalTestSupport.assertTerminal(owner)
        } finally {
            owner.close()
        }
    }

    @Test
    fun evictionCloseStopsReplacementOpenAndKeepsItsFailureBehavior() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:first.ttf"},{"type":"ttf","file":"test:second.ttf"}"""),
                "assets/test/font/first.ttf" to byteArrayOf(1),
                "assets/test/font/second.ttf" to byteArrayOf(2),
            )
        for (original in listOf(false, true)) {
            for (failure in failures()) {
                lateinit var owner: MinecraftFontEngine
                var closes = 0
                var glyphs = 0
                val backend =
                    FontTestBackend(
                        open = { _, _ ->
                            FontTestFace(
                                {
                                    glyphs++
                                    null
                                },
                                {
                                    closes++
                                    owner.close()
                                    if (failure != null) throw failure
                                },
                            )
                        },
                        release = { owner.close() },
                    )
                owner = MinecraftFontEngine(snapshot, { backend }, cacheEntries = 2, maxFaces = 1)
                val missing = owner.glyph(ResourceId("unknown", "terminal"), 'A'.code)
                val result = runCatching { FontTerminalTestSupport.glyph(snapshot, owner, original) }
                if (failure == null || failure is Exception) assertEquals(missing, result.getOrThrow()) else assertSame(failure, result.exceptionOrNull())
                assertEquals(if (failure is Exception) listOf(failure.message) else emptyList<String>(), owner.diagnostics.map { it.message })
                assertEquals(1, backend.openCalls)
                assertEquals(1, backend.closeCalls)
                assertEquals(1, closes)
                assertEquals(0, glyphs)
                FontTerminalTestSupport.assertTerminal(owner)
            }
        }
    }

    @Test
    fun validatedReopenReleasesALateFaceAndPropagatesItsExactCleanupFailure() {
        for (failure in failures()) {
            for (original in listOf(false, true)) verifyReopenClose(failure, original)
        }
    }

    private fun verifyReopenClose(
        failure: Throwable?,
        original: Boolean,
    ) {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:first.ttf"}"""),
                FontTestResources.font("test:pressure", """{"type":"ttf","file":"test:second.ttf"}"""),
                "assets/test/font/first.ttf" to byteArrayOf(1),
                "assets/test/font/second.ttf" to byteArrayOf(2),
            )
        lateinit var owner: MinecraftFontEngine
        var opens = 0
        var closes = 0
        var glyphs = 0
        val backend =
            FontTestBackend(
                open = { _, _ ->
                    opens++
                    val late = opens == 3
                    val face =
                        FontTestFace(
                            {
                                glyphs++
                                MinecraftFontGlyph(7f, 0f, 0f, 0f, 0f, null)
                            },
                            {
                                closes++
                                if (late && failure != null) throw failure
                            },
                        )
                    if (late) owner.close()
                    face
                },
            )
        owner = MinecraftFontEngine(snapshot, { backend }, cacheEntries = 0, maxFaces = 1)
        assertEquals(7f, FontTerminalTestSupport.glyph(snapshot, owner, original).advance)
        assertEquals(7f, FontTerminalTestSupport.glyph(snapshot, owner, original, font = ResourceId("test", "pressure")).advance)
        val missing = owner.glyph(ResourceId("unknown", "terminal"), 'A'.code)
        val result = runCatching { FontTerminalTestSupport.glyph(snapshot, owner, original) }
        if (failure == null) assertEquals(missing, result.getOrThrow()) else assertSame(failure, result.exceptionOrNull())
        assertEquals(3, backend.openCalls)
        assertEquals(1, backend.closeCalls)
        assertEquals(3, closes)
        assertEquals(2, glyphs)
        FontTerminalTestSupport.assertTerminal(owner)
    }

    @Test
    fun poisoningCloseKeepsTheAllocationFailurePrimaryAndTerminalCountersEmpty() {
        val snapshot =
            MinecraftFontSnapshot.load(
                listOf(FontTestResources.source(FontTestResources.font("default", """{"type":"ttf","file":"test:face.ttf"}"""), "assets/test/font/face.ttf" to byteArrayOf(1))),
                FontTestResources.compatibility,
                MinecraftFontOptions(),
                MinecraftFontLoadLimits(maxImageBytes = 16),
            )
        for (entries in listOf(0, 2, 4_096)) {
            for (original in listOf(false, true)) {
                val releaseFailure = IllegalStateException("terminal poisoned backend")
                lateinit var owner: MinecraftFontEngine
                var closes = 0
                val backend =
                    FontTestBackend(
                        open = { _, _ ->
                            FontTestFace(
                                { raster(8) },
                                {
                                    closes++
                                    owner.close()
                                },
                            )
                        },
                        release = {
                            owner.close()
                            throw releaseFailure
                        },
                    )
                owner = MinecraftFontEngine(snapshot, { backend }, cacheEntries = entries, maxFaces = 1)
                val failure = assertThrows(MinecraftFontLoadLimitException::class.java) { FontTerminalTestSupport.glyph(snapshot, owner, original) }
                assertEquals(listOf(releaseFailure), failure.suppressed.toList())
                assertEquals(1, closes)
                assertEquals(1, backend.openCalls)
                assertEquals(1, backend.closeCalls)
                FontTerminalTestSupport.assertTerminal(owner)
            }
        }
    }

    @Test
    fun imageChecksAfterCallbackCloseRetainTheExistingPublicClosedFailure() {
        for (original in listOf(false, true)) {
            val snapshot = trueTypeSnapshot()
            lateinit var owner: MinecraftFontEngine
            val detached = raster(1)
            val backend =
                FontTestBackend(
                    open = { _, _ ->
                        FontTestFace({
                            owner.close()
                            detached
                        })
                    },
                )
            owner = MinecraftFontEngine(snapshot, { backend })
            val failure = assertThrows(IllegalStateException::class.java) { FontTerminalTestSupport.glyph(snapshot, owner, original) }
            assertEquals("Font engine is closed.", failure.message)
            assertEquals(-1, checkNotNull(detached.image).argbAt(0, 0))
            FontTerminalTestSupport.assertTerminal(owner)
        }
    }

    @Test
    fun foreignThreadCloseCannotAffectAnIndependentOwnerOrNormalGlyphAndEvictionOrder() {
        val snapshot = trueTypeSnapshot()
        lateinit var owner: MinecraftFontEngine
        var closes = 0
        val backend =
            FontTestBackend(
                open = { _, _ ->
                    FontTestFace(
                        {
                            assertForeignThreadCloseRejected(owner)
                            raster(1)
                        },
                        { closes++ },
                    )
                },
            )
        owner = MinecraftFontEngine(snapshot, { backend }, cacheEntries = 2)
        MinecraftFontEngine(snapshot, { FontTestBackend(open = { _, _ -> FontTestFace({ raster(1) }) }) }, cacheEntries = 2).use { independent ->
            val expected = independent.glyph(FontTestResources.defaultFont, 'A'.code)
            assertEquals(expected, owner.glyph(FontTestResources.defaultFont, 'A'.code))
            owner.close()
            FontTerminalTestSupport.assertTerminal(owner)
            assertEquals(expected, independent.glyph(FontTestResources.defaultFont, 'A'.code))
            assertEquals(1, independent.retainedFaces)
        }
        assertEquals(1, closes)
        assertEquals(1, backend.closeCalls)
        var candidateGlyphs = 0
        var referenceGlyphs = 0
        val candidateBackend =
            FontTestBackend(open = { _, _ ->
                FontTestFace({
                    candidateGlyphs++
                    raster(1)
                })
            })
        val referenceBackend =
            FontTestBackend(open = { _, _ ->
                FontTestFace({
                    referenceGlyphs++
                    raster(1)
                })
            })
        MinecraftFontEngine(snapshot, { candidateBackend }, cacheEntries = 2).use { candidate ->
            MinecraftFontEngine(snapshot, { referenceBackend }, cacheEntries = 2).use { reference ->
                for (scalar in listOf('A'.code, 'B'.code, 'A'.code, 'C'.code, 'B'.code)) {
                    assertEquals(FontTerminalTestSupport.glyph(snapshot, reference, true, scalar), candidate.glyph(FontTestResources.defaultFont, scalar))
                    assertEquals(FontTerminalTestSupport.rasterKeys(reference), FontTerminalTestSupport.rasterKeys(candidate))
                    assertEquals(reference.retainedRasterBytes, candidate.retainedRasterBytes)
                }
            }
        }
        assertEquals(4, candidateGlyphs)
        assertEquals(candidateGlyphs, referenceGlyphs)
        assertEquals(1, candidateBackend.openCalls)
        assertEquals(candidateBackend.openCalls, referenceBackend.openCalls)
        assertEquals(1, candidateBackend.closeCalls)
        assertEquals(candidateBackend.closeCalls, referenceBackend.closeCalls)
    }

    private fun assertForeignThreadCloseRejected(owner: MinecraftFontEngine) {
        val task = FutureTask { runCatching { owner.close() }.exceptionOrNull() }
        Thread(task).start()
        val failure = task.get(5, TimeUnit.SECONDS)
        assertTrue(failure is IllegalStateException)
        assertEquals("Font engine requires its owner thread.", failure?.message)
    }

    private fun failures(): List<Throwable?> = listOf(null, IllegalStateException("terminal callback failure"), MinecraftFontLoadLimitException("terminal callback limit"), AssertionError("terminal callback fatal"))

    private fun trueTypeSnapshot(): MinecraftFontSnapshot =
        FontTestResources.snapshot(
            FontTestResources.font("default", """{"type":"ttf","file":"test:face.ttf"}"""),
            "assets/test/font/face.ttf" to byteArrayOf(1),
        )

    private fun bitmapSnapshot(): MinecraftFontSnapshot =
        FontTestResources.snapshot(
            FontTestResources.font("default", """{"type":"bitmap","file":"test:face.png","ascent":7,"chars":["A"]}"""),
            "assets/test/textures/face.png" to byteArrayOf(1),
        )

    private fun raster(width: Int): MinecraftFontGlyph = MinecraftFontGlyph(width.toFloat(), 0f, 0f, width.toFloat(), 1f, createDrawImage(IntSize(width, 1), IntArray(width) { -1 }))
}
