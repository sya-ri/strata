package dev.s7a.strata.runtime.minecraft.font

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies inclusive allocation budgets and streaming Unihex rejection without game or native dependencies.
 */
internal class MinecraftFontLoadLimitsTest {
    @Test
    fun exhaustedCountersRemainExhaustedAfterRepeatedRejectedWork() {
        val limits = MinecraftFontLoadLimits(maxEntries = 2, maxFontDocuments = 2, maxProviders = 2, maxGlyphs = 2, maxGlyphRowBytes = 2, maxInputBytes = 2, maxDecompressedBytes = 2, maxResolvedProviders = 2, maxTrueTypeFaces = 2, maxTrueTypeInputBytes = 2)
        for (kind in FontLoadBudget.Kind.entries) {
            val exact = FontLoadBudget(limits)
            exact.claim(kind, 1)
            exact.claim(kind, 1)
            assertEquals(0L, exact.remaining(kind))
            assertThrows(MinecraftFontLoadLimitException::class.java) { exact.claim(kind, 1) }
            val over = FontLoadBudget(limits)
            assertThrows(MinecraftFontLoadLimitException::class.java) { over.claim(kind, 3) }
            assertEquals(0L, over.remaining(kind))
            assertThrows(MinecraftFontLoadLimitException::class.java) { over.claim(kind, 1) }
            assertEquals(0L, over.remaining(kind))
        }
    }

    @Test
    fun customSourceReadsReceiveRemainingCapacityAndOversizedResultsStillConsumeIt() {
        val received = ArrayList<Int>()
        val source =
            object : MinecraftBoundedFontAssetSource {
                override val name = "bounded"

                override fun paths(): Set<String> = setOf("a")

                override fun read(path: String): ByteArray = error("The bounded callback must be selected.")

                override fun read(
                    path: String,
                    limits: MinecraftFontLoadLimits,
                ): ByteArray {
                    received.add(limits.maxAssetBytes)
                    return ByteArray(3)
                }
            }
        val budget = FontLoadBudget(MinecraftFontLoadLimits(maxAssetBytes = 3, maxInputBytes = 5))
        assertEquals(3, budget.read(source, "a")?.size)
        assertThrows(MinecraftFontLoadLimitException::class.java) { budget.read(source, "a") }
        assertThrows(MinecraftFontLoadLimitException::class.java) { budget.read(source, "a") }
        assertEquals(listOf(3, 2, 0), received)
        assertEquals(0L, budget.remaining(FontLoadBudget.Kind.InputBytes))
    }

    @Test
    fun typedEnumerationLimitsExcludeOnlyTheirSourceAndDoNotHideProgrammingFailures() {
        val valid = FontTestResources.source(FontTestResources.font("default", """{"type":"space","advances":{"A":3}}"""))
        val failed =
            object : MinecraftFontAssetSource by valid {
                override val name = "limited"

                override fun paths(): Set<String> = throw MinecraftFontLoadLimitException("custom entry ceiling")
            }
        val limits = MinecraftFontLoadLimits(maxSourceEntries = 1, maxEntries = 3)
        val snapshot = MinecraftFontSnapshot.load(listOf(failed, valid), FontTestResources.compatibility, MinecraftFontOptions(), limits)
        assertEquals(setOf(FontTestResources.defaultFont), snapshot.fontIds)
        assertEquals("limited", snapshot.diagnostics.single().source)
        val expected = IllegalArgumentException("custom programming failure")
        val broken =
            object : MinecraftFontAssetSource by valid {
                override fun paths(): Set<String> = throw expected
            }
        assertSame(expected, assertThrows(IllegalArgumentException::class.java) { MinecraftFontSnapshot.load(listOf(broken), FontTestResources.compatibility) })
    }

