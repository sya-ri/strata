package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies launcher entry points and the typed four-group argument contract.
 */
internal class ShowcaseLauncherContractTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun launchersExposeExactPublicStaticMain() {
        listOf(ComponentShowcaseChecker::class.java, ComponentShowcaseGenerator::class.java).forEach { type ->
            val methods =
                type.declaredMethods.filter { method ->
                    Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers) && method.isSynthetic.not()
                }
            assertEquals(1, methods.size)
            val method = methods.single()
            assertEquals("main", method.name)
            assertEquals(listOf(Array<String>::class.java), method.parameterTypes.toList())
            assertEquals(Void.TYPE, method.returnType)
            assertTrue(Modifier.isPublic(method.modifiers))
            assertTrue(Modifier.isStatic(method.modifiers))
            assertTrue(method.isSynthetic.not())
        }
    }

    @Test
    fun parserAcceptsEachTypedStagingKindWithOneExistingOutputRoot() {
        val moduleBuild = temporaryRoot.resolve("integration/docs/build")
        val check = moduleBuild.resolve("component-showcase/check")
        val generate = moduleBuild.resolve("component-showcase/generate")
        val classes = temporaryRoot.resolve("api/classes")
        Files.createDirectories(check)
        Files.createDirectories(generate)
        Files.createDirectories(classes)

        val checkArguments = ShowcaseLaunchArguments.parse(arguments(moduleBuild, check, classes), ShowcaseStagingKind.Check)
        val generateArguments = ShowcaseLaunchArguments.parse(arguments(moduleBuild, generate, classes), ShowcaseStagingKind.Generate)

        assertEquals(check, checkArguments.stagingRoot)
        assertEquals(generate, generateArguments.stagingRoot)
        assertEquals(listOf(classes), checkArguments.apiClassDirectories)
    }

    @Test
    fun parserRejectsAbsentOptionalOutputRootsAfterFiltering() {
        val moduleBuild = temporaryRoot.resolve("integration/docs/build")
        val check = moduleBuild.resolve("component-showcase/check")
        Files.createDirectories(check)
        val absent = temporaryRoot.resolve("api/absent")

        assertThrows(IllegalArgumentException::class.java) {
            ShowcaseLaunchArguments.parse(arguments(moduleBuild, check, absent), ShowcaseStagingKind.Check)
        }
    }

    private fun arguments(
        moduleBuild: Path,
        staging: Path,
        classes: Path,
    ): Array<String> = arrayOf(temporaryRoot.toString(), moduleBuild.toString(), staging.toString(), classes.toString())
}
