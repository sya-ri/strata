package dev.s7a.strata.runtime.minecraft.font

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random as KotlinRandom

/**
 * Independent ordered selection, public glyph/failure parity and finite engine ownership for Unihex indexes.
 * The reference renderer never calls the sweep builder or its lookup and computes row pixels independently.
 */
internal class MinecraftFontUnihexIndexTest {
    @Test
    fun sweepMatchesOriginalOrderForUnsortedNestedIdenticalAndAdjacentRanges() {
        val ranges =
            listOf(
                FontProvider.WidthOverride(80, 100, -1, 8),
                FontProvider.WidthOverride(20, 40, 0, 7),
                FontProvider.WidthOverride(30, 90, 1, 6),
                FontProvider.WidthOverride(80, 100, Int.MIN_VALUE, Int.MAX_VALUE),
                FontProvider.WidthOverride(10, 120, 2, 5),
                FontProvider.WidthOverride(121, 125, 0, 3),
                FontProvider.WidthOverride(0x1F600, 0x1F605, -2, 33),
                FontProvider.WidthOverride(0x10FFFD, 0x10FFFF, 0, 7),
            )
        val index = FontUnihexWidthIndex.build(ranges)
        for (scalar in (0..130) + (0x1F5FF..0x1F606) + (0x10FFFC..0x10FFFF)) {
            assertSame(originalWinner(ranges, scalar), index.lookup(scalar))
        }
        assertTrue(index.segmentCount <= ranges.size * 2)
        assertEquals(ranges.size * 16, index.bytes)
        assertNull(FontUnihexWidthIndex.build(emptyList()).lookup(65))
    }

    @Test
    fun independentlyGeneratedOrdersMatchAtEveryEndpointAndGap() {
        val random = Random(225)
        repeat(24) {
            val ranges = List(128) { FontProvider.WidthOverride(random.nextInt(512), 512 + random.nextInt(512), random.nextInt(), random.nextInt()) }
            val shuffled = ranges.shuffled(KotlinRandom(it))
            val index = FontUnihexWidthIndex.build(shuffled)
            for (scalar in 0..1024) assertSame(originalWinner(shuffled, scalar), index.lookup(scalar))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FontUnihexWidthIndex.build(List(8_193) { FontProvider.WidthOverride(65, 66, 0, 7) })
        }
    }

    @Test
    fun publicUncachedAndEvictedGlyphsKeepIndependentPixelsAndBothAdvanceContracts() {
        val ranges = fillers(16) + listOf(FontProvider.WidthOverride(65, 70, -1, 8), FontProvider.WidthOverride(0x1F600, 0x1F602, -2, 33))
        for (fractional in listOf(false, true)) {
            for (entries in listOf(0, 2, 4096)) {
                val snapshot = snapshot(ranges, capabilities = FontTestResources.compatibility.copy(fractionalUnihexAdvance = fractional))
                MinecraftFontEngine(snapshot, { FontTestBackend() }, cacheEntries = entries).use { engine ->
                    val retained = engine.glyph(FontTestResources.defaultFont, 65)
                    for (scalar in listOf(65, 66, 70, 71, 0x1F600, 0x1F602, 65)) {
                        assertEquals(expected(ranges, scalar, fractional), engine.glyph(FontTestResources.defaultFont, scalar))
                    }
                    assertEquals(expected(ranges, 65, fractional), retained)
                    assertEquals(1, indexes(engine).size)
                    assertTrue(units(engine) <= 65_536)
                }
            }
        }
    }

    @Test
    fun absentAndFirstRangeGlyphsNeverAdmitAnIndexAndSmallOrOversizedInputsUseTheLoop() {
        for (count in listOf(0, 1, 8, 128, 8_193)) {
            val ranges = if (count == 0) emptyList() else listOf(FontProvider.WidthOverride(65, 66, 0, 7)) + fillers(count - 1)
            MinecraftFontEngine(snapshot(ranges), { FontTestBackend() }, cacheEntries = 0).use { engine ->
                engine.glyph(FontTestResources.defaultFont, 65)
                engine.glyph(FontTestResources.defaultFont, 0x1F601)
                assertTrue(indexes(engine).isEmpty())
                assertEquals(0, units(engine))
            }
        }
        for (count in listOf(1, 8, 8_193)) {
            val ranges = fillers(count - 1) + FontProvider.WidthOverride(65, 66, 1, 7)
            MinecraftFontEngine(snapshot(ranges), { FontTestBackend() }, cacheEntries = 0).use { engine ->
                assertEquals(expected(ranges, 65, true), engine.glyph(FontTestResources.defaultFont, 65))
                assertTrue(indexes(engine).isEmpty())
            }
        }
    }

