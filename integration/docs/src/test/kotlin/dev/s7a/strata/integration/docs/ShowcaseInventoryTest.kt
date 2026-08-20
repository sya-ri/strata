package dev.s7a.strata.integration.docs

import dev.s7a.strata.dsl.UiScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.tools.ToolProvider

/**
 * Verifies API class-directory inventory discovery, filtering, ordering, and failure causes.
 */
internal class ShowcaseInventoryTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun realApiOutputDiscoversOnlyDocumentedComponents() {
        val classes = repositoryRoot().resolve("api/build/classes/kotlin/main")
        assertTrue(Files.isDirectory(classes))

        assertEquals(DocumentedComponent.entries.toSet(), ShowcaseInventory.discover(listOf(classes)))
    }

    @Test
    fun duplicateDirectoriesBinaryNamesAndUnsafeRootsAreRejected() {
        val classes = repositoryRoot().resolve("api/build/classes/kotlin/main")
        val duplicate =
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseInventory.discover(listOf(classes, classes))
            }
        assertTrue(duplicate.message.orEmpty().contains("duplicated"))

        val first = temporaryRoot.resolve("first")
        val second = temporaryRoot.resolve("second")
        copyClass(classes, first, "dev/s7a/strata/dsl/UiComponentsKt.class")
        copyClass(classes, second, "dev/s7a/strata/dsl/UiComponentsKt.class")
        val duplicateBinary =
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseInventory.discover(listOf(first, second))
            }
        assertTrue(duplicateBinary.message.orEmpty().contains("binary names"))

        val file = temporaryRoot.resolve("not-a-directory")
        Files.writeString(file, "file")
        assertThrows(IllegalArgumentException::class.java) { ShowcaseInventory.discover(listOf(file)) }

        val link = temporaryRoot.resolve("linked")
        val linkCreated = runCatching { Files.createSymbolicLink(link, classes) }.isSuccess
        if (linkCreated) {
            assertThrows(IllegalArgumentException::class.java) { ShowcaseInventory.discover(listOf(link)) }
        }
    }

    @Test
    fun classifiersExcludeNonStaticLowercasePrivateWrongParameterAndInstanceMethods() {
        val classes =
            compile(
                "fixture/Classifier.java",
                """
                package fixture;
                import dev.s7a.strata.dsl.UiScope;
                public final class Classifier {
                    public static void lowercase(UiScope scope) {}
                    public void Instance(UiScope scope) {}
                    private static void Private(UiScope scope) {}
                    public static void Wrong(String value) {}
                    public static void Array(UiScope[] value) {}
                    public static Object WrongReturn(UiScope scope) { return null; }
                }
                """.trimIndent(),
            )

        assertEquals(emptySet<DocumentedComponent>(), ShowcaseInventory.discover(listOf(classes)))
    }

    @Test
    fun unknownUpperCamelAndCorruptClassPreserveDeterministicFailureContext() {
        val unknown =
            compile(
                "fixture/Unknown.java",
                """
                package fixture;
                import dev.s7a.strata.dsl.UiScope;
                public final class Unknown {
                    public static void Unknown(UiScope scope) {}
                }
                """.trimIndent(),
            )
        val unknownFailure = assertThrows(IllegalArgumentException::class.java) { ShowcaseInventory.discover(listOf(unknown)) }
        assertTrue(unknownFailure.message.orEmpty().contains("undecoded"))

        val corrupt = temporaryRoot.resolve("corrupt")
        Files.createDirectories(corrupt.resolve("fixture"))
        Files.write(corrupt.resolve("fixture/Broken.class"), byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        val corruptFailure = assertThrows(IllegalStateException::class.java) { ShowcaseInventory.discover(listOf(corrupt)) }
        assertTrue(corruptFailure.message.orEmpty().contains("fixture.Broken"))
        assertTrue(corruptFailure.cause is LinkageError)
    }

    @Test
    fun overloadsAreGroupedWithoutInitializingFixtureAndDuplicateClassNamesStayStable() {
        val marker = temporaryRoot.resolve("initialized")
        val markerPath = marker.toString().replace('\\', '/')
        val classes =
            compile(
                "fixture/Overloads.java",
                """
                package fixture;
                import dev.s7a.strata.dsl.UiScope;
                public final class Overloads {
                    static { try { java.nio.file.Files.writeString(java.nio.file.Path.of("$markerPath"), "initialized"); } catch (Exception ignored) {} }
                    public static void Row(UiScope scope) {}
                    public static void Row(UiScope scope, int value) {}
                }
                """.trimIndent(),
            )

        assertEquals(setOf(DocumentedComponent.Row), ShowcaseInventory.discover(listOf(classes)))
        assertTrue(Files.exists(marker).not())
        val duplicateClass = temporaryRoot.resolve("duplicate")
        copyClass(classes, duplicateClass, "fixture/Overloads.class")
        val duplicate =
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseInventory.discover(listOf(classes, duplicateClass))
            }
        assertTrue(duplicate.message.orEmpty().contains("binary names"))
    }

    private fun repositoryRoot(): Path {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return if (Files.isDirectory(current.resolve("api/build/classes/kotlin/main"))) current else current.resolve("../..").normalize()
    }

    private fun copyClass(
        sourceRoot: Path,
        destinationRoot: Path,
        relative: String,
    ) {
        val source = sourceRoot.resolve(relative)
        val destination = destinationRoot.resolve(relative)
        Files.createDirectories(destination.parent)
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun compile(
        relative: String,
        source: String,
    ): Path {
        val sourceFile = temporaryRoot.resolve("source").resolve(relative)
        val output = temporaryRoot.resolve("compiled").resolve(relative.substringBeforeLast('/'))
        Files.createDirectories(sourceFile.parent)
        Files.createDirectories(output)
        Files.writeString(sourceFile, source)
        val compiler = ToolProvider.getSystemJavaCompiler() ?: throw IllegalStateException("A Java compiler is required for inventory fixtures.")
        val result =
            compiler.run(
                null,
                null,
                null,
                "-classpath",
                listOf(
                    System.getProperty("java.class.path"),
                    repositoryRoot().resolve("api/build/classes/kotlin/main").toString(),
                ).joinToString(File.pathSeparator),
                "-d",
                output.toString(),
                sourceFile.toString(),
            )
        assertEquals(0, result)
        return output
    }
}
