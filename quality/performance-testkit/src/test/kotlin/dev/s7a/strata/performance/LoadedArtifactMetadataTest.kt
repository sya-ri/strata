package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Real local archive/resource provenance, including shadowed resources and non-initializing discovery.
 */
class LoadedArtifactMetadataTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun hashesActualLoadedArchiveWithoutInitializingItsClass() {
        val jar = createJar()
        URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
            val evidence = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "fixture.Probe"), setOf("fixture"))
            LoadedArtifactMetadata.verifyComplete(evidence)
            assertNull(System.getProperty("strata.testkit.provenance.initialized"))
            val module = evidence.getAsJsonArray("modules").single().asJsonObject
            val tree = module.getAsJsonObject("classTree")
            assertEquals(1, tree.get("entryCount").asInt)
            assertEquals(ArtifactIdentity.file(jar), module.getAsJsonObject("codeSource").get("sha256").asString)
            val bytes = Files.readAllBytes(temporary.resolve("classes/fixture/Probe.class"))
            val text = "fixture/Probe.class=${sha256(bytes)}\n"
            assertEquals(sha256(text.toByteArray(Charsets.UTF_8)), tree.get("sha256").asString)
            val compatibility = JvmArtifactIdentity.capture(loader, listOf("fixture.Probe"))
            val legacy = compatibility.getAsJsonObject("strata_class_sha256").getAsJsonObject("fixture.Probe")
            assertEquals(sha256(bytes), legacy.getAsJsonObject("class_resource").get("sha256").asString)
            assertEquals(ArtifactIdentity.file(jar), compatibility.getAsJsonObject("strata_jar_sha256").get(jar.toString()).asString)
            assertNull(System.getProperty("strata.testkit.provenance.initialized"))
        }
    }

    @Test
    fun resourceFromAnotherArchiveCannotSatisfyRuntimeIdentity() {
        val jar = createJar()
        val other = temporary.resolve("shadow.jar")
        Files.copy(jar, other)
        val shadow = URL("jar:${other.toUri().toURL()}!/fixture/Probe.class")
        object : URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader) {
            override fun getResource(name: String): URL? = if (name.endsWith("Probe.class")) shadow else super.getResource(name)
        }.use { loader ->
            val evidence = LoadedArtifactMetadata.capture(loader, mapOf("fixture" to "fixture.Probe"), setOf("fixture"))
            assertFailsWith<IllegalStateException> { LoadedArtifactMetadata.verifyComplete(evidence) }
            assertFailsWith<IllegalStateException> { JvmArtifactIdentity.capture(loader, listOf("fixture.Probe")) }
        }
    }

    @Test
    fun missingModuleIsPreparationFailure() {
        val evidence = LoadedArtifactMetadata.capture(javaClass.classLoader, mapOf("fixture" to "fixture.MissingClass"), emptySet())
        assertFailsWith<IllegalStateException> { LoadedArtifactMetadata.verifyComplete(evidence) }
    }

    private fun createJar(): Path {
        val source = temporary.resolve("source/fixture/Probe.java")
        Files.createDirectories(checkNotNull(source.parent))
        Files.writeString(source, "package fixture; public final class Probe { static { System.setProperty(\"strata.testkit.provenance.initialized\", \"yes\"); } }")
        val classes = temporary.resolve("classes")
        Files.createDirectories(classes)
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()))
        val jar = temporary.resolve("measured.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { archive ->
            archive.putNextEntry(JarEntry("fixture/Probe.class"))
            archive.write(Files.readAllBytes(classes.resolve("fixture/Probe.class")))
            archive.closeEntry()
        }
        return jar
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