    @Test
    fun jsonPreflightPreservesLenientSyntaxAndRejectsDepthOrTokensBeforeTreeParsing() {
        val contents = """{"ignored":[[0],]}""".toByteArray()
        assertTrue(FontJson.document(contents, MinecraftFontLoadLimits(maxJsonDepth = 3)).has("ignored"))
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontJson.document(contents, MinecraftFontLoadLimits(maxJsonDepth = 2)) }
        val empty = "{}".toByteArray()
        assertTrue(FontJson.document(empty, MinecraftFontLoadLimits(maxJsonValues = 2)).entrySet().isEmpty())
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontJson.document(empty, MinecraftFontLoadLimits(maxJsonValues = 1)) }
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontJson.document(empty, MinecraftFontLoadLimits(maxDocumentBytes = 1)) }
    }

    @Test
    fun unihexEntryAndAggregateExpansionCeilingsIncludeIgnoredFilesAndDetectionBytes() {
        val encoded = FontTestResources.archive("ignored.txt" to ByteArray(8), "ignored/" to ByteArray(8))
        val exact = FontLoadBudget(MinecraftFontLoadLimits(maxDecompressedEntryBytes = 8, maxDecompressedBytes = 16))
        assertNull(FontUnihexData.load(encoded, exact).glyph('A'.code))
        assertEquals(0L, exact.remaining(FontLoadBudget.Kind.DecompressedBytes))
        val entryOver = FontLoadBudget(MinecraftFontLoadLimits(maxDecompressedEntryBytes = 7, maxDecompressedBytes = 100))
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(encoded, entryOver) }
        assertEquals(92L, entryOver.remaining(FontLoadBudget.Kind.DecompressedBytes))
        val aggregateOver = FontLoadBudget(MinecraftFontLoadLimits(maxDecompressedEntryBytes = 8, maxDecompressedBytes = 15))
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(encoded, aggregateOver) }
        assertEquals(0L, aggregateOver.remaining(FontLoadBudget.Kind.DecompressedBytes))
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(encoded, aggregateOver) }
        assertEquals(0L, aggregateOver.remaining(FontLoadBudget.Kind.DecompressedBytes))
    }

    @Test
    fun nestedArchiveEntriesShareCapacityAcrossArchivesAndIncludeDirectories() {
        val encoded = FontTestResources.archive("ignored/" to byteArrayOf(), "ignored.txt" to byteArrayOf())
        val exact = FontLoadBudget(MinecraftFontLoadLimits(maxSourceEntries = 2, maxEntries = 4))
        repeat(2) { FontUnihexData.load(encoded, exact) }
        assertEquals(0L, exact.remaining(FontLoadBudget.Kind.SourceEntries))
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(encoded, exact) }
        val over = FontLoadBudget(MinecraftFontLoadLimits(maxSourceEntries = 2, maxEntries = 3))
        FontUnihexData.load(encoded, over)
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(encoded, over) }
        assertEquals(0L, over.remaining(FontLoadBudget.Kind.SourceEntries))
        val local = FontLoadBudget(MinecraftFontLoadLimits(maxSourceEntries = 1, maxEntries = 10))
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(encoded, local) }
        assertEquals(8L, local.remaining(FontLoadBudget.Kind.SourceEntries))
    }

    @Test
    fun duplicateGlyphRecordsConsumeGlyphAndRowBudgetsBeforeAllocation() {
        val record = "0041:${"FF".repeat(16)}\n"
        val encoded = FontTestResources.archive("glyphs.hex" to record.repeat(2).toByteArray())
        val limits = MinecraftFontLoadLimits(maxGlyphs = 2, maxGlyphRowBytes = 256)
        val exact = FontLoadBudget(limits)
        assertNotNull(FontUnihexData.load(encoded, exact).glyph('A'.code))
        assertEquals(0L, exact.remaining(FontLoadBudget.Kind.Glyphs))
        assertEquals(0L, exact.remaining(FontLoadBudget.Kind.GlyphRowBytes))
        val rowOver = FontLoadBudget(limits.copy(maxGlyphRowBytes = 255))
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(encoded, rowOver) }
        assertEquals(0L, rowOver.remaining(FontLoadBudget.Kind.GlyphRowBytes))
        val glyphOver = FontLoadBudget(limits.copy(maxGlyphs = 1))
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(encoded, glyphOver) }
        assertEquals(0L, glyphOver.remaining(FontLoadBudget.Kind.Glyphs))
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(encoded, glyphOver) }
    }

    @Test
    fun unihexRecordBufferAcceptsItsLargestNativeRecordAndClosesRejectedStreams() {
        val record = "10FFFF:${"F".repeat(128)}"
        assertEquals(135, record.length)
        val valid = FontTestResources.archive("glyphs.hex" to record.toByteArray())
        assertEquals(32, FontUnihexData.load(valid, FontLoadBudget(MinecraftFontLoadLimits())).glyph(0x10FFFF)?.width)
        val oversized = FontTestResources.archive("glyphs.hex" to (record + "F").toByteArray())
        var closed = false
        val input =
            object : ByteArrayInputStream(oversized) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(input, FontLoadBudget(MinecraftFontLoadLimits())) }
        assertTrue(closed)
    }

    @Test
    fun compressedIgnoredZipBombStopsAfterItsBoundedDetectionByte() {
        val encoded = FontTestResources.archive("ignored.bin" to ByteArray(128 * 1024))
        assertTrue(encoded.size < 1024)
        val budget = FontLoadBudget(MinecraftFontLoadLimits(maxDecompressedEntryBytes = 32, maxDecompressedBytes = 100))
        assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(encoded, budget) }
        assertEquals(67L, budget.remaining(FontLoadBudget.Kind.DecompressedBytes))
    }

    @Test
    fun retainedAndForeignEntryCallbacksCannotAccessTheLoadingBudget() {
        var captured: (() -> Unit)? = null
        val source =
            object : MinecraftBoundedFontAssetSource {
                override val name = "callback"

                override fun paths(): Set<String> = setOf("a")

                override fun read(path: String): ByteArray? = null

                override fun paths(
                    limits: MinecraftFontLoadLimits,
                    onEntryExamined: () -> Unit,
                ): Set<String> {
                    captured = onEntryExamined
                    val foreign = FutureTask { assertThrows(IllegalStateException::class.java) { onEntryExamined() } }
                    Thread(foreign).start()
                    foreign.get(10, TimeUnit.SECONDS)
                    onEntryExamined()
                    return paths()
                }
            }
        val budget = FontLoadBudget(MinecraftFontLoadLimits(maxEntries = 2))
        assertEquals(setOf("a"), budget.paths(source))
        assertEquals(1L, budget.remaining(FontLoadBudget.Kind.SourceEntries))
        assertThrows(IllegalStateException::class.java) { requireNotNull(captured).invoke() }
        assertEquals(1L, budget.remaining(FontLoadBudget.Kind.SourceEntries))
        val callback = requireNotNull(captured)
        val retained =
            callback.javaClass.declaredFields.map { field ->
                field.isAccessible = true
                field.get(callback)
            }
        assertTrue(retained.none { value -> value is FontLoadBudget || value is Thread })
    }

    @Test
    fun retainedEntryCallbacksReleaseTheirBudgetOnSuccessAndSourceFailure() {
        for (throws in listOf(false, true)) {
            val (reference, callback) = detachedEntryCallback(throws)
            FontTestReferences.assertCollected(reference)
            assertThrows(IllegalStateException::class.java) { callback() }
        }
    }

    @Test
    fun nestedUnihexCompressedArchivesHonorExactAndZeroFileCeilingsBeforeOpening() {
        val bytes = FontTestResources.archive("ignored" to byteArrayOf(1))
        val exact = MinecraftFontLoadLimits(maxArchiveBytes = bytes.size.toLong())
        FontUnihexData.load(bytes, FontLoadBudget(exact))
        FontUnihexData.load(bytes, FontLoadBudget(exact.copy(maxArchiveBytes = bytes.size.toLong() + 1)))
        for (maximum in listOf(0L, bytes.size.toLong() - 1)) {
            val budget = FontLoadBudget(exact.copy(maxArchiveBytes = maximum))
            assertThrows(MinecraftFontLoadLimitException::class.java) { FontUnihexData.load(bytes, budget) }
            assertEquals(budget.limits.maxEntries.toLong(), budget.remaining(FontLoadBudget.Kind.SourceEntries))
        }
    }

    private fun detachedEntryCallback(throws: Boolean): Pair<WeakReference<FontLoadBudget>, () -> Unit> {
        val budget = FontLoadBudget(MinecraftFontLoadLimits(maxEntries = 2))
        var captured: (() -> Unit)? = null
        val source =
            object : MinecraftBoundedFontAssetSource {
                override val name = "detached"

                override fun paths(): Set<String> = emptySet()

                override fun read(path: String): ByteArray? = null

                override fun paths(
                    limits: MinecraftFontLoadLimits,
                    onEntryExamined: () -> Unit,
                ): Set<String> {
                    captured = onEntryExamined
                    onEntryExamined()
                    if (throws) throw IOException("enumeration failed after observing an entry")
                    return emptySet()
                }
            }
        if (throws) assertThrows(IOException::class.java) { budget.paths(source) } else budget.paths(source)
        assertEquals(1L, budget.remaining(FontLoadBudget.Kind.SourceEntries))
        return WeakReference(budget) to requireNotNull(captured)
    }
}