    @Test
    fun indexDoesNotEvaluateUnusedOrShadowedInvalidWidthsButSelectedFailuresStayLazy() {
        val valid = FontProvider.WidthOverride(65, 66, 0, 7)
        val shadowed = FontProvider.WidthOverride(65, 66, Int.MIN_VALUE, Int.MAX_VALUE)
        val unused = FontProvider.WidthOverride(80, 90, 10, -10)
        val ranges = fillers(16) + listOf(valid, shadowed, unused)
        val snapshot = snapshot(ranges)
        assertTrue(snapshot.diagnostics.isEmpty())
        MinecraftFontEngine(snapshot, { FontTestBackend() }, cacheEntries = 0).use { engine ->
            assertEquals(expected(ranges, 65, true), engine.glyph(FontTestResources.defaultFont, 65))
            assertEquals(1, indexes(engine).size)
            // The absent sparse glyph returns before selecting the invalid width at 80.
            assertEquals(6f, engine.glyph(FontTestResources.defaultFont, 80).advance)
        }
        for (invalid in listOf(shadowed, valid.copy(left = 10, right = -10), valid.copy(left = 1, right = 0), valid.copy(left = 0, right = 8_192))) {
            val selected = fillers(16) + invalid
            val state = snapshot(selected)
            assertTrue(state.diagnostics.isEmpty())
            val reference = runCatching { expected(selected, 65, true) }.exceptionOrNull()
            assertTrue(reference != null)
            val engine = MinecraftFontEngine(state, { FontTestBackend() }, cacheEntries = 2)
            try {
                repeat(2) {
                    val failure = runCatching { engine.glyph(FontTestResources.defaultFont, 65) }.exceptionOrNull()
                    assertEquals(checkNotNull(reference).javaClass, checkNotNull(failure).javaClass)
                    assertEquals(reference.message, failure.message)
                }
            } finally {
                engine.close()
            }
            assertTrue(indexes(engine).isEmpty())
            assertEquals(0, units(engine))
        }
    }

    @Test
    fun atlasRejectionUsesSelectedReleaseMetricsAndStillStopsProviderFallback() {
        val ranges = fillers(16) + FontProvider.WidthOverride(65, 66, 0, 256)
        for (baked in listOf(false, true)) {
            val capabilities = FontTestResources.compatibility.copy(bakedGlyphMetrics = baked)
            val document = provider(ranges) + "," + """{"type":"space","advances":{"A":99}}"""
            val snapshot = snapshot(ranges, capabilities, document)
            MinecraftFontEngine(snapshot, { FontTestBackend() }, cacheEntries = 0).use { engine ->
                val result = engine.glyph(FontTestResources.defaultFont, 65)
                assertEquals(if (baked) 6f else 129.5f, result.advance)
                assertEquals(IntSize(5, 8), checkNotNull(result.image).size)
                assertEquals(if (baked) 1f else 0.5f, result.boldOffset)
                assertEquals(1, indexes(engine).size)
            }
        }
    }

