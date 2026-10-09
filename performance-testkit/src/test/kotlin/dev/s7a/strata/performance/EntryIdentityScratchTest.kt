package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Independent framing goldens and actual read-buffer observations preserve entry bytes, stream ownership and invocation isolation.
 */
internal class EntryIdentityScratchTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun publicDirectoryAndSingleFileHashesKeepIndependentGoldenFraming() {
        val entries = linkedMapOf(
            "assets/z.txt" to ByteArray(8193) { it.toByte() },
            "assets/a.txt" to byteArrayOf(),
            "data/short.txt" to byteArrayOf(1, 2, 3),
            "assets/middle.txt" to ByteArray(8191) { (it * 7).toByte() },
            "data/equal.txt" to ByteArray(8192) { (it * 11).toByte() },
        )
        entries.forEach { (name, bytes) ->
            val path = directory.resolve(name)
            Files.createDirectories(checkNotNull(path.parent))
            Files.write(path, bytes)
        }
        assertEquals(framed(entries), ArtifactIdentity.tree(directory))
        val file = directory.resolve("abc.bin")
        Files.writeString(file, "abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ArtifactIdentity.file(file))
        assertEquals(entries.size, entries.count { Files.size(directory.resolve(it.key)) == it.value.size.toLong() })
    }

