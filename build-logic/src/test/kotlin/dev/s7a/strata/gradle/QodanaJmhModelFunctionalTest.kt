package dev.s7a.strata.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs production compiler exports and declaration inventory against working, Minecraft-independent owners.
 */
internal class QodanaJmhModelFunctionalTest {
    @TempDir
    lateinit var directory: Path

    private val repository =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("gradle/libs.versions.toml")) }

    @Test
    fun `discover custom working owners and retain all declared inputs and dependency mappings`() {
        val fixture = QodanaCompilerFixture(directory, repository)
        fixture.prepare()
        fixture.commit("Create working JMH owners", ".")
        val result = fixture.runner().build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":qodanaDeclarationInventory")?.outcome)
        val evidence = QodanaCompilerModelAssertions(directory, repository)
        val inventory = evidence.verify(result, fixture.owners)
        assertReusedExport(fixture, inventory)
        assertFreshOutput(fixture)
        assertMissingDependency(fixture)
        assertBrokenAuxiliary(fixture)
    }

    /**
     * Requires a reused workspace to recreate source views and disregard obsolete dependency metadata.
     */
    private fun assertReusedExport(
        fixture: QodanaCompilerFixture,
        inventory: Map<*, *>,
    ) {
        val sourceViews = JsonSlurper().parse(directory.resolve("build/qodana/source-views.json").toFile()) as Map<*, *>
        val firstView = (sourceViews["roots"] as List<*>).first() as Map<*, *>
        val obsoleteSource = directory.resolve(firstView["view"] as String).resolve("Obsolete.kt")
        Files.writeString(obsoleteSource, "This stale file must not enter a later analysis.\n")
        Files.writeString(directory.resolve("shared-kmp/build/qodana/dependencies.json"), "{\"revision\":\"stale\",\"project\":\":foreign\",\"compilations\":{}}\n")
        val reused = fixture.runner().build()
        assertEquals(TaskOutcome.SUCCESS, reused.task(":qodanaDeclarationInventory")?.outcome)
        assertTrue(Files.exists(obsoleteSource).not(), "Each analysis must recreate the exact selected source views.")
        assertEquals(inventory, JsonSlurper().parse(directory.resolve("build/qodana/declarations.json").toFile()))
    }

    /**
     * Requires a later revision to fingerprint the newly compiled dependency bytes.
     */
    private fun assertFreshOutput(fixture: QodanaCompilerFixture) {
        val initialSupportHash = QodanaCompilerModelAssertions(directory, repository).supportOutputHash()
        fixture.write("support-kotlin/src/main/kotlin/fixture/KotlinSupport.kt", "package fixture\npublic fun kotlinValue(): Int = 6\n")
        fixture.commit("Change required compiler output", "support-kotlin/src/main/kotlin/fixture/KotlinSupport.kt")
        val changed = fixture.runner().build()
        assertEquals(TaskOutcome.SUCCESS, changed.task(":qodanaDeclarationInventory")?.outcome)
        val evidence = QodanaCompilerModelAssertions(directory, repository)
        assertTrue(initialSupportHash != evidence.supportOutputHash(), "A later action must fingerprint the newly compiled dependency bytes.")
        evidence.assertCompilerModel()
    }

    /**
     * Rejects a declared dependency that has no required compiled output.
     */
    private fun assertMissingDependency(fixture: QodanaCompilerFixture) {
        val runner = fixture.runner()
        val missingDependency = runner.withArguments(runner.arguments + "-Pfixture.missingRequiredClasspath=true").buildAndFail()
        assertTrue(missingDependency.output.contains("missing-required.jar"))
    }

    /**
     * Prevents a declaration receipt when an authored auxiliary compiler input is broken.
     */
    private fun assertBrokenAuxiliary(fixture: QodanaCompilerFixture) {
        fixture.write("auxiliary-owner/authored-kotlin/fixture/AuxiliaryValue.kt", "package fixture\npublic fun broken(): Int = missingValue\n")
        fixture.commit("Reject broken auxiliary compilation", "auxiliary-owner/authored-kotlin/fixture/AuxiliaryValue.kt")
        Files.delete(directory.resolve("build/qodana/declarations.json"))
        val failed = fixture.runner().buildAndFail()
        assertEquals(TaskOutcome.FAILED, failed.task(":auxiliary-owner:compileVerificationKotlin")?.outcome)
        assertEquals(null, failed.task(":qodanaDeclarationInventory"))
        assertTrue(Files.exists(directory.resolve("build/qodana/declarations.json")).not())
    }
}