    @Test
    fun aliasesShareOnlyTheirExactDeclarationAndEnginesRemainIndependentOwners() {
        val ranges = fillers(128) + FontProvider.WidthOverride(65, 66, 1, 7)
        val state =
            FontTestResources.snapshot(
                FontTestResources.font("test:shared", provider(ranges)),
                FontTestResources.font("default", """{"type":"reference","id":"test:shared"}"""),
                FontTestResources.font("test:alias", """{"type":"reference","id":"test:shared"}"""),
                FontTestResources.font("test:other", provider(ranges.drop(1) + ranges.first())),
                archive(),
            )
        val firstBackend = FontTestBackend()
        val first = MinecraftFontEngine(state, { firstBackend }, cacheEntries = 0)
        val second = MinecraftFontEngine(state, { FontTestBackend() }, cacheEntries = 0)
        val old = first.glyph(FontTestResources.defaultFont, 65)
        first.glyph(ResourceId("test", "alias"), 65)
        assertEquals(1, indexes(first).size)
        second.glyph(ResourceId("test", "shared"), 65)
        assertEquals(1, indexes(second).size)
        assertNotSame(indexes(first).values.single(), indexes(second).values.single())
        // A distinct ordered declaration retains its own engine-local identity.
        first.glyph(ResourceId("test", "other"), 65)
        assertEquals(2, indexes(first).size)
        val error = AtomicReference<Throwable?>()
        val thread = Thread { error.set(runCatching { first.glyph(FontTestResources.defaultFont, 65) }.exceptionOrNull()) }
        thread.start()
        thread.join()
        assertTrue(error.get() is IllegalStateException)
        first.close()
        first.close()
        assertTrue(indexes(first).isEmpty())
        assertEquals(0, units(first))
        assertEquals(1, firstBackend.closeCalls)
        assertEquals(expected(ranges, 65, true), old)
        assertEquals(expected(ranges, 65, true), second.glyph(FontTestResources.defaultFont, 65))
        assertThrows(IllegalStateException::class.java) { first.glyph(FontTestResources.defaultFont, 65) }
        second.close()
    }

    @Test
    fun aggregateAdmissionIsFiniteAndExhaustionRetainsTheOriginalLoop() {
        val ranges = fillers(4_095) + FontProvider.WidthOverride(65, 66, 1, 7)
        val files = (0..8).map { FontTestResources.font("test:owner_$it", provider(ranges)) } + archive()
        val state = FontTestResources.snapshot(*files.toTypedArray())
        assertTrue(state.diagnostics.isEmpty())
        MinecraftFontEngine(state, { FontTestBackend() }, cacheEntries = 0).use { engine ->
            for (provider in 0..8) {
                repeat(8) { assertEquals(expected(ranges, 65, true), engine.glyph(ResourceId("test", "owner_$provider"), 65)) }
            }
            assertEquals(8, indexes(engine).size)
            assertEquals(65_536, units(engine))
            assertEquals(512 * 1024, indexes(engine).values.sumOf { (it as FontUnihexWidthIndex).bytes })
            for (scalar in 65..70) engine.glyph(ResourceId("test", "owner_8"), scalar)
            assertEquals(8, indexes(engine).size)
            assertEquals(65_536, units(engine))
        }
    }

    @Test
    fun filteredAndPoisonedProvidersNeverConstructAnIndexAndMalformedRangesKeepLoadDiagnostics() {
        val ranges = fillers(16) + FontProvider.WidthOverride(65, 66, 0, 7)
        val filtered = provider(ranges).dropLast(1) + ""","filter":{"uniform":true}}"""
        val sibling = """{"type":"space","advances":{"A":9}}"""
        val poisoned = """{"type":"ttf","file":"test:missing.ttf","filter":{"uniform":true}}"""
        for ((document, advance) in listOf("$filtered,$sibling" to 9f, "${provider(ranges)},$poisoned" to 6f)) {
            MinecraftFontEngine(snapshot(ranges, document = document), { FontTestBackend() }, cacheEntries = 0).use { engine ->
                repeat(3) { assertEquals(advance, engine.glyph(FontTestResources.defaultFont, 65).advance) }
                assertTrue(indexes(engine).isEmpty())
            }
        }
        val malformed = provider(listOf(FontProvider.WidthOverride(65, 65, 0, 7)))
        val state = snapshot(emptyList(), document = malformed)
        assertEquals(MinecraftFontDiagnostic.Kind.MalformedDocument, state.diagnostics.single().kind)
    }

