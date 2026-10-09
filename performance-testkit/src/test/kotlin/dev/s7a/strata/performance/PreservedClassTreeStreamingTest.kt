package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Independent preserved-entry digests retain unsigned UTF-8 framing, empty-byte support and exact streaming bounds.
 * Raw entry contents exercise hashing admission, without claiming that these detached inputs are loaded executable classes.
 */
internal class PreservedClassTreeStreamingTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun exactBytesAndCanonicalUnicodeOrderMatchAnIndependentGolden() {
        val entries = linkedMapOf(
            "fixture/Empty.class" to byteArrayOf(),
            "fixture/Short.class" to byteArrayOf(0, 1, -1),
            "fixture/Wide.class" to ByteArray(8193) { (it * 17).toByte() },
            "fixture/.class" to ByteArray(8191) { it.toByte() },
            "fixture/𐀀.class" to ByteArray(8192) { (it * 7).toByte() },
        )
        val path = write("mixed.jar", entries.entries.reversed().associate { it.key to it.value })
        val report = JvmEvidenceFiles.classTree(path)
        assertEquals(entries.size, report.countField("entryCount"))
        assertEquals(entries.values.sumOf { it.size.toLong() }, report.get("bytes").asLong)
        assertEquals(golden(entries), report.textField("sha256"))
        val moved = directory.resolve("moved.jar")
        Files.move(path, moved)
        assertEquals(report, JvmEvidenceFiles.classTree(moved))
    }

    @Test
    fun exactClassAndTotalTreeCapsRemainValidAndTheNextByteFails() {
        val maximum = 8 * 1024 * 1024
        val content = ByteArray(maximum) { (it * 13).toByte() }
        val entries = (0 until 8).associate { "fixture/Entry$it.class" to content }
        val exact = JvmEvidenceFiles.classTree(write("exact.jar", entries))
        assertEquals(64L * 1024 * 1024, exact.get("bytes").asLong)
        assertEquals(golden(entries), exact.textField("sha256"))
        val oversizedTree = write("tree-too-large.jar", entries + ("fixture/Last.class" to byteArrayOf(1)))
        assertFailsWith<IllegalArgumentException> { JvmEvidenceFiles.classTree(oversizedTree) }
        val oversizedEntry = write("entry-too-large.jar", mapOf("fixture/Probe.class" to ByteArray(maximum + 1)))
        assertFailsWith<IllegalArgumentException> { JvmEvidenceFiles.classTree(oversizedEntry) }
    }

    @Test
    fun exactEntryCountRetainsItsGateBeforeAnExtraClassCanBeAdmitted() {
        val entries = (0 until 16_384).associate { "fixture/Entry$it.class" to byteArrayOf() }
        val report = JvmEvidenceFiles.classTree(write("entry-count.jar", entries))
        assertEquals(16_384, report.countField("entryCount"))
        val excessive = write("entry-count-too-large.jar", entries + ("fixture/Last.class" to byteArrayOf()))
        assertFailsWith<IllegalArgumentException> { JvmEvidenceFiles.classTree(excessive) }
    }

    @Test
    fun everyShortReadCountsAndDeclaredSizeMismatchCannotReturnADigest() {
        val bytes = ByteArray(8193) { (it * 19).toByte() }
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 101))
        }
        assertEquals(sha256(bytes), hash(input, bytes.size.toLong()))
        listOf(-1L, bytes.size - 1L, bytes.size + 1L).forEach { declared ->
            assertFailsWith<IllegalArgumentException> { hash(ByteArrayInputStream(bytes), declared) }
        }
    }

    @Test
    fun aDecompressedStreamReadsAtMostTheOriginalMaximumPlusOneGuard() {
        val maximum = 8 * 1024 * 1024
        val input = CountingInput(maximum.toLong() + 100)
        assertFailsWith<IllegalArgumentException> { hash(input, maximum.toLong()) }
        assertEquals(maximum + 1L, input.bytes)
    }

    @Test
    fun aReadFailurePropagatesItsExactCauseAndTruncatedArchivesStayRejected() {
        val failure = IOException("short archive read failed")
        val input = object : InputStream() {
            override fun read(): Int = throw failure

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
        }
        assertSame(failure, assertFailsWith<IOException> { hash(input, 1) })
        val path = write("truncated.jar", mapOf("fixture/Probe.class" to byteArrayOf(1, 2, 3)))
        val complete = Files.readAllBytes(path)
        Files.write(path, complete.copyOf(complete.size / 2))
        assertFailsWith<IOException> { JvmEvidenceFiles.classTree(path) }
        Files.delete(path)
    }

    private fun write(name: String, entries: Map<String, ByteArray>): Path =
        directory.resolve(name).also { path ->
            ZipOutputStream(Files.newOutputStream(path)).use { archive ->
                entries.forEach { (entry, bytes) ->
                    archive.putNextEntry(ZipEntry(entry))
                    archive.write(bytes)
                    archive.closeEntry()
                }
            }
        }

    // Force short, mismatched and oversized stream contracts through the actual private streaming boundary.
    private fun hash(input: InputStream, expected: Long): String {
        val method = JvmEvidenceFiles.javaClass.declaredMethods.single { it.name.contentEquals("classHash") }.apply { isAccessible = true }
        return try {
            method.invoke(JvmEvidenceFiles, input, expected, ByteArray(DEFAULT_BUFFER_SIZE)) as String
        } catch (failure: InvocationTargetException) {
            throw checkNotNull(failure.cause)
        }
    }

    private fun golden(entries: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // These test names are declared in their independent expected unsigned UTF-8 order.
        entries.forEach { (name, bytes) -> digest.update("$name=${sha256(bytes)}\n".toByteArray(Charsets.UTF_8)) }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Generates bytes without a class-sized backing array and records the exact decompressed read bound.
     */
    private class CountingInput(private val size: Long) : InputStream() {
        var bytes = 0L
            private set

        override fun read(): Int {
            if (bytes == size) return -1
            bytes++
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytes == size) return -1
            val count = minOf(length.toLong(), size - bytes).toInt()
            buffer.fill(0, offset, offset + count)
            bytes += count
            return count
        }
    }
}
