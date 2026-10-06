package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises actual compiled archives rather than a hand-written inventory fixture.
 */
class JvmApiInventoryTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun discoversOverloadsAndNestedDeclarationsWithoutInitialization() {
        val jar = createJar("base", "")
        URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
            val surface = JvmApiInventory.capture(loader, mapOf("fixture" to "inventory.Probe")).getValue("fixture")
            assertTrue(surface.any { "#method:1:draw:(I)V" in it })
            assertTrue(surface.any { "#method:1:draw:(J)V" in it })
            assertTrue(surface.any { "#field:4:count:I" in it })
            assertTrue(surface.any { it.startsWith("inventory.Probe\$Nested#type:") })
            assertTrue(surface.none { "Hidden" in it || "privateValue" in it })
            assertNull(System.getProperty("strata.testkit.inventory.initialized"))
        }
    }

    @Test
    fun aRealApiAdditionFailsItsExistingFeatureAssignment() {
        val baseline = createJar("baseline", "")
        val candidate = createJar("candidate", "public void added() {}")
        val representatives = mapOf("fixture" to "inventory.Probe")
        val oldSurface = URLClassLoader(arrayOf(baseline.toUri().toURL()), javaClass.classLoader).use { JvmApiInventory.capture(it, representatives) }
        URLClassLoader(arrayOf(candidate.toUri().toURL()), javaClass.classLoader).use { loader ->
            val newSurface = JvmApiInventory.capture(loader, representatives)
            val assignments = oldSurface.mapValues { (_, symbols) -> symbols.associateWith { "draw" } }
            assertFailsWith<IllegalArgumentException> {
                PerformanceInventory(newSurface, assignments, mapOf("api/" to setOf("draw"))).verify(setOf("draw"))
            }
        }
    }

    @Test
    fun componentDiscoveryUsesTheExactReceiverReturnTypeAndStaticBoundary() {
        val jar = createJar("components", "public static void Widget(Probe receiver) {} public static void lower(Probe receiver) {} public static int Wrong(Probe receiver) { return 1; }")
        URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
            val components = JvmApiInventory.componentEntryPoints(loader, mapOf("fixture" to "inventory.Probe"), "inventory.Probe").getValue("fixture")
            assertEquals(1, components.size)
            assertTrue(components.single().contains(":Widget:(Linventory/Probe;)V"))
            assertNull(System.getProperty("strata.testkit.inventory.initialized"))
        }
    }

    private fun createJar(
        directoryName: String,
        additionalMethod: String,
    ): Path {
        val directory = temporary.resolve(directoryName)
        Files.createDirectories(directory)
        val source = directory.resolve("Probe.java")
        Files.writeString(
            source,
            "package inventory; public class Probe { " +
                "static { System.setProperty(\"strata.testkit.inventory.initialized\", \"yes\"); } " +
                "public void draw(int value) {} public void draw(long value) {} protected int count; " +
                "private int privateValue; public static class Nested {} private static class Hidden {} " + additionalMethod + " }",
        )
        val classes = directory.resolve("classes")
        Files.createDirectories(classes)
        assertEquals(0, checkNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null, "-d", classes.toString(), source.toString()))
        val jar = directory.resolve("actual-api.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { archive ->
            Files.walk(classes).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { path ->
                    archive.putNextEntry(JarEntry(classes.relativize(path).toString().replace('\\', '/')))
                    archive.write(Files.readAllBytes(path))
                    archive.closeEntry()
                }
            }
        }
        return jar
    }
}
