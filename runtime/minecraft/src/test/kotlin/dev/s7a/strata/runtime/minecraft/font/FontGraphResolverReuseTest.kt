package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies immutable declaration reuse without changing reference membership, filters or expansion accounting.
 */
internal class FontGraphResolverReuseTest {
    @Test
    fun unfilteredAliasesChainsAndDiamondsReuseSnapshotLocalDeclarations() {
        val lower = FontTestResources.source(FontTestResources.font("test:leaf", """{"type":"space","advances":{"B":7}}"""), name = "lower")
        val higher =
            FontTestResources.source(
                FontTestResources.font("test:leaf", """{"type":"space","advances":{"A":3},"filter":{"uniform":true}}"""),
                FontTestResources.font("test:alias", """{"type":"reference","id":"test:leaf"}"""),
                FontTestResources.font("test:chain", """{"type":"reference","id":"test:alias"}"""),
                FontTestResources.font("test:diamond", """{"type":"reference","id":"test:alias"},{"type":"reference","id":"test:chain"}"""),
                name = "higher",
            )
        val snapshot = MinecraftFontSnapshot.load(listOf(lower, higher), FontTestResources.compatibility)
        val leaf = snapshot.fonts.getValue(ResourceId("test", "leaf"))
        assertEquals(listOf("higher", "lower"), leaf.map(FontProviderEntry::source))
        assertEquals(mapOf(FontOption.Uniform to true), leaf.first().filter)
        for (name in listOf("alias", "chain", "diamond")) {
            val entries = snapshot.fonts.getValue(ResourceId("test", name))
            assertNotSame(leaf, entries)
            entries.forEachIndexed { index, entry -> assertSame(leaf[index % leaf.size], entry) }
        }
        val fresh = MinecraftFontSnapshot.load(listOf(lower, higher), FontTestResources.compatibility)
        assertNotSame(leaf.first(), fresh.fonts.getValue(ResourceId("test", "leaf")).first())
        assertTrue(snapshot.diagnostics.isEmpty())
    }

    @Test
    fun filteredSiblingsAndMixedChainsPreservePrecedenceWithoutMutatingTheirTargets() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("test:leaf", """{"type":"space","advances":{"A":3},"filter":{"uniform":true,"jp":false}}"""),
                FontTestResources.font("test:filtered", """{"type":"reference","id":"test:leaf","filter":{"uniform":false}}"""),
                FontTestResources.font("test:mixed", """{"type":"reference","id":"test:filtered"}"""),
                FontTestResources.font("test:sibling", """{"type":"reference","id":"test:leaf","filter":{"jp":true}}"""),
            )
        val leaf = snapshot.fonts.getValue(ResourceId("test", "leaf")).single()
        val filtered = snapshot.fonts.getValue(ResourceId("test", "filtered")).single()
        val sibling = snapshot.fonts.getValue(ResourceId("test", "sibling")).single()
        assertSame(filtered, snapshot.fonts.getValue(ResourceId("test", "mixed")).single())
        assertNotSame(leaf, filtered)
        assertNotSame(leaf, sibling)
        assertEquals(mapOf(FontOption.Uniform to true, FontOption.JapaneseVariants to false), leaf.filter)
        assertEquals(mapOf(FontOption.Uniform to false, FontOption.JapaneseVariants to false), filtered.filter)
        assertEquals(mapOf(FontOption.Uniform to true, FontOption.JapaneseVariants to true), sibling.filter)
        for (entry in listOf(filtered, sibling)) {
            assertEquals(leaf.identity, entry.identity)
            assertSame(leaf.provider, entry.provider)
            assertEquals(leaf.source, entry.source)
        }
    }

    @Test
    fun reusedEntriesStillReserveEveryExpandedMembershipBeforeAdmission() {
        val leaf = ResourceId("test", "leaf")
        val first = ResourceId("test", "first")
        val second = ResourceId("test", "second")
        val declaration = FontProviderEntry(0, FontProvider.Space(mapOf('A'.code to 3f)), emptyMap(), "leaf-pack")
        val declarations =
            linkedMapOf(
                leaf to listOf(declaration),
                first to listOf(FontProviderEntry(1, FontProvider.Reference(leaf), emptyMap(), "first-pack")),
                second to listOf(FontProviderEntry(2, FontProvider.Reference(leaf), emptyMap(), "second-pack")),
            )
        for (maximum in listOf(2, 3)) {
            val diagnostics = ArrayList<MinecraftFontDiagnostic>()
            val budget = FontLoadBudget(MinecraftFontLoadLimits(maxResolvedProviders = maximum))
            val resolved = FontGraphResolver(declarations, diagnostics, budget).resolve()
            assertEquals(if (maximum == 2) setOf(leaf, first) else declarations.keys, resolved.keys)
            assertEquals(0L, budget.remaining(FontLoadBudget.Kind.ResolvedProviders))
            assertSame(declaration, resolved.getValue(first).single())
            if (maximum == 2) {
                assertEquals(second, diagnostics.single().font)
                assertEquals("second-pack", diagnostics.single().source)
                assertEquals(MinecraftFontDiagnostic.Kind.ProviderLoadFailure, diagnostics.single().kind)
            } else {
                assertTrue(diagnostics.isEmpty())
            }
        }
    }
}
