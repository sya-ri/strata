package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies launcher entry points and explicit read-only asset inputs without a loaded-game directory.
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
        val check = ShowcaseLaunchArguments.parse(ShowcaseLaunchFixture.arguments(temporaryRoot, ShowcaseStagingKind.Check), ShowcaseStagingKind.Check)
        val generate = ShowcaseLaunchArguments.parse(ShowcaseLaunchFixture.arguments(temporaryRoot, ShowcaseStagingKind.Generate), ShowcaseStagingKind.Generate)

        assertEquals(temporaryRoot.resolve("integration/docs/build/component-showcase/check"), check.stagingRoot)
        assertEquals(temporaryRoot.resolve("integration/docs/build/component-showcase/generate"), generate.stagingRoot)
        assertEquals(listOf(temporaryRoot.resolve("api/classes")), check.componentClassDirectories)
        assertEquals(temporaryRoot.resolve("inputs/client.jar"), check.inputs.clientJar)
        assertEquals(temporaryRoot.resolve("inputs/asset-index.json"), check.inputs.assetIndex)
        assertEquals(temporaryRoot.resolve("inputs/objects"), check.inputs.assetObjects)
        assertEquals(temporaryRoot.resolve("inputs/version.json"), check.inputs.versionManifest)
        assertEquals(temporaryRoot.resolve("inputs/inventory.png"), check.inputs.nativeInventoryPng)
        assertEquals(temporaryRoot.resolve("inputs/inventory.properties"), check.inputs.nativeInventoryReceipt)
        assertFalse(Files.exists(temporaryRoot.resolve("integration/minecraft-fabric-26.2/build/minecraft-parity")))
    }

    @Test
    fun parserRejectsAbsentOptionalOutputRootsAfterFiltering() {
        val args = ShowcaseLaunchFixture.arguments(temporaryRoot, ShowcaseStagingKind.Check)
        args[9] = temporaryRoot.resolve("api/absent").toString()

        assertThrows(IllegalArgumentException::class.java) {
            ShowcaseLaunchArguments.parse(args, ShowcaseStagingKind.Check)
        }
    }

    @Test
    fun parserAcceptsExternalReadInputsAndLeavesTheirContentsUnchanged() {
        val project = temporaryRoot.resolve("project")
        val inputs = temporaryRoot.resolve("external-assets")
        val args = ShowcaseLaunchFixture.arguments(project, ShowcaseStagingKind.Check, inputs)
        val before = Files.readAllBytes(inputs.resolve("client.jar"))

        val launch = ShowcaseLaunchArguments.parse(args, ShowcaseStagingKind.Check)

        assertEquals(inputs.resolve("client.jar"), launch.inputs.clientJar)
        assertTrue(before.contentEquals(Files.readAllBytes(inputs.resolve("client.jar"))))
        assertFalse(Files.exists(project.resolve("integration/minecraft-fabric-26.2")))
    }

    @Test
    fun parserRejectsMissingReadInputsLegacyArgumentsAndDuplicateClasses() {
        val args = ShowcaseLaunchFixture.arguments(temporaryRoot, ShowcaseStagingKind.Check)
        (3..8).forEach { index ->
            val missing = args.copyOf().also { values -> values[index] = temporaryRoot.resolve("absent-$index").toString() }
            assertThrows(IllegalArgumentException::class.java) { ShowcaseLaunchArguments.parse(missing, ShowcaseStagingKind.Check) }
        }
        assertThrows(IllegalArgumentException::class.java) { ShowcaseLaunchArguments.parse(args.take(5).toTypedArray(), ShowcaseStagingKind.Check) }
        assertThrows(IllegalArgumentException::class.java) { ShowcaseLaunchArguments.parse(args + args.last(), ShowcaseStagingKind.Check) }
    }

    @Test
    fun parserRejectsWrongInputTypesAndInputsWithinGeneratedDestinations() {
        val args = ShowcaseLaunchFixture.arguments(temporaryRoot, ShowcaseStagingKind.Check)
        val wrongType = args.copyOf().also { values -> values[3] = args[5] }
        assertThrows(IllegalArgumentException::class.java) { ShowcaseLaunchArguments.parse(wrongType, ShowcaseStagingKind.Check) }
        listOf(Path.of(args[2]).resolve("input.jar"), temporaryRoot.resolve("docs/components/input.jar")).forEach { input ->
            Files.createDirectories(input.parent)
            Files.writeString(input, "input")
            val overlapping = args.copyOf().also { values -> values[3] = input.toString() }
            assertThrows(IllegalArgumentException::class.java) { ShowcaseLaunchArguments.parse(overlapping, ShowcaseStagingKind.Check) }
        }
    }
}
