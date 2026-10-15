package dev.s7a.strata.performance

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Actual compiled directory origins, archive parity and fail-closed physical resource verification.
 */
internal class LoadedClassDirectoryTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun directoryAndArchiveKeepTheSameCompleteClassTreeWithoutInitialization() {
        val root = compileDirectory()
        val jar = archive(root)
        val directoryReport = capture(root)
        val archiveReport = capture(jar)
        val module = directoryReport.arrayField("modules").single().asJsonObject
        val origin = module.objectField("codeSource")
        assertEquals("class_directory", origin.textField("kind"))
        assertEquals(3, origin.countField("entryCount"))
        assertEquals(golden(root), origin.textField("sha256"))
        assertEquals(module.objectField("classTree"), archiveReport.arrayField("modules").single().asJsonObject.objectField("classTree"))
        val receipt = NativePerformanceEvidence.verify(wrap(archiveReport), wrap(directoryReport), setOf("directoryfixture.Probe"), emptySet()).single().asJsonObject
        assertEquals(origin.get("sha256"), receipt.get("cpu_class_directory_sha256"))
        assertNull(System.getProperty("strata.testkit.directory.initialized"))
        Files.delete(jar)
    }

    @Test
    fun directoryCodeSourceIsVerifiedWithoutARequestedClassTree() {
        val root = compileDirectory()
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val report = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "directoryfixture.Probe"), emptySet())
            LoadedArtifactMetadata.verifyComplete(report)
            val module = report.arrayField("modules").single().asJsonObject
            val origin = module.objectField("codeSource")
            assertTrue(module.has("classTree").not())
            assertEquals("class_directory", origin.textField("kind"))
            assertEquals("available", origin.textField("status"))
            assertEquals(3, origin.countField("entryCount"))
            assertEquals(golden(root), origin.textField("sha256"))
            assertEquals(hash(Files.readAllBytes(root.resolve("directoryfixture/Probe.class"))), module.objectField("classResource").textField("sha256"))
            assertNull(System.getProperty("strata.testkit.directory.initialized"))
        }
    }

    @Test
    fun pairedDirectoryAndResourceDotSegmentsCannotAliasCanonicalOrigins() {
        val root = compileDirectory()
        val report = capture(root)
        val native = wrap(capture(archive(root)))
        val prefix = root.toUri().toString().trimEnd('/')
        val resourceAlias = "$prefix/directoryfixture/../directoryfixture/Probe.class"
        listOf("codeSource" to "$prefix/directoryfixture/../", "classResource" to resourceAlias).forEach { (field, alias) ->
            val changed = report.deepCopy()
            val location = changed.arrayField("modules").single().asJsonObject.objectField(field)
            location.addProperty("url", alias)
            location.addProperty("hashedUrl", alias)
            assertFails { NativePerformanceEvidence.verify(native, wrap(changed), setOf("directoryfixture.Probe"), emptySet()) }
        }
        object : URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()) {
            override fun getResource(name: String): URL? = if (name == "directoryfixture/Probe.class") URL(resourceAlias) else super.getResource(name)
        }.use { loader ->
            val alias = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "directoryfixture.Probe"), emptySet())
            assertFailsWith<IllegalStateException> { LoadedArtifactMetadata.verifyComplete(alias) }
        }
    }

    @Test
    fun everyPhysicalClassAndInventoryChangeInvalidatesTheRecordedOrigin() {
        val root = compileDirectory()
        val original = capture(root)
        val jarReport = capture(archive(root))
        val other = root.resolve("directoryfixture/Other.class")
        val bytes = Files.readAllBytes(other)
        Files.write(other, bytes + byteArrayOf(1))
        assertNotEquals(original.arrayField("modules").single().asJsonObject.objectField("codeSource").get("sha256"), capture(root).arrayField("modules").single().asJsonObject.objectField("codeSource").get("sha256"))
        assertFails { NativePerformanceEvidence.verify(wrap(jarReport), wrap(original), setOf("directoryfixture.Probe"), emptySet()) }
        Files.write(other, bytes)
        val added = root.resolve("directoryfixture/Added.class")
        Files.write(added, bytes)
        assertFails { NativePerformanceEvidence.verify(wrap(jarReport), wrap(original), setOf("directoryfixture.Probe"), emptySet()) }
        Files.delete(added)
        Files.delete(other)
        assertFails { NativePerformanceEvidence.verify(wrap(jarReport), wrap(original), setOf("directoryfixture.Probe"), emptySet()) }
    }

    @Test
    fun declarationAndOriginTamperingCannotSatisfyDirectoryVerification() {
        val root = compileDirectory()
        val original = capture(root)
        val native = wrap(capture(archive(root)))
        val outside = temporary.resolve("outside.class")
        Files.copy(root.resolve("directoryfixture/Probe.class"), outside)
        val mutations = listOf<(JsonObject) -> Unit>(
            { it.objectField("codeSource").addProperty("kind", "file") },
            { it.objectField("codeSource").addProperty("status", "unavailable") },
            { it.objectField("codeSource").addProperty("hashedUrl", outside.toUri().toString()) },
            { it.objectField("codeSource").addProperty("algorithm", "sha256") },
            { it.objectField("codeSource").addProperty("entryCount", 1) },
            { it.objectField("codeSource").addProperty("bytes", 1) },
            { it.objectField("codeSource").addProperty("sha256", "0".repeat(64)) },
            { it.objectField("codeSource").addProperty("url", "https://example.invalid/classes") },
            { it.objectField("codeSource").addProperty("url", root.toUri().toString() + "?query=1") },
            { it.objectField("classResource").addProperty("url", outside.toUri().toString()) },
            { it.objectField("classResource").addProperty("status", "unavailable") },
            { it.objectField("classResource").addProperty("kind", "jar_entry") },
            { it.objectField("classResource").addProperty("hashedUrl", outside.toUri().toString()) },
            { it.objectField("classResource").addProperty("url", root.resolve("directoryfixture/Probe.class").toUri().toString() + "#fragment") },
        )
        mutations.forEach { mutate ->
            val changed = original.deepCopy()
            mutate(changed.arrayField("modules").single().asJsonObject)
            assertFails { NativePerformanceEvidence.verify(native, wrap(changed), setOf("directoryfixture.Probe"), emptySet()) }
        }
    }

    @Test
    fun anotherDirectoryOrForgedResourceBytesFailEvenWithoutRequestedClassTree() {
        val root = compileDirectory()
        val outside = temporary.resolve("outside.class")
        Files.copy(root.resolve("directoryfixture/Probe.class"), outside)
        listOf(outside.toUri().toURL(), URL("https://example.invalid/Probe.class")).forEach { shadow ->
            object : URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()) {
                override fun getResource(name: String): URL? = if (name == "directoryfixture/Probe.class") shadow else super.getResource(name)
            }.use { loader ->
                assertFailsWith<IllegalStateException> { LoadedArtifactMetadata.verifyComplete(LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "directoryfixture.Probe"), emptySet())) }
            }
        }
        observedLoader(root, "directoryfixture/Probe.class", mutableListOf(), forged = true).use { loader ->
            assertFailsWith<IllegalStateException> { LoadedArtifactMetadata.verifyComplete(LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "directoryfixture.Probe"), emptySet())) }
        }
    }

    @Test
    fun failedLoadedResourceReadClosesEveryOpenedStream() {
        val root = compileDirectory()
        val streams = mutableListOf<ObservedStream>()
        observedLoader(root, "directoryfixture/Other.class", streams).use { loader ->
            val report = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "directoryfixture.Probe"), setOf("fixture"))
            assertFailsWith<IllegalStateException> { LoadedArtifactMetadata.verifyComplete(report) }
            assertEquals("unavailable", report.arrayField("modules").single().asJsonObject.objectField("classTree").textField("status"))
        }
        assertTrue(streams.isNotEmpty() && streams.all { it.closed })
        Files.delete(root.resolve("directoryfixture/Other.class"))
    }

    @Test
    fun unsafeEmptyOversizedAndExcessiveDirectoryInventoriesFail() {
        val root = compileDirectory()
        assertFails { JvmEvidenceFiles.classTree(Files.createDirectory(temporary.resolve("empty"))) }
        assertFails { ClassArchiveInventory.entries(root, 1, 8L * 1024 * 1024) }
        val unsafe = root.resolve("unsafe=name.class")
        Files.write(unsafe, byteArrayOf(1))
        assertFails { JvmEvidenceFiles.classTree(root) }
        Files.delete(unsafe)
        val zero = root.resolve("Zero.class")
        Files.write(zero, byteArrayOf())
        assertFails { JvmEvidenceFiles.classTree(root) }
        Files.delete(zero)
        RandomAccessFile(root.resolve("Large.class").toFile(), "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        assertFails { JvmEvidenceFiles.classTree(root) }
        Files.delete(root.resolve("Large.class"))
        val total = Files.createDirectory(temporary.resolve("total"))
        repeat(9) { index -> RandomAccessFile(total.resolve("Class$index.class").toFile(), "rw").use { it.setLength(8L * 1024 * 1024) } }
        assertFails { JvmEvidenceFiles.classTree(total) }
    }

    @Test
    fun linkedRootsAncestorsAndEntriesCannotAliasClassDirectories() {
        val root = compileDirectory()
        val alias = temporary.resolve("alias")
        assumeTrue(runCatching { Files.createSymbolicLink(alias, root) }.isSuccess, "The test host must permit symbolic links")
        assertFails { JvmEvidenceFiles.classTree(alias) }
        val ancestor = temporary.resolve("ancestor")
        Files.createSymbolicLink(ancestor, temporary)
        assertFails { JvmEvidenceFiles.classTree(ancestor.resolve("classes")) }
        Files.createSymbolicLink(root.resolve("Escape.class"), root.resolve("directoryfixture/Probe.class"))
        assertFails { JvmEvidenceFiles.classTree(root) }
    }

    @Test
    fun realArchivesRejectDirectoryDeclarationsAndSameByteResourceShadows() {
        val root = compileDirectory()
        val jar = archive(root)
        val report = capture(jar)
        assertEquals(1, NativePerformanceEvidence.verify(wrap(report), wrap(report), setOf("directoryfixture.Probe"), emptySet()).size())
        listOf("class_directory", "unknown").forEach { kind ->
            val changed = report.deepCopy()
            changed.arrayField("modules").single().asJsonObject.objectField("codeSource").addProperty("kind", kind)
            assertFails { NativePerformanceEvidence.verify(wrap(changed), wrap(report), setOf("directoryfixture.Probe"), emptySet()) }
            assertFails { NativePerformanceEvidence.verify(wrap(report), wrap(changed), setOf("directoryfixture.Probe"), emptySet()) }
        }
        val other = temporary.resolve("same-bytes.jar")
        Files.copy(jar, other)
        val shadow = URL("jar:${other.toUri().toURL()}!/directoryfixture/Probe.class")
        object : URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()) {
            override fun getResource(name: String): URL? = if (name == "directoryfixture/Probe.class") shadow else super.getResource(name)
        }.use { loader ->
            val shadowed = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "directoryfixture.Probe"), setOf("fixture"))
            assertFailsWith<IllegalStateException> { LoadedArtifactMetadata.verifyComplete(shadowed) }
        }
        Files.delete(other)
        Files.delete(jar)
    }

    private fun capture(path: Path): JsonObject =
        URLClassLoader(arrayOf(path.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "directoryfixture.Probe"), setOf("fixture")).also(LoadedArtifactMetadata::verifyComplete)
        }

    private fun wrap(report: JsonObject): JsonObject = JsonObject().apply { add("strata", report) }

    private fun compileDirectory(): Path {
        val source = temporary.resolve("Probe.java")
        Files.writeString(source, "package directoryfixture; public final class Probe { static { System.setProperty(\"strata.testkit.directory.initialized\", \"yes\"); } public static class Nested {} } class Other {}")
        val root = Files.createDirectory(temporary.resolve("classes"))
        assertEquals(0, checkNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null, "-d", root.toString(), source.toString()))
        return root
    }

    private fun archive(root: Path): Path {
        val jar = temporary.resolve("runtime.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                    output.putNextEntry(JarEntry(root.relativize(file).joinToString("/")))
                    Files.copy(file, output)
                    output.closeEntry()
                }
            }
        }
        return jar
    }

    private fun golden(root: Path): String {
        val text =
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .sorted()
                    .map { "${root.relativize(it).joinToString("/")}=${hash(Files.readAllBytes(it))}\n" }
                    .toList()
                    .joinToString("")
            }
        return hash(text.toByteArray(Charsets.UTF_8))
    }

    private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun observedLoader(
        root: Path,
        selected: String,
        streams: MutableList<ObservedStream>,
        forged: Boolean = false,
    ): URLClassLoader =
        object : URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()) {
            override fun getResource(name: String): URL? {
                val actual = super.getResource(name) ?: return null
                return URL(
                    null,
                    actual.toExternalForm(),
                    object : URLStreamHandler() {
                        override fun openConnection(url: URL): URLConnection =
                            object : URLConnection(url) {
                                override fun connect() = Unit

                                override fun getInputStream(): InputStream {
                                    val input = if (forged && name == selected) byteArrayOf(1).inputStream() else actual.openStream()
                                    return ObservedStream(input, name == selected && forged.not()).also(streams::add)
                                }
                            }
                    },
                )
            }
        }

    /**
     * Records closure of the actual resource streams, including a deterministic failing read.
     */
    private class ObservedStream(input: InputStream, private val fail: Boolean) : FilterInputStream(input) {
        var closed = false

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (fail) throw IOException("Expected directory resource read failure")
            return `in`.read(buffer, offset, length)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
