package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Verifies native provider precedence, reference failures, and immutable font selection without game dependencies.
 */
internal class MinecraftFontSnapshotTest {
    @Test
    fun higherPacksAndEarlierProvidersWinWhileLowerPacksSupplyMissingScalars() {
        val lower =
            FontTestResources.source(
                FontTestResources.font("default", """{"type":"space","advances":{"A":1,"C":3}}"""),
                FontTestResources.font("test:shared", """{"type":"space","advances":{"A":2,"B":5}}"""),
                name = "lower",
            )
        val higher =
            FontTestResources.source(
                FontTestResources.font(
                    "default",
                    """
                    {"type":"space","advances":{"A":7}},
                    {"type":"reference","id":"test:shared"},
                    {"type":"space","advances":{"B":9}}
                    """.trimIndent(),
                ),
                name = "higher",
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(lower, higher), FontTestResources.compatibility)
        withEngine(snapshot) { engine ->
            assertEquals(7.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
            assertEquals(5.0f, engine.glyph(FontTestResources.defaultFont, 'B'.code).advance)
            assertEquals(3.0f, engine.glyph(FontTestResources.defaultFont, 'C'.code).advance)
            assertTrue(engine.diagnostics.isEmpty())
        }
    }

    @Test
    fun outerReferenceFiltersOverrideInnerConditionsAtTheirOriginalPosition() {
        val definitions =
            arrayOf(
                FontTestResources.font(
                    "test:shared",
                    """{"type":"space","advances":{"日":4},"filter":{"uniform":true,"jp":false}}""",
                ),
                FontTestResources.font(
                    "default",
                    """
                    {"type":"reference","id":"test:shared","filter":{"uniform":false}},
                    {"type":"space","advances":{"日":12}}
                    """.trimIndent(),
                ),
            )
        withEngine(FontTestResources.snapshot(*definitions)) { engine ->
            assertEquals(4.0f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
        }
        withEngine(FontTestResources.snapshot(*definitions, options = MinecraftFontOptions(uniform = true))) { engine ->
            assertEquals(12.0f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
        }
        withEngine(FontTestResources.snapshot(*definitions, options = MinecraftFontOptions(japaneseVariants = true))) { engine ->
            assertEquals(12.0f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
        }
    }

    @Test
    fun legacyForceUnicodeRemapsOnlyTheDefaultFont() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"space","advances":{"A":2}}"""),
                FontTestResources.font("uniform", """{"type":"space","advances":{"A":8}}"""),
                FontTestResources.font("test:custom", """{"type":"space","advances":{"A":3},"filter":{"uniform":false}}"""),
                options = MinecraftFontOptions(uniform = true),
                capabilities = FontTestResources.compatibility.copy(providerFilters = false),
            )
        withEngine(snapshot) { engine ->
            assertEquals(8.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
            assertEquals(3.0f, engine.glyph(ResourceId("test", "custom"), 'A'.code).advance)
        }
    }

    @Test
    fun malformedHigherDocumentsAreSkippedWithoutRemovingLowerProviders() {
        val lower = FontTestResources.source(FontTestResources.font("default", """{"type":"space","advances":{"한":7}}"""))
        val higher =
            FontTestResources.source(
                FontTestResources.font(
                    "default",
                    """{"type":"space","advances":{"한":1}},{"type":"unsupported"}""",
                ),
                name = "broken",
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(lower, higher), FontTestResources.compatibility)
        assertEquals(MinecraftFontDiagnostic.Kind.MalformedDocument, snapshot.diagnostics.single().kind)
        assertEquals("broken", snapshot.diagnostics.single().source)
        withEngine(snapshot) { engine -> assertEquals(7.0f, engine.glyph(FontTestResources.defaultFont, '한'.code).advance) }
    }

    @Test
    fun unsupportedDerivedFontIdentifiersAreDiagnosedWithoutReadingOrDiscardingValidSiblings() {
        val invalidPaths =
            listOf(
                "assets/test/font/.json",
                "assets/test/font/..json",
                "assets/test/font/...json",
                "assets/test/font/nested/.json",
            )
        val lower = FontTestResources.source(FontTestResources.font("default", """{"type":"space","advances":{"日":7,"한":8}}"""))
        var higher: MinecraftFontAssetSource =
            FontTestResources.source(
                *invalidPaths.map { path -> path to "{}".toByteArray() }.toTypedArray(),
                FontTestResources.font("default", """{"type":"space","advances":{"日":1}}"""),
                FontTestResources.font("test:other", """{"type":"space","advances":{"日":3}}"""),
                name = "unsupported-identifiers",
            )
        for (path in invalidPaths) {
            higher = FontTestResources.failingRead(higher, path, AssertionError("Unsupported font identifiers must not be read."))
        }
        val snapshot = MinecraftFontSnapshot.load(listOf(lower, higher), FontTestResources.compatibility)
        assertEquals(setOf(FontTestResources.defaultFont, ResourceId("test", "other")), snapshot.fontIds)
        assertEquals(invalidPaths.size, snapshot.diagnostics.size)
        invalidPaths.sorted().zip(snapshot.diagnostics).forEach { (path, diagnostic) ->
            assertEquals(MinecraftFontDiagnostic.Kind.MalformedDocument, diagnostic.kind)
            assertNull(diagnostic.font)
            assertEquals("unsupported-identifiers", diagnostic.source)
            assertTrue(diagnostic.message.contains(path))
        }
        withEngine(snapshot) { engine ->
            assertEquals(1.0f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
            assertEquals(8.0f, engine.glyph(FontTestResources.defaultFont, '한'.code).advance)
            assertEquals(3.0f, engine.glyph(ResourceId("test", "other"), '日'.code).advance)
        }
    }

    @Test
    fun unreadableFontDocumentsAreSkippedWithoutDiscardingOtherPackFonts() {
        val lower = FontTestResources.source(FontTestResources.font("default", """{"type":"space","advances":{"日":7}}"""))
        val replacement = FontTestResources.font("default", """{"type":"space","advances":{"日":1}}""")
        val higher =
            FontTestResources.source(
                replacement,
                FontTestResources.font("test:other", """{"type":"space","advances":{"日":3}}"""),
                name = "unreadable",
            )
        val failed = FontTestResources.failingRead(higher, replacement.first, IOException("font JSON read"))
        val snapshot = MinecraftFontSnapshot.load(listOf(lower, failed), FontTestResources.compatibility)
        val diagnostic = snapshot.diagnostics.single()
        assertEquals(MinecraftFontDiagnostic.Kind.MalformedDocument, diagnostic.kind)
        assertEquals(FontTestResources.defaultFont, diagnostic.font)
        assertEquals("unreadable", diagnostic.source)
        withEngine(snapshot) { engine ->
            assertEquals(7.0f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
            assertEquals(3.0f, engine.glyph(ResourceId("test", "other"), '日'.code).advance)
        }
    }

    @Test
    fun fatalFontAndMetadataReadFailuresRemainFatal() {
        val document = FontTestResources.font("default", """{"type":"space","advances":{"日":1}}""")
        val source =
            FontTestResources.source(
                document,
                "pack.mcmeta" to "{}".toByteArray(),
                "assets/test/font/.json" to "{}".toByteArray(),
            )
        for (path in listOf(document.first, "pack.mcmeta")) {
            val expected = LinkageError("fatal read")
            val failed = FontTestResources.failingRead(source, path, expected)
            val actual = assertThrows(LinkageError::class.java) { MinecraftFontSnapshot.load(listOf(failed), FontTestResources.compatibility) }
            assertSame(expected, actual)
        }
    }

    @Test
    fun missingReferencesAndCyclesInvalidateTheEntireDependentBundle() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"space","advances":{"A":1}},{"type":"reference","id":"test:absent"}"""),
                FontTestResources.font("test:cycle_a", """{"type":"reference","id":"test:cycle_b"}"""),
                FontTestResources.font("test:cycle_b", """{"type":"reference","id":"test:cycle_a"}"""),
                FontTestResources.font("test:valid", """{"type":"space","advances":{"A":3}}"""),
            )
        assertEquals(setOf(ResourceId("test", "valid")), snapshot.fontIds)
        assertTrue(snapshot.diagnostics.any { diagnostic -> diagnostic.kind === MinecraftFontDiagnostic.Kind.MissingReference })
        assertTrue(snapshot.diagnostics.any { diagnostic -> diagnostic.kind === MinecraftFontDiagnostic.Kind.CyclicReference })
        withEngine(snapshot) { engine ->
            assertEquals(6.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
            assertEquals(3.0f, engine.glyph(ResourceId("test", "valid"), 'A'.code).advance)
        }
    }

    @Test
    fun missingDisabledProviderStillInvalidatesTheBundleBeforeSelection() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "default",
                    """
                    {"type":"space","advances":{"A":1}},
                    {"type":"ttf","file":"test:absent.ttf","filter":{"uniform":true}}
                    """.trimIndent(),
                ),
            )
        assertFalse(FontTestResources.defaultFont in snapshot.fontIds)
        assertEquals(MinecraftFontDiagnostic.Kind.ProviderLoadFailure, snapshot.diagnostics.single().kind)
    }

    @Test
    fun supplementaryScalarsAndFractionalSpaceAdvancesRemainIntact() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"space","advances":{"😀":3.5,"한":8,"日":-1.25}}"""),
            )
        withEngine(snapshot) { engine ->
            assertEquals(3.5f, engine.glyph(FontTestResources.defaultFont, 0x1F600).advance)
            assertEquals(8.0f, engine.glyph(FontTestResources.defaultFont, '한'.code).advance)
            assertEquals(-1.25f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
        }
    }

    @Test
    fun emptyExternalNamespacesUseTheNativeDefaultNamespace() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":":reference","id":":shared"}"""),
                FontTestResources.font("shared", """{"type":":space","advances":{"日":9}}"""),
            )
        withEngine(snapshot) { engine ->
            assertEquals(9.0f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
            assertTrue(engine.diagnostics.isEmpty())
        }
    }

    @Test
    fun equalAndReversedUnihexOverrideEndpointsRejectTheCompleteDocument() {
        for (endpoint in listOf("日", "\u65E4")) {
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font(
                        "default",
                        """
                        {"type":"space","advances":{"A":1}},
                        {"type":"unihex","hex_file":"test:font/custom.zip","size_overrides":[
                            {"from":"日","to":"$endpoint","left":0,"right":7}
                        ]}
                        """.trimIndent(),
                    ),
                )
            assertEquals(MinecraftFontDiagnostic.Kind.MalformedDocument, snapshot.diagnostics.single().kind)
            withEngine(snapshot) { engine -> assertEquals(6.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance) }
        }
    }

    @Test
    fun nonNativeHexDigitsAndLineSeparatorsInvalidateTheCompleteUnihexBundle() {
        val valid = "0041:${"FF".repeat(16)}"
        val records =
            listOf(
                "004a:${"FF".repeat(16)}",
                "0041:${"ff".repeat(16)}",
                "+041:${"FF".repeat(16)}",
                "0041:${"+F".repeat(16)}",
                "$valid\r\n",
                "\n$valid\n",
                "$valid\n\n",
            )
        for (record in records) {
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font(
                        "default",
                        """{"type":"space","advances":{"A":1}},{"type":"unihex","hex_file":"test:font/invalid.zip"}""",
                    ),
                    FontTestResources.font("test:other", """{"type":"space","advances":{"A":3}}"""),
                    "assets/test/font/invalid.zip" to FontTestResources.archive("invalid.hex" to record.toByteArray()),
                )
            assertEquals(setOf(ResourceId("test", "other")), snapshot.fontIds)
            assertEquals(MinecraftFontDiagnostic.Kind.ProviderLoadFailure, snapshot.diagnostics.single().kind)
            withEngine(snapshot) { engine ->
                assertEquals(6.0f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance)
                assertEquals(3.0f, engine.glyph(ResourceId("test", "other"), 'A'.code).advance)
            }
        }
    }

    @Test
    fun referenceDepthIsIndependentOfMemoizationAndDoesNotPoisonStandaloneTargets() {
        val root = ResourceId("test", "root")
        val middle = ResourceId("test", "middle")
        val leaf = ResourceId("test", "leaf")
        val other = ResourceId("test", "other")
        val declarations =
            linkedMapOf(
                root to listOf(FontProviderEntry(0, FontProvider.Reference(middle), emptyMap(), "test")),
                middle to listOf(FontProviderEntry(1, FontProvider.Reference(leaf), emptyMap(), "test")),
                leaf to listOf(FontProviderEntry(2, FontProvider.Space(mapOf('A'.code to 3f)), emptyMap(), "test")),
                other to listOf(FontProviderEntry(3, FontProvider.Space(mapOf('A'.code to 7f)), emptyMap(), "test")),
            )
        for (entries in listOf(declarations.entries.toList(), declarations.entries.reversed())) {
            val ordered = entries.associateTo(LinkedHashMap()) { entry -> entry.key to entry.value }
            val diagnostics = ArrayList<MinecraftFontDiagnostic>()
            val bounded = FontGraphResolver(ordered, diagnostics, FontLoadBudget(MinecraftFontLoadLimits(maxReferenceDepth = 2))).resolve()
            assertEquals(setOf(middle, leaf, other), bounded.keys)
            assertEquals(root, diagnostics.single().font)
            assertEquals(MinecraftFontDiagnostic.Kind.ProviderLoadFailure, diagnostics.single().kind)
            val accepted = FontGraphResolver(ordered, ArrayList(), FontLoadBudget(MinecraftFontLoadLimits(maxReferenceDepth = 3))).resolve()
            assertEquals(declarations.keys, accepted.keys)
            assertEquals(accepted.getValue(leaf), accepted.getValue(root))
        }
    }

    @Test
    fun rejectedProviderAndGlyphArraysCannotResetAggregateCapacityForLaterDocuments() {
        val source =
            FontTestResources.source(
                FontTestResources.font("test:a", """{"type":"space","advances":{"A":3}}"""),
                FontTestResources.font("test:b", """{"type":"space","advances":{"A":4,"B":5}},{"type":"space","advances":{"C":6}}"""),
                FontTestResources.font("test:c", """{"type":"space","advances":{"A":7}}"""),
            )
        for (limits in listOf(MinecraftFontLoadLimits(maxProviders = 2), MinecraftFontLoadLimits(maxGlyphs = 2))) {
            val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility, MinecraftFontOptions(), limits)
            assertEquals(listOf(ResourceId("test", "b"), ResourceId("test", "c")), snapshot.diagnostics.map(MinecraftFontDiagnostic::font))
            withEngine(snapshot) { engine ->
                assertEquals(3f, engine.glyph(ResourceId("test", "a"), 'A'.code).advance)
                assertEquals(6f, engine.glyph(ResourceId("test", "b"), 'A'.code).advance)
                assertEquals(6f, engine.glyph(ResourceId("test", "c"), 'A'.code).advance)
            }
        }
    }

    @Test
    fun metadataRejectedSourcesStillConsumeTheirEnumeratedEntryCapacity() {
        val invalid = FontTestResources.source("pack.mcmeta" to "{".toByteArray(), "ignored" to byteArrayOf(), name = "invalid")
        val valid = FontTestResources.source(FontTestResources.font("default", """{"type":"space","advances":{"A":3}}"""))
        val limits = MinecraftFontLoadLimits(maxSourceEntries = 2, maxEntries = 3)
        val exact = MinecraftFontSnapshot.load(listOf(invalid, valid), FontTestResources.compatibility, MinecraftFontOptions(), limits)
        assertEquals(1, exact.diagnostics.size)
        withEngine(exact) { engine -> assertEquals(3f, engine.glyph(FontTestResources.defaultFont, 'A'.code).advance) }
        val over = MinecraftFontSnapshot.load(listOf(invalid, invalid, valid), FontTestResources.compatibility, MinecraftFontOptions(), limits)
        assertEquals(3, over.diagnostics.size)
        assertTrue(over.fontIds.isEmpty())
    }

    @Test
    fun fontDocumentAndResolvedProviderLimitsPreserveAlreadyAcceptedIndependentBundles() {
        val sources = listOf("a", "b", "c").map { name -> FontTestResources.source(FontTestResources.font("test:$name", """{"type":"space","advances":{"A":3}}"""), name = name) }
        val limits = MinecraftFontLoadLimits(maxFontDocuments = 2)
        val exact = MinecraftFontSnapshot.load(sources.take(2), FontTestResources.compatibility, MinecraftFontOptions(), limits)
        assertTrue(exact.diagnostics.isEmpty())
        val over = MinecraftFontSnapshot.load(sources, FontTestResources.compatibility, MinecraftFontOptions(), limits)
        assertEquals(exact.fontIds, over.fontIds)
        assertEquals("c", over.diagnostics.single().source)
        val expanded = MinecraftFontSnapshot.load(sources, FontTestResources.compatibility, MinecraftFontOptions(), limits.copy(maxFontDocuments = 3, maxResolvedProviders = 2))
        assertEquals(exact.fontIds, expanded.fontIds)
        assertEquals(ResourceId("test", "c"), expanded.diagnostics.single().font)
    }

    private fun withEngine(
        snapshot: MinecraftFontSnapshot,
        action: (MinecraftFontEngine) -> Unit,
    ) {
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { FontTestBackend() }).use(action)
    }
}
