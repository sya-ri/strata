package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.JarURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Observes actual public loaded-resource traversal at the stream boundary without replacing the hashing implementation.
 * Fixture classes are compiled by the JDK and resolved without initialization from their real local archive.
 */
internal class LoadedClassTreeScratchTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun oneTreeOwnsOneScratchAcrossRealCompiledClassResourcesAndShortReads() {
        val entries = compiledEntries()
        val jar = archive(entries)
        val streams = mutableListOf<ObservedStream>()
        observedLoader(jar, streams).use { loader ->
            val report = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "fixture.Probe8191"), setOf("fixture"))
            LoadedArtifactMetadata.verifyComplete(report)
            val module = report.getAsJsonArray("modules").single().asJsonObject
            val tree = module.getAsJsonObject("classTree")
            assertEquals(entries.size, tree.get("entryCount").asInt)
            assertEquals(entries.values.sumOf { it.size }.toLong(), tree.get("bytes").asLong)
            assertEquals(treeGolden(entries), tree.get("sha256").asString)
            assertEquals(ArtifactIdentity.file(jar), module.getAsJsonObject("codeSource").get("sha256").asString)
            val resources = streams.drop(1)
            assertEquals(entries.keys.sorted(), resources.map { it.entry })
            assertTrue(streams.all { it.closed })
            assertEquals(entries.values.sumOf { it.size }, resources.sumOf { it.bytes })
            val buffers = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())
            resources.forEach { buffers.addAll(it.buffers) }
            assertEquals(1, buffers.size)
            assertEquals(DEFAULT_BUFFER_SIZE, buffers.single().size)
            assertNotSame(streams.first().buffers.first(), buffers.single())
        }
        Files.delete(jar)
    }

    @Test
    fun independentPublicTraversalsRetainTheSameBytesAndSeparateScratch() {
        val entries = compiledEntries()
        val jar = archive(entries)
        val first = mutableListOf<ObservedStream>()
        val second = mutableListOf<ObservedStream>()
        listOf(first, second).forEach { streams ->
            observedLoader(jar, streams).use { loader ->
                val report = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "fixture.Probe8191"), setOf("fixture"))
                LoadedArtifactMetadata.verifyComplete(report)
                assertEquals(treeGolden(entries), report.getAsJsonArray("modules").single().asJsonObject.getAsJsonObject("classTree").get("sha256").asString)
            }
        }
        assertNotSame(first[1].buffers.first(), second[1].buffers.first())
        assertTrue((first + second).all { it.closed })
    }

    @Test
    fun realResourceReadFailureClosesTheStreamAndRetainsUnavailableTree() {
        val entries = compiledEntries()
        val jar = archive(entries)
        val streams = mutableListOf<ObservedStream>()
        observedLoader(jar, streams, "fixture/Probe8192.class").use { loader ->
            val report = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "fixture.Probe8191"), setOf("fixture"))
            assertFailsWith<IllegalStateException> { LoadedArtifactMetadata.verifyComplete(report) }
            val tree = report.getAsJsonArray("modules").single().asJsonObject.getAsJsonObject("classTree")
            assertEquals("unavailable", tree.get("status").asString)
            assertTrue(tree.get("sha256").isJsonNull)
            assertTrue(tree.get("reason").asString.contains("Expected actual class-resource read failure"))
            assertTrue(streams.all { it.closed })
            assertTrue(streams.none { it.entry == "fixture/Probe8193.class" })
        }
        Files.delete(jar)
    }

    @Test
    fun emptyArchiveClassCannotBecomeAvailableLoadedTree() {
        val entries = compiledEntries() + ("fixture/Zero.class" to byteArrayOf())
        val jar = archive(entries)
        val streams = mutableListOf<ObservedStream>()
        observedLoader(jar, streams).use { loader ->
            val report = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "fixture.Probe8191"), setOf("fixture"))
            assertFailsWith<IllegalStateException> { LoadedArtifactMetadata.verifyComplete(report) }
            val tree = report.getAsJsonArray("modules").single().asJsonObject.getAsJsonObject("classTree")
            assertTrue(tree.get("sha256").isJsonNull)
            assertTrue(tree.get("reason").asString.contains("Empty classpath resource"))
            assertTrue(streams.all { it.closed })
        }
    }

    @Test
    fun aNestedPublicCaptureCannotReuseItsOuterScratch() {
        val entries = compiledEntries()
        val jar = archive(entries)
        val outer = mutableListOf<ObservedStream>()
        val nested = mutableListOf<ObservedStream>()
        observedLoader(jar, nested).use { nestedLoader ->
            var captured = false
            observedLoader(jar, outer, onRead = {
                if (captured.not()) {
                    captured = true
                    val report = LoadedArtifactMetadata.capture(nestedLoader, mapOf("fixture" to "fixture.Probe8191"), setOf("fixture"))
                    LoadedArtifactMetadata.verifyComplete(report)
                    assertEquals(treeGolden(entries), report.getAsJsonArray("modules").single().asJsonObject.getAsJsonObject("classTree").get("sha256").asString)
                }
            }).use { loader ->
                val report = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "fixture.Probe8191"), setOf("fixture"))
                LoadedArtifactMetadata.verifyComplete(report)
                assertEquals(treeGolden(entries), report.getAsJsonArray("modules").single().asJsonObject.getAsJsonObject("classTree").get("sha256").asString)
            }
        }
        assertTrue(capturedStreamsAreClosed(outer, nested))
        assertNotSame(outer[1].buffers.first(), nested[1].buffers.first())
        Files.delete(jar)
    }

    @Test
    fun simultaneousPublicCapturesOwnSeparateScratchAndCompleteBytes() {
        val entries = compiledEntries()
        val jar = archive(entries)
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(List(2) {
                Callable {
                    val streams = mutableListOf<ObservedStream>()
                    var first = true
                    observedLoader(jar, streams, onRead = {
                        if (first) {
                            first = false
                            barrier.await(30, TimeUnit.SECONDS)
                        }
                    }).use { loader ->
                        val report = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "fixture.Probe8191"), setOf("fixture"))
                        LoadedArtifactMetadata.verifyComplete(report)
                        assertEquals(treeGolden(entries), report.getAsJsonArray("modules").single().asJsonObject.getAsJsonObject("classTree").get("sha256").asString)
                    }
                    streams
                }
            }).map { it.get() }
            assertTrue(capturedStreamsAreClosed(results[0], results[1]))
            assertNotSame(results[0][1].buffers.first(), results[1][1].buffers.first())
        } finally {
            executor.shutdownNow()
        }
        Files.delete(jar)
    }

    private fun capturedStreamsAreClosed(first: List<ObservedStream>, second: List<ObservedStream>): Boolean =
        (first + second).all { it.closed }
    private fun observedLoader(jar: Path, streams: MutableList<ObservedStream>, failingEntry: String? = null, onRead: (() -> Unit)? = null): URLClassLoader =
        object : URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()) {
            override fun getResource(name: String): URL? {
                val actual = super.getResource(name) ?: return null
                return URL(null, actual.toExternalForm(), object : URLStreamHandler() {
                    override fun openConnection(url: URL): URLConnection =
                        object : JarURLConnection(url) {
                            private val delegate = actual.openConnection() as JarURLConnection

                            override fun connect() = delegate.connect()

                            override fun getJarFile(): JarFile = delegate.jarFile

                            override fun getInputStream(): InputStream {
                                delegate.useCaches = false
                                return ObservedStream(name, delegate.inputStream, name == failingEntry, onRead).also(streams::add)
                            }
                        }
                })
            }
        }

    private fun compiledEntries(): Map<String, ByteArray> {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        val classes = directory.resolve("classes")
        Files.createDirectories(classes)
        return listOf(8191, 8192, 8193).associate { target ->
            val name = "Probe$target"
            val source = directory.resolve("$name.java")
            fun compile(padding: Int): ByteArray {
                Files.writeString(source, "package fixture; public final class $name { public static final String PAD = \"${"a".repeat(padding)}\"; }")
                assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()))
                return Files.readAllBytes(classes.resolve("fixture/$name.class"))
            }
            val initial = compile(0)
            val bytes = compile(target - initial.size)
            assertEquals(target, bytes.size)
            "fixture/$name.class" to bytes
        }
    }

    private fun archive(entries: Map<String, ByteArray>): Path {
        val path = directory.resolve("measured.jar")
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(JarEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return path
    }

    private fun treeGolden(entries: Map<String, ByteArray>): String {
        val text = entries.toSortedMap().entries.joinToString("") { (name, bytes) -> "$name=${hash(bytes)}\n" }
        return hash(text.toByteArray(Charsets.UTF_8))
    }

    private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Records only actual stream calls, scratch identities and closure, never production implementation state.
     */
    private class ObservedStream(val entry: String, input: InputStream, private val fail: Boolean, private val onRead: (() -> Unit)? = null) : FilterInputStream(input) {
        val buffers = mutableListOf<ByteArray>()
        var bytes = 0
        var closed = false

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            onRead?.invoke()
            buffers.add(buffer)
            if (fail) throw IOException("Expected actual class-resource read failure")
            val count = `in`.read(buffer, offset, minOf(length, 113))
            if (0 < count) bytes += count
            return count
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