    @Test
    fun unicodeEntryFramingSelectionAndArchiveOrderKeepExactIdentity() {
        val selected = linkedMapOf(
            "assets/marker.txt" to byteArrayOf(),
            "assets/.txt" to ByteArray(8191) { it.toByte() },
            "assets/𐀀.txt" to ByteArray(8193) { (it * 3).toByte() },
            "fixture/Probe.class" to byteArrayOf(4, 5, 6),
            "data/日本.json" to ByteArray(8192) { (it * 7).toByte() },
        )
        val entries = selected + ("README.md" to byteArrayOf(99)) + ("META-INF/ignored.json" to byteArrayOf(17))
        entries.forEach { (name, bytes) ->
            val path = directory.resolve(name)
            Files.createDirectories(checkNotNull(path.parent))
            Files.write(path, bytes)
        }
        val golden = framed(selected)
        assertEquals(golden, EntryIdentity.directorySha256(directory, EntryIdentity::selectedEntry))
        listOf(entries.entries.toList(), entries.entries.reversed()).forEachIndexed { index, ordered ->
            val archive = directory.resolve("ordered-$index.jar")
            JarOutputStream(Files.newOutputStream(archive)).use { output ->
                ordered.forEach { (name, bytes) ->
                    output.putNextEntry(JarEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            assertEquals(golden, EntryIdentity.zipSha256(archive, EntryIdentity::selectedEntry))
            Files.delete(archive)
        }
        Files.writeString(directory.resolve("README.md"), "Changed excluded metadata")
        assertEquals(golden, EntryIdentity.directorySha256(directory, EntryIdentity::selectedEntry))
        assertEquals(framed(selected.filterKeys(EntryIdentity::selectedResourceEntry)), ArtifactIdentity.resourceTree(directory, "assets/marker.txt").getValue("sha256"))
    }

    @Test
    fun aCompleteTraversalReusesOnlyItsOwnBufferAndClosesEveryShortStream() {
        val entries = mixedEntries()
        val streams = mutableListOf<ObservedStream>()
        val result = hash(entries.keys.sorted()) { name -> ObservedStream(entries.getValue(name)).also(streams::add) }
        assertEquals(framed(entries), result)
        assertEquals(entries.values.sumOf { it.size }, streams.sumOf { it.bytes })
        assertEquals(entries.size, streams.size)
        assertTrue(streams.all { it.closed })
        val buffers = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())
        streams.forEach { buffers.addAll(it.buffers) }
        assertEquals(1, buffers.size)
        assertTrue(buffers.all { it.size == DEFAULT_BUFFER_SIZE })
    }

    @Test
    fun nestedTraversalsCannotBorrowTheOuterScratch() {
        val entries = mixedEntries()
        val outer = mutableListOf<ObservedStream>()
        val nested = mutableListOf<ObservedStream>()
        var nestedResult: String? = null
        val result = hash(entries.keys.sorted()) { name ->
            if (nestedResult == null) {
                nestedResult = hash(entries.keys.sorted()) { nestedName -> ObservedStream(entries.getValue(nestedName)).also(nested::add) }
            }
            ObservedStream(entries.getValue(name)).also(outer::add)
        }
        assertEquals(framed(entries), result)
        assertEquals(result, nestedResult)
        assertNotSame(outer.first().buffers.first(), nested.first().buffers.first())
        assertTrue((outer + nested).all { it.closed })
    }

    @Test
    fun simultaneousTraversalsKeepTheirExactBytes() {
        val barrier = CyclicBarrier(2)
        val left = mixedEntries()
        val right = left.mapValues { (_, bytes) -> bytes.map { (it.toInt() xor 127).toByte() }.toByteArray() }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(listOf(left, right).map { entries ->
                Callable {
                    var first = true
                    hash(entries.keys.sorted()) { name ->
                        if (first) {
                            first = false
                            barrier.await()
                        }
                        ObservedStream(entries.getValue(name))
                    }
                }
            })
            assertEquals(listOf(framed(left), framed(right)), results.map { it.get() })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aThrowingEntryClosesOpenedStreamsAndPreservesItsOriginalFailure() {
        val failure = IOException("read failed after a partial entry")
        val streams = mutableListOf<ObservedStream>()
        val caught = assertFailsWith<IOException> {
            hash(listOf("first", "second", "third")) { name ->
                ObservedStream(ByteArray(8193), if (name.contentEquals("second")) failure else null).also(streams::add)
            }
        }
        assertSame(failure, caught)
        assertEquals(2, streams.size)
        assertTrue(streams.all { it.closed })
    }

    @Test
    fun anEmptyTraversalKeepsTheGoldenWithoutOpeningAStream() {
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, ArtifactIdentity.tree(directory))
        assertEquals(expected, hash(emptyList()) { error("An empty traversal must not open a stream") })
    }
    private fun mixedEntries(): Map<String, ByteArray> =
        listOf(0, 1, 7, 8191, 8192, 8193, 32_769).mapIndexed { index, size -> "entry-$index" to ByteArray(size) { (it * 31 + index).toByte() } }.toMap()

    // Observe the actual private traversal's stream boundary; no helper implementation or buffer field is copied.
    private fun hash(entries: List<String>, open: (String) -> InputStream): String {
        val method = EntryIdentity.javaClass.declaredMethods.single { it.name.contentEquals("hashEntries") && it.parameterCount == 2 }.apply { isAccessible = true }
        return try {
            method.invoke(EntryIdentity, entries, open) as String
        } catch (failure: InvocationTargetException) {
            throw checkNotNull(failure.cause)
        }
    }

    private fun framed(entries: Map<String, ByteArray>): String {
        val bytes = ByteArrayOutputStream()
        entries.toSortedMap().forEach { (name, content) ->
            val encoded = name.toByteArray(Charsets.UTF_8)
            bytes.write("${encoded.size}:".toByteArray(Charsets.US_ASCII))
            bytes.write(encoded)
            bytes.write(0)
            bytes.write(content)
            bytes.write(255)
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
    }

    /**
     * Exposes positive short reads, an exact callback failure and the actual caller buffers without changing their bytes.
     */
    private class ObservedStream(content: ByteArray, private val failure: IOException? = null) : InputStream() {
        private val input = ByteArrayInputStream(content)
        val buffers = mutableListOf<ByteArray>()
        var bytes = 0
            private set
        var closed = false
            private set

        override fun read(): Int = input.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (0 < bytes) failure?.let { throw it }
            buffers.add(buffer)
            val read = input.read(buffer, offset, minOf(length, 113))
            if (0 < read) bytes += read
            return read
        }

        override fun close() {
            closed = true
            input.close()
        }
    }
}