    @Test
    fun terminalCleanupDropsIndexAndSnapshotEvenWhenBackendCloseFails() {
        val ranges = fillers(128) + FontProvider.WidthOverride(65, 66, 0, 7)
        val failure = IllegalStateException("backend close failed")
        val backend = FontTestBackend(release = { throw failure })
        val engine = MinecraftFontEngine(snapshot(ranges), { backend }, cacheEntries = 0)
        val old = engine.glyph(FontTestResources.defaultFont, 65)
        assertSame(failure, assertThrows(IllegalStateException::class.java) { engine.close() })
        engine.close()
        assertTrue(indexes(engine).isEmpty())
        assertEquals(0, units(engine))
        assertNull(MinecraftFontEngine::class.java.getDeclaredField("snapshot").apply { isAccessible = true }.get(engine))
        assertEquals(0, engine.retainedRasterEntries)
        assertEquals(expected(ranges, 65, true), old)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun replacementSnapshotDoesNotReuseSameNumberedProviderOrChangeOldReturnedGlyphs() {
        val oldRanges = fillers(128) + FontProvider.WidthOverride(65, 66, 0, 7)
        val newRanges = fillers(128) + FontProvider.WidthOverride(65, 66, -1, 8)
        val oldOwner = MinecraftFontEngine(snapshot(oldRanges), { FontTestBackend() }, cacheEntries = 0)
        val newOwner = MinecraftFontEngine(snapshot(newRanges), { FontTestBackend() }, cacheEntries = 0)
        try {
            val oldGlyph = oldOwner.glyph(FontTestResources.defaultFont, 65)
            assertEquals(expected(newRanges, 65, true), newOwner.glyph(FontTestResources.defaultFont, 65))
            assertEquals(expected(oldRanges, 65, true), oldOwner.glyph(FontTestResources.defaultFont, 65))
            assertNotSame(indexes(oldOwner).values.single(), indexes(newOwner).values.single())
            oldOwner.close()
            assertEquals(expected(oldRanges, 65, true), oldGlyph)
            assertEquals(expected(newRanges, 65, true), newOwner.glyph(FontTestResources.defaultFont, 65))
        } finally {
            oldOwner.close()
            newOwner.close()
        }
    }

    @Test
    fun callbackCloseContinuationKeepsOriginalGlyphAndWidthFailureOrderWithoutReadmittingIndexes() {
        for (case in ContinuationCase.entries) {
            val ranges = continuationRanges(case)
            val document = """{"type":"ttf","file":"test:continuation.ttf"}""" + "," + provider(ranges)
            val state =
                FontTestResources.snapshot(
                    FontTestResources.font("default", document),
                    "assets/test/font/continuation.ttf" to byteArrayOf(1),
                    archive(),
                )
            assertTrue(state.diagnostics.isEmpty())
            for (control in continuationControls()) {
                val cleanupFailure = IllegalStateException("observed backend cleanup")
                val reference = closedContinuation(state, case, control, cleanupFailure, original = true)
                val candidate = closedContinuation(state, case, control, cleanupFailure, original = false)
                assertEquals(reference.getOrNull(), candidate.getOrNull())
                assertEquals(reference.exceptionOrNull()?.javaClass, candidate.exceptionOrNull()?.javaClass)
                assertEquals(reference.exceptionOrNull()?.message, candidate.exceptionOrNull()?.message)
                when {
                    control.cleanup == ContinuationCleanup.PropagatedFailure -> assertSame(cleanupFailure, candidate.exceptionOrNull())
                    control.imageNull -> assertEquals(7f, candidate.getOrThrow().advance)
                    case == ContinuationCase.Absent -> {
                        assertEquals(6f, candidate.getOrThrow().advance)
                        assertEquals(IntSize(5, 8), checkNotNull(candidate.getOrThrow().image).size)
                    }
                    case == ContinuationCase.Overflow -> assertTrue(candidate.exceptionOrNull() is ArithmeticException)
                    else -> assertEquals("Font engine is closed.", checkNotNull(candidate.exceptionOrNull()).message)
                }
            }
        }
    }

    @Test
    fun foreignThreadRejectionAndCallbackCloseLeaveAnIndependentIndexOwnerOpen() {
        val ranges = continuationRanges(ContinuationCase.Late)
        val document = """{"type":"ttf","file":"test:continuation.ttf"}""" + "," + provider(ranges)
        val state = FontTestResources.snapshot(FontTestResources.font("default", document), "assets/test/font/continuation.ttf" to byteArrayOf(1), archive())
        lateinit var first: MinecraftFontEngine
        var closeOnGlyph = false
        val firstBackend =
            FontTestBackend(
                open = { _, _ ->
                    FontTestFace(
                        lookup = {
                            if (closeOnGlyph) first.close()
                            null
                        },
                    )
                },
            )
        val secondBackend = FontTestBackend(open = { _, _ -> FontTestFace(lookup = { null }) })
        first = MinecraftFontEngine(state, { firstBackend }, cacheEntries = 0, maxFaces = 1)
        val second = MinecraftFontEngine(state, { secondBackend }, cacheEntries = 0, maxFaces = 1)
        try {
            first.glyph(FontTestResources.defaultFont, 70)
            second.glyph(FontTestResources.defaultFont, 70)
            for (operation in listOf({ first.glyph(FontTestResources.defaultFont, 65) }, { first.close() })) {
                val failure = AtomicReference<Throwable?>()
                val thread = Thread { failure.set(runCatching(operation).exceptionOrNull()) }
                thread.start()
                thread.join()
                assertTrue(failure.get() is IllegalStateException)
                assertEquals(1, indexes(first).size)
                assertEquals(1, indexes(second).size)
            }
            closeOnGlyph = true
            assertThrows(IllegalStateException::class.java) { first.glyph(FontTestResources.defaultFont, 65) }
            assertContinuationTerminal(first)
            assertEquals(expected(ranges, 65, true), second.glyph(FontTestResources.defaultFont, 65))
            assertEquals(1, indexes(second).size)
            assertEquals(1, firstBackend.closeCalls)
            assertEquals(0, secondBackend.closeCalls)
        } finally {
            first.close()
            second.close()
        }
        assertContinuationTerminal(second)
        assertEquals(1, secondBackend.closeCalls)
    }

    private fun closedContinuation(
        state: MinecraftFontSnapshot,
        case: ContinuationCase,
        control: ContinuationControl,
        cleanupFailure: Throwable,
        original: Boolean,
    ): Result<MinecraftFontGlyph> {
        lateinit var engine: MinecraftFontEngine
        var armed = false
        var glyphCalls = 0
        var faceCloses = 0
        var observedCloseFailure: Throwable? = null
        val detachedGlyph = MinecraftFontGlyph(7f, 0f, 0f, 0f, 0f, null)
        val backend =
            FontTestBackend(
                open = { _, _ ->
                    FontTestFace(
                        lookup = {
                            glyphCalls++
                            if (armed) {
                                if (control.cleanup == ContinuationCleanup.PropagatedFailure) {
                                    engine.close()
                                } else {
                                    observedCloseFailure = runCatching(engine::close).exceptionOrNull()
                                }
                            }
                            if (armed && control.imageNull) detachedGlyph else null
                        },
                        release = {
                            faceCloses++
                            assertTrue(indexes(engine).isEmpty())
                            assertEquals(0, units(engine))
                            if (control.cleanup == ContinuationCleanup.Reentrant) engine.close()
                        },
                    )
                },
                release = {
                    assertTrue(indexes(engine).isEmpty())
                    assertEquals(0, units(engine))
                    if (control.cleanup == ContinuationCleanup.Reentrant) engine.close()
                    if (control.cleanup in setOf(ContinuationCleanup.CaughtFailure, ContinuationCleanup.PropagatedFailure)) throw cleanupFailure
                },
            )
        engine = MinecraftFontEngine(state, { backend }, cacheEntries = control.entries, maxFaces = 1)
        try {
            val missing = engine.glyph(ResourceId("unknown", "continuation"), 65)
            if (control.primed) {
                engine.glyph(FontTestResources.defaultFont, 70)
                assertEquals(if (case == ContinuationCase.Unadmitted) 0 else 1, indexes(engine).size)
            }
            armed = true
            val scalar = if (case == ContinuationCase.Absent) 80 else 65
            val result = runCatching { if (original) originalContinuation(state, engine, scalar) else engine.glyph(FontTestResources.defaultFont, scalar) }
            if (control.cleanup == ContinuationCleanup.CaughtFailure) assertSame(cleanupFailure, observedCloseFailure)
            if (control.cleanup != ContinuationCleanup.PropagatedFailure) {
                if (control.imageNull) assertSame(detachedGlyph, result.getOrThrow())
                if (control.imageNull.not() && case == ContinuationCase.Absent) assertSame(missing, result.getOrThrow())
            }
            assertEquals(if (control.primed) 2 else 1, glyphCalls)
            assertEquals(1, backend.openCalls)
            assertEquals(1, faceCloses)
            assertEquals(1, backend.closeCalls)
            assertContinuationTerminal(engine)
            return result
        } finally {
            engine.close()
        }
    }

    // This independent original walk bypasses both resolution selection and Unihex index selection.
    // Private preflight and TrueType raster hooks are shared; call counts and terminal results are asserted separately.
    private fun originalContinuation(
        state: MinecraftFontSnapshot,
        engine: MinecraftFontEngine,
        scalar: Int,
    ): MinecraftFontGlyph {
        FontJson.validateScalar(scalar)
        val missing = engine.glyph(ResourceId("unknown", "continuation"), scalar)
        val providers = checkNotNull(state.fonts[FontTestResources.defaultFont])
        if ((continuationInvoke(engine, "prepareFont", arrayOf(ResourceId::class.java, List::class.java), FontTestResources.defaultFont, providers) as Boolean).not()) return missing
        for (entry in providers) {
            check(entry.filter.isEmpty())
            val result =
                if (entry.provider is FontProvider.Unihex) {
                    originalContinuationUnihex(engine, entry.provider, scalar)
                } else {
                    continuationInvoke(engine, "cachedGlyph", arrayOf(FontProviderEntry::class.java, Int::class.java), entry, scalar) as MinecraftFontGlyph?
                }
            if (result != null) return result
        }
        return missing
    }

    private fun originalContinuationUnihex(
        engine: MinecraftFontEngine,
        provider: FontProvider.Unihex,
        scalar: Int,
    ): MinecraftFontGlyph? {
        val glyph = provider.glyphs.glyph(scalar) ?: return null
        val override = provider.overrides.firstOrNull { bounds -> scalar in bounds.first..bounds.last }
        val bounds = override?.let { it.left..it.right } ?: glyph.bounds()
        val width = Math.addExact(Math.subtractExact(bounds.last, bounds.first), 1)
        val current = continuationInvoke(engine, "requireSnapshot", emptyArray()) as MinecraftFontSnapshot
        current.limits.requireImageSize(width, 16)
        return expected(provider.overrides, scalar, current.compatibility.fractionalUnihexAdvance)
    }

    private fun assertContinuationTerminal(engine: MinecraftFontEngine) {
        assertTrue(indexes(engine).isEmpty())
        assertEquals(0, units(engine))
        assertEquals(0, engine.retainedRasterEntries)
        assertEquals(0L, engine.retainedRasterBytes)
        assertEquals(0, engine.retainedFaces)
        assertEquals(0L, continuationField(engine, "faceBytes"))
        assertEquals(0, continuationField(engine, "resolutionUnits"))
        for (name in listOf("resolutions", "rasters", "faces", "bitmapSizes", "bitmapFailures", "faceFailures", "providerStatus", "fontStatus")) {
            assertTrue((continuationField(engine, name) as Map<*, *>).isEmpty(), name)
        }
        assertTrue((continuationField(engine, "validatedFaces") as Set<*>).isEmpty())
        assertNull(continuationField(engine, "snapshot"))
        assertNull(continuationField(engine, "backend"))
        assertEquals("Font engine is closed.", assertThrows(IllegalStateException::class.java) { engine.glyph(FontTestResources.defaultFont, 65) }.message)
        engine.close()
        assertTrue(indexes(engine).isEmpty())
        assertEquals(0, units(engine))
    }

    private fun continuationRanges(case: ContinuationCase): List<FontProvider.WidthOverride> {
        val valid = FontProvider.WidthOverride(65, 66, 0, 7)
        return when (case) {
            ContinuationCase.First -> listOf(valid) + fillers(16)
            ContinuationCase.Unadmitted -> fillers(8_192) + valid
            ContinuationCase.Overflow -> fillers(16) + valid.copy(left = Int.MIN_VALUE, right = Int.MAX_VALUE)
            ContinuationCase.Shadowed -> fillers(16) + listOf(valid, valid.copy(left = Int.MIN_VALUE, right = Int.MAX_VALUE))
            ContinuationCase.Late, ContinuationCase.Absent -> fillers(16) + valid
        }
    }

    private fun continuationControls(): List<ContinuationControl> =
        buildList {
            for (entries in listOf(0, 2, 4096)) {
                for (cleanup in ContinuationCleanup.entries) {
                    for (imageNull in listOf(false, true)) {
                        for (primed in listOf(false, true)) add(ContinuationControl(entries, cleanup, imageNull, primed))
                    }
                }
            }
        }

    private fun continuationField(
        engine: MinecraftFontEngine,
        name: String,
    ): Any? =
        MinecraftFontEngine::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(engine)

    private fun continuationInvoke(
        engine: MinecraftFontEngine,
        name: String,
        types: Array<Class<*>>,
        vararg arguments: Any,
    ): Any? =
        try {
            MinecraftFontEngine::class.java
                .getDeclaredMethod(name, *types)
                .apply { isAccessible = true }
                .invoke(engine, *arguments)
        } catch (failure: InvocationTargetException) {
            throw checkNotNull(failure.targetException)
        }

    private data class ContinuationControl(
        val entries: Int,
        val cleanup: ContinuationCleanup,
        val imageNull: Boolean,
        val primed: Boolean,
    )

    private enum class ContinuationCase {
        Late,
        First,
        Absent,
        Unadmitted,
        Overflow,
        Shadowed,
    }

    private enum class ContinuationCleanup {
        Success,
        Reentrant,
        CaughtFailure,
        PropagatedFailure,
    }

    private fun originalWinner(
        ranges: List<FontProvider.WidthOverride>,
        scalar: Int,
    ): FontProvider.WidthOverride? {
        for (range in ranges) if (range.first <= scalar && scalar <= range.last) return range
        return null
    }

    private fun expected(
        ranges: List<FontProvider.WidthOverride>,
        scalar: Int,
        fractional: Boolean,
    ): MinecraftFontGlyph {
        val winner = originalWinner(ranges, scalar)
        val left = winner?.left ?: 0
        val right = winner?.right ?: 7
        val width = Math.addExact(Math.subtractExact(right, left), 1)
        MinecraftFontLoadLimits().requireImageSize(width, 16)
        val pixels = IntArray(Math.multiplyExact(width, 16)) { index -> if (left + index % width in setOf(0, 7)) -1 else 0 }
        val advance = if (fractional) width / 2f + 1f else (width / 2 + 1).toFloat()
        return MinecraftFontGlyph(advance, 0f, 0f, width / 2f, 8f, createDrawImage(IntSize(width, 16), pixels), boldOffset = 0.5f, shadowOffset = 0.5f)
    }

    private fun fillers(count: Int): List<FontProvider.WidthOverride> = List(count) { FontProvider.WidthOverride(10_000 + it * 2, 10_001 + it * 2, 0, 7) }

    private fun provider(ranges: List<FontProvider.WidthOverride>): String = JsonObject().apply {
        addProperty("type", "unihex")
        addProperty("hex_file", "test:font/index.zip")
        add(
            "size_overrides",
            JsonArray().apply {
                ranges.forEach { range ->
                    add(
                        JsonObject().apply {
                            addProperty("from", String(Character.toChars(range.first)))
                            addProperty("to", String(Character.toChars(range.last)))
                            addProperty("left", range.left)
                            addProperty("right", range.right)
                        },
                    )
                }
            },
        )
    }.toString()

    private fun archive(): Pair<String, ByteArray> {
        val hex = listOf(65, 66, 70, 71, 0x1F600, 0x1F602).joinToString("\n") { "${it.toString(16).uppercase().padStart(4, '0')}:${"81".repeat(16)}" }
        return "assets/test/font/index.zip" to FontTestResources.archive("index.hex" to hex.toByteArray())
    }

    private fun snapshot(
        ranges: List<FontProvider.WidthOverride>,
        capabilities: MinecraftFontCompatibility = FontTestResources.compatibility,
        document: String = provider(ranges),
    ): MinecraftFontSnapshot = FontTestResources.snapshot(FontTestResources.font("default", document), archive(), capabilities = capabilities)

    private fun indexes(engine: MinecraftFontEngine): Map<*, *> = MinecraftFontEngine::class.java.getDeclaredField("unihexIndexes").apply { isAccessible = true }.get(engine) as Map<*, *>

    private fun units(engine: MinecraftFontEngine): Int = MinecraftFontEngine::class.java.getDeclaredField("unihexIndexUnits").apply { isAccessible = true }.getInt(engine)
}
