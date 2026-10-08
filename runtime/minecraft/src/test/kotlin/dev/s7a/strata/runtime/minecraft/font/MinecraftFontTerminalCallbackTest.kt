package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies cleanup failures raised inside loading callbacks and detached shaping returns.
 * Loading keeps its original Exception diagnostics and fatal Throwable identity after the owner becomes terminal.
 */
internal class MinecraftFontTerminalCallbackTest {
    @Test
    fun cleanupFailureInsideDecodeOrOpenKeepsTheOriginalPreflightFailureContract() {
        for (entries in listOf(0, 2, 4_096)) {
            for (bitmap in listOf(false, true)) {
                for (failure in listOf(IllegalStateException("close during loading"), MinecraftFontLoadLimitException("close limit during loading"), AssertionError("fatal close during loading"))) {
                    for (original in listOf(false, true)) verifyLoadingCleanup(entries, bitmap, failure, original)
                }
            }
        }
    }

    private fun verifyLoadingCleanup(
        entries: Int,
        bitmap: Boolean,
        failure: Throwable,
        original: Boolean,
    ) {
        val provider = if (bitmap) """{"type":"bitmap","file":"test:face.png","ascent":7,"chars":["A"]}""" else """{"type":"ttf","file":"test:face.ttf"}"""
        val path = if (bitmap) "assets/test/textures/face.png" else "assets/test/font/face.ttf"
        val snapshot = FontTestResources.snapshot(FontTestResources.font("default", provider), path to byteArrayOf(1))
        lateinit var owner: MinecraftFontEngine
        val backend =
            FontTestBackend(
                decode = {
                    owner.close()
                    error("The original cleanup failure must escape this callback.")
                },
                open = { _, _ ->
                    owner.close()
                    error("The original cleanup failure must escape this callback.")
                },
                release = {
                    owner.close()
                    throw failure
                },
            )
        owner = MinecraftFontEngine(snapshot, { backend }, cacheEntries = entries)
        val missing = owner.glyph(ResourceId("unknown", "terminal"), 'A'.code)
        val result = runCatching { FontTerminalTestSupport.glyph(snapshot, owner, original) }
        if (failure is Exception) {
            assertEquals(missing, result.getOrThrow())
            assertEquals(failure.message, owner.diagnostics.single().message)
            assertEquals(MinecraftFontDiagnostic.Kind.ProviderLoadFailure, owner.diagnostics.single().kind)
        } else {
            assertSame(failure, result.exceptionOrNull())
            assertTrue(owner.diagnostics.isEmpty())
        }
        assertEquals(if (bitmap) 1 else 0, backend.decodeCalls)
        assertEquals(if (bitmap) 0 else 1, backend.openCalls)
        assertEquals(1, backend.closeCalls)
        FontTerminalTestSupport.assertTerminal(owner)
    }

    @Test
    fun orderingAndShapingCallbacksReturnDetachedValuesWithoutTerminalOwnerState() {
        val snapshot = FontTestResources.snapshot(FontTestResources.font("default", """{"type":"space","advances":{"A":3}}"""))
        for (shaping in listOf(false, true)) {
            lateinit var owner: MinecraftFontEngine
            val backend = FontTestBackend(release = { owner.close() })
            val order = "A😀"
            val visual = listOf(MinecraftVisualGlyph('A'.code, 0), MinecraftVisualGlyph(0x1F600, 1))
            val delegated =
                object : MinecraftFontBackend by backend {
                    override fun visualOrder(
                        text: String,
                        rightToLeft: Boolean,
                    ): String {
                        owner.close()
                        return order
                    }

                    override fun visualGlyphs(
                        text: String,
                        rightToLeft: Boolean,
                    ): List<MinecraftVisualGlyph> {
                        owner.close()
                        return visual
                    }
                }
            owner = MinecraftFontEngine(snapshot, { delegated })
            if (shaping) assertEquals(visual, owner.visualGlyphs(order)) else assertEquals(order, owner.visualOrder(order))
            assertEquals(1, backend.closeCalls)
            assertEquals(0, backend.decodeCalls)
            assertEquals(0, backend.openCalls)
            FontTerminalTestSupport.assertTerminal(owner)
        }
    }

    @Test
    fun lateFaceReleaseStillRunsAfterTheCallbackObservedABackendCleanupFailure() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:face.ttf"}"""),
                "assets/test/font/face.ttf" to byteArrayOf(1),
            )
        for (original in listOf(false, true)) {
            for (lateFailure in listOf(null, IllegalStateException("late face close"), MinecraftFontLoadLimitException("late face close limit"), AssertionError("late face close fatal"))) {
                lateinit var owner: MinecraftFontEngine
                val backendFailure = IllegalStateException("observed backend close")
                var active = 0
                var closes = 0
                val backend =
                    FontTestBackend(
                        open = { _, _ ->
                            active++
                            val face =
                                FontTestFace(
                                    { error("A terminal preflight must perform no glyph work.") },
                                    {
                                        active--
                                        closes++
                                        owner.close()
                                        if (lateFailure != null) throw lateFailure
                                    },
                                )
                            assertSame(backendFailure, runCatching { owner.close() }.exceptionOrNull())
                            face
                        },
                        release = { throw backendFailure },
                    )
                owner = MinecraftFontEngine(snapshot, { backend }, cacheEntries = 2)
                val missing = owner.glyph(ResourceId("unknown", "terminal"), 'A'.code)
                val result = runCatching { FontTerminalTestSupport.glyph(snapshot, owner, original) }
                if (lateFailure == null) assertEquals(missing, result.getOrThrow()) else assertSame(lateFailure, result.exceptionOrNull())
                assertTrue(backendFailure.suppressed.isEmpty())
                assertEquals(0, active)
                assertEquals(1, closes)
                assertEquals(1, backend.openCalls)
                assertEquals(1, backend.closeCalls)
                FontTerminalTestSupport.assertTerminal(owner)
            }
        }
    }
}
