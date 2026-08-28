package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Verifies that resource filters and release-selected overlays reproduce effective pack resources.
 */
internal class MinecraftFontPackStackTest {
    @Test
    fun overlaysReplaceSamePackDocumentsButPreserveLowerPackFallback() {
        val lower = FontTestResources.source(FontTestResources.font("default", """{"type":"space","advances":{"C":3}}"""))
        val base = FontTestResources.font("default", """{"type":"space","advances":{"A":1,"B":2}}""")
        val overlay = FontTestResources.font("default", """{"type":"space","advances":{"A":7}}""")
        val later = FontTestResources.font("default", """{"type":"space","advances":{"A":9}}""")
        val higher =
            FontTestResources.source(
                base,
                "selected/${overlay.first}" to overlay.second,
                "later/${later.first}" to later.second,
                "pack.mcmeta" to
                    """
                    {"overlays":{"entries":[
                        {"directory":"selected","min_format":[84,0],"max_format":[84,2]},
                        {"directory":"later","min_format":[84,2],"max_format":85}
                    ]}}
                    """.trimIndent().toByteArray(),
            )
        val first = MinecraftFontSnapshot.load(listOf(lower, higher), FontTestResources.compatibility.copy(packFormatMinor = 1))
        withEngine(first) { engine ->
            assertEquals(7.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
            assertEquals(6.0f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
            assertEquals(3.0f, engine.glyph(FontTestResources.defaultFont, 'C'.code).advance)
        }
        val second = MinecraftFontSnapshot.load(listOf(lower, higher), FontTestResources.compatibility.copy(packFormatMinor = 2))
        withEngine(second) { engine -> assertEquals(9.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance) }
    }

    @Test
    fun legacyOverlayRangesAndUnsupportedOverlayCapabilitiesAreDistinct() {
        val base = FontTestResources.font("default", """{"type":"space","advances":{"A":1}}""")
        val overlay = FontTestResources.font("default", """{"type":"space","advances":{"A":7}}""")
        val source =
            FontTestResources.source(
                base,
                "legacy/${overlay.first}" to overlay.second,
                "pack.mcmeta" to
                    """{"overlays":{"entries":[{"directory":"legacy","formats":{"min_inclusive":12,"max_inclusive":18}}]}}""".toByteArray(),
            )
        val legacy = FontTestResources.compatibility.copy(packFormat = 18, minorPackFormats = false)
        withEngine(MinecraftFontSnapshot.load(listOf(source), legacy)) { engine ->
            assertEquals(7.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
        }
        withEngine(MinecraftFontSnapshot.load(listOf(source), legacy.copy(packOverlays = false))) { engine ->
            assertEquals(1.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
        }
    }

    @Test
    fun packFiltersRemoveLowerResourcesBeforeAddingTheirOwnReplacements() {
        val lower =
            FontTestResources.source(
                FontTestResources.font("default", """{"type":"space","advances":{"A":1,"B":2}}"""),
                FontTestResources.font("test:unblocked", """{"type":"space","advances":{"A":4}}"""),
            )
        val higher =
            FontTestResources.source(
                "pack.mcmeta" to """{"filter":{"block":[{"namespace":"minecraft","path":"font/default\\.json"}]}}""".toByteArray(),
                FontTestResources.font("default", """{"type":"space","advances":{"A":9}}"""),
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(lower, higher), FontTestResources.compatibility)
        withEngine(snapshot) { engine ->
            assertEquals(9.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
            assertEquals(6.0f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
            assertTrue(engine.diagnostics.isEmpty())
        }
        assertEquals(2, snapshot.fontIds.size)
    }

    @Test
    fun filterNamespacesAndPathsSearchIndependentlyAcrossAllBlockEntries() {
        val lower =
            FontTestResources.source(
                FontTestResources.font("default", """{"type":"space","advances":{"A":1}}"""),
                FontTestResources.font("test:alpha", """{"type":"space","advances":{"A":2}}"""),
                FontTestResources.font("other:default", """{"type":"space","advances":{"A":3}}"""),
            )
        val higher =
            FontTestResources.source(
                "pack.mcmeta" to
                    """
                    {"filter":{"block":[
                        {"namespace":"craft","path":"alpha"},
                        {"namespace":"es","path":"default"}
                    ]}}
                    """.trimIndent().toByteArray(),
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(lower, higher), FontTestResources.compatibility)
        assertEquals(setOf(ResourceId("other", "default")), snapshot.fontIds)
        assertTrue(snapshot.diagnostics.isEmpty())
    }

    @Test
    fun malformedFiltersAreIgnoredWithoutDiscardingTheSourceOrLowerProviders() {
        val lower = FontTestResources.source(FontTestResources.font("default", """{"type":"space","advances":{"B":2}}"""))
        val higher =
            FontTestResources.source(
                FontTestResources.font("default", """{"type":"space","advances":{"A":9}}"""),
                "pack.mcmeta" to """{"filter":{"block":[{"namespace":"["}]}}""".toByteArray(),
                name = "invalid-filter",
            )
        for (reject in listOf(false, true)) {
            val snapshot = MinecraftFontSnapshot.load(listOf(lower, higher), FontTestResources.compatibility.copy(rejectMalformedOverlayMetadata = reject))
            assertEquals(MinecraftFontDiagnostic.Kind.PackMetadataFailure, snapshot.diagnostics.single().kind)
            assertNull(snapshot.diagnostics.single().font)
            assertEquals("invalid-filter", snapshot.diagnostics.single().source)
            withEngine(snapshot) { engine ->
                assertEquals(9.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                assertEquals(2.0f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
            }
        }
    }

    @Test
    fun invalidOrUnreadablePackMetadataExcludesOnlyItsSelectedSource() {
        val lower = FontTestResources.source(FontTestResources.font("default", """{"type":"space","advances":{"A":1}}"""))
        val replacement = FontTestResources.font("default", """{"type":"space","advances":{"A":9}}""")
        val malformed = FontTestResources.source(replacement, "pack.mcmeta" to "{".toByteArray(), name = "invalid-metadata")
        val unreadable = FontTestResources.failingRead(malformed, "pack.mcmeta", IOException("metadata read"))
        val last = FontTestResources.source(FontTestResources.font("default", """{"type":"space","advances":{"B":2}}"""))
        for (broken in listOf(malformed, unreadable)) {
            val snapshot = MinecraftFontSnapshot.load(listOf(lower, broken, last), FontTestResources.compatibility)
            assertEquals(MinecraftFontDiagnostic.Kind.PackMetadataFailure, snapshot.diagnostics.single().kind)
            assertNull(snapshot.diagnostics.single().font)
            withEngine(snapshot) { engine ->
                assertEquals(1.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                assertEquals(2.0f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
            }
        }
    }

    @Test
    fun malformedOverlayMetadataUsesTheSelectedRejectionPolicyBeforeApplyingFilters() {
        val lower = FontTestResources.source(FontTestResources.font("default", """{"type":"space","advances":{"A":1,"B":2}}"""))
        for (overlays in listOf("""{"entries":[{"directory":"invalid/path","formats":84}]}""", "{}")) {
            val higher =
                FontTestResources.source(
                    FontTestResources.font("default", """{"type":"space","advances":{"A":9}}"""),
                    "pack.mcmeta" to
                        """
                        {"filter":{"block":[{"namespace":"minecraft","path":"font/default\\.json"}]},
                         "overlays":$overlays}
                        """.trimIndent().toByteArray(),
                )
            for (reject in listOf(false, true)) {
                val snapshot = MinecraftFontSnapshot.load(listOf(lower, higher), FontTestResources.compatibility.copy(rejectMalformedOverlayMetadata = reject))
                assertEquals(MinecraftFontDiagnostic.Kind.PackMetadataFailure, snapshot.diagnostics.single().kind)
                withEngine(snapshot) { engine ->
                    assertEquals(if (reject) 1.0f else 9.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                    assertEquals(if (reject) 2.0f else 6.0f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
                }
            }
            val unsupported = MinecraftFontSnapshot.load(listOf(higher), FontTestResources.compatibility.copy(packOverlays = false))
            assertTrue(unsupported.diagnostics.isEmpty())
            withEngine(unsupported) { engine -> assertEquals(9.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance) }
        }
    }

    @Test
    fun unlistedAssetsUseActiveOverlayOrderAndLowerPackFallback() {
        val asset = ResourceId("test", "outside/glyphs.zip")
        val fallback = ResourceId("test", "outside/fallback.zip")
        val lower =
            unlistedAssets(
                FontTestResources.source(
                    "assets/test/outside/glyphs.zip" to byteArrayOf(1),
                    "assets/test/outside/fallback.zip" to byteArrayOf(2),
                ),
            )
        val higher =
            unlistedAssets(
                FontTestResources.source(
                    "assets/test/outside/glyphs.zip" to byteArrayOf(3),
                    "early/assets/test/outside/glyphs.zip" to byteArrayOf(4),
                    "late/assets/test/outside/glyphs.zip" to byteArrayOf(5),
                    "inactive/assets/test/outside/glyphs.zip" to byteArrayOf(6),
                    "pack.mcmeta" to
                        """
                        {"overlays":{"entries":[
                            {"directory":"early","formats":84},
                            {"directory":"late","formats":84},
                            {"directory":"inactive","formats":85}
                        ]}}
                        """.trimIndent().toByteArray(),
                ),
            )
        val diagnostics = ArrayList<MinecraftFontDiagnostic>()
        val budget = FontLoadBudget(MinecraftFontLoadLimits())
        val resources = FontResourceStack(listOf(lower, higher), FontTestResources.compatibility, diagnostics, budget)
        assertEquals((lower.paths().size + higher.paths().size).toLong(), budget.limits.maxEntries - budget.remaining(FontLoadBudget.Kind.SourceEntries))
        assertArrayEquals(byteArrayOf(5), resources.selected(asset)?.copyBytes())
        assertSame(resources.selected(asset), resources.selected(asset))
        assertArrayEquals(byteArrayOf(2), resources.selected(fallback)?.copyBytes())
        val withoutOverlays = FontResourceStack(listOf(lower, higher), FontTestResources.compatibility.copy(packOverlays = false), diagnostics, FontLoadBudget(MinecraftFontLoadLimits()))
        assertArrayEquals(byteArrayOf(3), withoutOverlays.selected(asset)?.copyBytes())
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun unlistedAssetFiltersAndRejectedSourcesRetainNativeSelectionBoundaries() {
        val asset = ResourceId("test", "outside/glyphs.zip")
        val path = "assets/test/outside/glyphs.zip"
        val lower = unlistedAssets(FontTestResources.source(path to byteArrayOf(1)))
        val filter = "pack.mcmeta" to """{"filter":{"block":[{"namespace":"es","path":"outside"}]}}""".toByteArray()
        val blocked = unlistedAssets(FontTestResources.source(filter))
        val replacement = unlistedAssets(FontTestResources.source(filter, path to byteArrayOf(2)))
        val diagnostics = ArrayList<MinecraftFontDiagnostic>()
        assertNull(FontResourceStack(listOf(lower, blocked), FontTestResources.compatibility, diagnostics, FontLoadBudget(MinecraftFontLoadLimits())).selected(asset))
        assertArrayEquals(
            byteArrayOf(2),
            FontResourceStack(listOf(lower, replacement), FontTestResources.compatibility, diagnostics, FontLoadBudget(MinecraftFontLoadLimits())).selected(asset)?.copyBytes(),
        )
        for (metadata in listOf("{", """{"overlays":{}}""")) {
            val rejected = unlistedAssets(FontTestResources.source("pack.mcmeta" to metadata.toByteArray(), path to byteArrayOf(3)))
            val resources = FontResourceStack(listOf(lower, rejected), FontTestResources.compatibility, diagnostics, FontLoadBudget(MinecraftFontLoadLimits()))
            assertArrayEquals(byteArrayOf(1), resources.selected(asset)?.copyBytes())
        }
        assertEquals(listOf(MinecraftFontDiagnostic.Kind.PackMetadataFailure, MinecraftFontDiagnostic.Kind.PackMetadataFailure), diagnostics.map { it.kind })
    }

    @Test
    fun missingListedAssetsAndFailedUnlistedReadsDoNotFallThroughToLowerPacks() {
        val asset = ResourceId("test", "outside/glyphs.zip")
        val path = "assets/test/outside/glyphs.zip"
        val lower = FontTestResources.source(path to byteArrayOf(1))
        val higher = FontTestResources.source(path to byteArrayOf(2))
        val disappeared =
            object : MinecraftFontAssetSource by higher {
                override fun read(path: String): ByteArray? = null
            }
        val failure = IOException("selected resource read")
        val unreadable = FontTestResources.failingRead(unlistedAssets(higher), path, failure)
        val diagnostics = ArrayList<MinecraftFontDiagnostic>()
        assertThrows(IllegalArgumentException::class.java) {
            FontResourceStack(listOf(lower, disappeared), FontTestResources.compatibility, diagnostics, FontLoadBudget(MinecraftFontLoadLimits())).selected(asset)
        }
        assertSame(
            failure,
            assertThrows(IOException::class.java) {
                FontResourceStack(listOf(lower, unreadable), FontTestResources.compatibility, diagnostics, FontLoadBudget(MinecraftFontLoadLimits())).selected(asset)
            },
        )
        assertTrue(diagnostics.isEmpty())
        val definitions = FontTestResources.source(FontTestResources.font("default", """{"type":"unihex","hex_file":"test:outside/glyphs.zip"}"""))
        for (failed in listOf(disappeared, unreadable)) {
            val snapshot = MinecraftFontSnapshot.load(listOf(definitions, lower, failed), FontTestResources.compatibility)
            assertTrue(snapshot.fontIds.isEmpty())
            assertEquals(MinecraftFontDiagnostic.Kind.ProviderLoadFailure, snapshot.diagnostics.single().kind)
        }
    }

    private fun unlistedAssets(source: MinecraftFontAssetSource): MinecraftFontAssetSource =
        object : MinecraftFontAssetSource by source {
            override fun paths(): Set<String> = emptySet()
        }

    private fun withEngine(
        snapshot: MinecraftFontSnapshot,
        action: (MinecraftFontEngine) -> Unit,
    ) {
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { FontTestBackend() }).use(action)
    }
}
