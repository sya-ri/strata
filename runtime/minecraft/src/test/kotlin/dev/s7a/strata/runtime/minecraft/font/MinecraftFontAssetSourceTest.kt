package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Verifies direct offline sources and copied snapshot ownership without relying on installed Minecraft assets.
 */
internal class MinecraftFontAssetSourceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun memorySourcesCopyInputAndOutputBytesAndRejectUnsafePaths() {
        val original = byteArrayOf(1, 2, 3)
        val files = linkedMapOf("assets/test/font/example.ttf" to original)
        val source = MinecraftMemoryFontAssetSource("memory", files)
        original[0] = 9
        files.clear()
        val first = requireNotNull(source.read("assets/test/font/example.ttf"))
        assertArrayEquals(byteArrayOf(1, 2, 3), first)
        first[0] = 7
        assertArrayEquals(byteArrayOf(1, 2, 3), source.read("assets/test/font/example.ttf"))
        assertNull(source.read("assets/test/font/missing.ttf"))
        for (path in listOf("../escape", "/absolute", "assets\\test", "assets/./test", "assets//test", "C:/font")) {
            assertThrows(IllegalArgumentException::class.java) { source.read(path) }
            assertThrows(IllegalArgumentException::class.java) { MinecraftMemoryFontAssetSource("invalid", mapOf(path to original)) }
        }
    }

    @Test
    fun directoriesArchivesAndIndexedObjectsExposeIdenticalIndependentBytes() {
        val definition = FontTestResources.font("default", """{"type":"space","advances":{"日":8}}""")
        val pack = Files.createDirectories(directory.resolve("pack"))
        val definitionFile = pack.resolve(definition.first)
        Files.createDirectories(definitionFile.parent)
        Files.write(definitionFile, definition.second)
        val archive = directory.resolve("pack.zip")
        Files.write(archive, FontTestResources.archive(definition))
        val hash = MessageDigest.getInstance("SHA-1").digest(definition.second).joinToString("") { byte -> "%02x".format(byte) }
        val objects = Files.createDirectories(directory.resolve("objects"))
        val objectDirectory = Files.createDirectories(objects.resolve(hash.substring(0, 2)))
        Files.write(objectDirectory.resolve(hash), definition.second)
        val index = directory.resolve("index.json")
        Files.writeString(index, """{"objects":{"minecraft/font/default.json":{"hash":"$hash","size":${definition.second.size}}}}""")
        val sources =
            listOf(
                MinecraftDirectoryFontAssetSource(pack),
                MinecraftArchiveFontAssetSource(archive),
                MinecraftIndexedFontAssetSource(index, objects),
            )
        for (source in sources) {
            assertEquals(setOf(definition.first), source.paths())
            val bytes = requireNotNull(source.read(definition.first))
            assertArrayEquals(definition.second, bytes)
            bytes.fill(0)
            assertArrayEquals(definition.second, source.read(definition.first))
            assertNull(source.read("assets/test/missing"))
            val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility)
            MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { FontTestBackend() }).use { engine ->
                assertEquals(8.0f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
            }
        }
        Files.delete(archive)
        assertThrows(IllegalArgumentException::class.java) { sources.first().read("../outside") }
    }

    @Test
    fun invalidArchivePathsAndObjectHashesFailBeforeSnapshotConstruction() {
        val archive = directory.resolve("invalid.zip")
        Files.write(archive, FontTestResources.archive("../outside" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { MinecraftArchiveFontAssetSource(archive).paths() }
        val index = directory.resolve("index.json")
        Files.writeString(index, """{"objects":{"minecraft/font/default.json":{"hash":"../../outside"}}}""")
        assertThrows(IllegalArgumentException::class.java) { MinecraftIndexedFontAssetSource(index, directory) }
    }

    @Test
    fun engineNeverCallsSourcesAfterLoadingAndBackendCannotMutateSnapshotBytes() {
        val definition =
            FontTestResources.font(
                "default",
                """{"type":"bitmap","file":"test:font/example.png","height":8,"ascent":7,"chars":["日"]}""",
            )
        val files = linkedMapOf(definition, "assets/test/textures/font/example.png" to byteArrayOf(11))
        var released = false
        var reads = 0
        val source =
            object : MinecraftFontAssetSource {
                override val name = "callback"

                override fun paths(): Set<String> = files.keys.toSet()

                override fun read(path: String): ByteArray? {
                    check(released.not())
                    reads++
                    return files[path]?.copyOf()
                }
            }
        val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility)
        val loadingReads = reads
        released = true
        files.clear()
        repeat(2) {
            val backend =
                FontTestBackend(decode = { bytes ->
                    assertArrayEquals(byteArrayOf(11), bytes)
                    bytes[0] = 99
                    createDrawImage(IntSize(1, 1), intArrayOf(-1))
                })
            MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }).use { engine ->
                assertEquals(9.0f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
            }
        }
        assertEquals(loadingReads, reads)
    }

    @Test
    fun boundedStreamReadsAcceptTheCeilingAndConsumeOnlyOneDetectionByte() {
        for (length in listOf(2, 3, 4)) {
            var closed = false
            val input =
                object : ByteArrayInputStream(ByteArray(length) { 7 }) {
                    override fun close() {
                        closed = true
                        super.close()
                    }
                }
            if (length <= 3) {
                assertArrayEquals(ByteArray(length) { 7 }, input.use { it.readMinecraftFontBytes(3) })
            } else {
                assertThrows(MinecraftFontLoadLimitException::class.java) { input.use { it.readMinecraftFontBytes(3) } }
            }
            assertTrue(closed)
            assertEquals(0, input.available())
        }
        val input = ByteArrayInputStream(ByteArray(100))
        assertThrows(MinecraftFontLoadLimitException::class.java) { input.use { it.readMinecraftFontBytes(3) } }
        assertEquals(96, input.available())
        assertArrayEquals(byteArrayOf(), ByteArrayInputStream(byteArrayOf()).use { it.readMinecraftFontBytes(0) })
    }

    @Test
    fun archiveDirectoriesConsumeSharedCapacityAndLongIgnoredNamesStillCloseTheArchive() {
        val archive = directory.resolve("directories.zip")
        Files.write(archive, FontTestResources.archive("one/" to byteArrayOf(), "two/" to byteArrayOf()))
        val source = MinecraftArchiveFontAssetSource(archive)
        val budget = FontLoadBudget(MinecraftFontLoadLimits(maxSourceEntries = 2, maxEntries = 4))
        repeat(2) { assertTrue(budget.paths(source).isEmpty()) }
        assertEquals(0L, budget.remaining(FontLoadBudget.Kind.SourceEntries))
        assertThrows(MinecraftFontLoadLimitException::class.java) { budget.paths(source) }
        assertThrows(MinecraftFontLoadLimitException::class.java) { source.paths(MinecraftFontLoadLimits(maxSourceEntries = 1)) }
        assertThrows(MinecraftFontLoadLimitException::class.java) { source.paths(MinecraftFontLoadLimits(maxPathLength = 3)) }
        assertTrue(source.paths(MinecraftFontLoadLimits(maxPathLength = 4)).isEmpty())
        Files.delete(archive)
    }

    @Test
    fun sourceEntryReportsPrecedeFilteringAndExactArchiveByteLimitsAreInclusive() {
        val archive = directory.resolve("entries.zip")
        val encoded = FontTestResources.archive("folder/" to byteArrayOf(), "folder/data" to byteArrayOf(1, 2, 3))
        Files.write(archive, encoded)
        val source = MinecraftArchiveFontAssetSource(archive)
        val limits = MinecraftFontLoadLimits(maxArchiveBytes = encoded.size.toLong(), maxSourceEntries = 2, maxEntries = 2)
        val budget = FontLoadBudget(limits)
        assertEquals(setOf("folder/data"), budget.paths(source))
        assertEquals(0L, budget.remaining(FontLoadBudget.Kind.SourceEntries))
        assertThrows(MinecraftFontLoadLimitException::class.java) { source.paths(limits.copy(maxArchiveBytes = encoded.size.toLong() - 1)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), source.read("folder/data", limits.copy(maxAssetBytes = 3)))
        assertThrows(MinecraftFontLoadLimitException::class.java) { source.read("folder/data", limits.copy(maxAssetBytes = 2)) }
        Files.delete(archive)
    }

    @Test
    fun directoryEnumerationCountsDirectoriesAndClosesItsTraversalAtTheBoundary() {
        Files.createDirectories(directory.resolve("nested/leaf"))
        Files.write(directory.resolve("nested/leaf/data"), byteArrayOf(1))
        val source = MinecraftDirectoryFontAssetSource(directory)
        val budget = FontLoadBudget(MinecraftFontLoadLimits(maxSourceEntries = 3, maxEntries = 3))
        assertEquals(setOf("nested/leaf/data"), budget.paths(source))
        assertEquals(0L, budget.remaining(FontLoadBudget.Kind.SourceEntries))
        assertThrows(MinecraftFontLoadLimitException::class.java) { source.paths(MinecraftFontLoadLimits(maxSourceEntries = 2)) }
        Files.delete(directory.resolve("nested/leaf/data"))
        Files.delete(directory.resolve("nested/leaf"))
    }

    @Test
    fun indexedConstructionUsesTheSmallerDocumentAndAggregateByteCeiling() {
        val index = directory.resolve("bounded-index.json")
        val contents = """{"objects":{}}""".toByteArray()
        Files.write(index, contents)
        val exact = MinecraftFontLoadLimits(maxDocumentBytes = contents.size, maxInputBytes = contents.size.toLong())
        assertTrue(MinecraftIndexedFontAssetSource(index, directory, "exact", exact).paths().isEmpty())
        for (limits in listOf(exact.copy(maxDocumentBytes = contents.size - 1), exact.copy(maxInputBytes = contents.size.toLong() - 1), exact.copy(maxInputBytes = 0))) {
            assertThrows(MinecraftFontLoadLimitException::class.java) { MinecraftIndexedFontAssetSource(index, directory, "over", limits) }
        }
        assertTrue(MinecraftIndexedFontAssetSource(index, directory, "below", exact.copy(maxDocumentBytes = contents.size + 1, maxInputBytes = contents.size.toLong() + 1)).paths().isEmpty())
        Files.delete(index)
    }

    @Test
    fun memoryInputLimitsAreCheckedBeforeCopyingAndBoundedReadsPreserveTheOriginal() {
        val files = mapOf("a" to byteArrayOf(1, 2), "b" to byteArrayOf(3, 4, 5))
        val exact = MinecraftFontLoadLimits(maxSourceEntries = 2, maxAssetBytes = 3, maxInputBytes = 5)
        val source = MinecraftMemoryFontAssetSource("exact", files, exact)
        assertThrows(MinecraftFontLoadLimitException::class.java) { MinecraftMemoryFontAssetSource("entry-over", files, exact.copy(maxSourceEntries = 1)) }
        assertThrows(MinecraftFontLoadLimitException::class.java) { MinecraftMemoryFontAssetSource("total-over", files, exact.copy(maxInputBytes = 4)) }
        assertThrows(MinecraftFontLoadLimitException::class.java) { source.read("b", exact.copy(maxAssetBytes = 2)) }
        assertArrayEquals(files.getValue("b"), source.read("b", exact))
    }

    @Test
    fun missingAndFailedAssetReadsAreMemoizedWithoutRetainingSourcesOrFailures() {
        for (fails in listOf(false, true)) {
            val (snapshot, references, reads) = detachedRepeatedAssetFailure(fails)
            assertEquals(1, reads)
            assertEquals(2, snapshot.diagnostics.size)
            assertEquals(
                2,
                snapshot.diagnostics
                    .map(MinecraftFontDiagnostic::font)
                    .toSet()
                    .size,
            )
            assertEquals(setOf(FontJson.identifier("test:valid")), snapshot.fontIds)
            FontTestReferences.assertCollected(*references.toTypedArray())
            MinecraftFontEngine(snapshot, { FontTestBackend() }).use { engine ->
                assertEquals(3f, engine.glyph(FontJson.identifier("test:valid"), 'A'.code).advance)
            }
        }
    }

    private fun detachedRepeatedAssetFailure(fails: Boolean): Triple<MinecraftFontSnapshot, List<WeakReference<*>>, Int> {
        val asset = "assets/test/font/missing.ttf"
        val definition = """{"type":"ttf","file":"test:missing.ttf"}"""
        val delegate = FontTestResources.source(FontTestResources.font("default", definition), FontTestResources.font("test:other", definition), FontTestResources.font("test:valid", """{"type":"space","advances":{"A":3}}"""))
        val failure = IOException("selected font asset is unreadable")
        val failures = if (fails) mapOf(asset to failure) else emptyMap()
        val reads = HashMap<String, Int>()
        val source =
            object : MinecraftFontAssetSource by delegate {
                override fun read(path: String): ByteArray? {
                    reads[path] = reads.getOrDefault(path, 0) + 1
                    failures[path]?.let { throw it }
                    return delegate.read(path)
                }
            }
        val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility)
        return Triple(snapshot, listOf(WeakReference(source), WeakReference(failure)), reads.getValue(asset))
    }
}
