package dev.s7a.strata.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Exercises production settings selection without loading plugins, game dependencies or product tasks. */
internal class ProjectModelFunctionalTest {
    @TempDir
    lateinit var directory: Path

    private val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("gradle/performance-modules.tsv")) }

    /** Copies the production selection branch and real project inventory into a plugin-free build. */
    @BeforeEach
    fun prepare() {
        val settings = Files.readString(repository.resolve("settings.gradle.kts"))
        Files.writeString(
            directory.resolve("settings.gradle.kts"),
            "rootProject.name = \"model-fixture\"\n" + settings.substringAfter("rootProject.name = \"strata\""),
        )
        val common = Regex("\"(:[a-z][a-z0-9:-]*)\"").findAll(settings).map { it.groupValues[1] }
            .filter { Files.isRegularFile(repository.resolve(it.removePrefix(":").replace(':', '/')).resolve("build.gradle.kts")) }
        val versioned = listOf("runtime", "integration").flatMap { parent ->
            Files.newDirectoryStream(repository.resolve(parent), "minecraft-fabric-*").use { projects ->
                projects.filter { Files.isRegularFile(it.resolve("build.gradle.kts")) }.map { ":$parent:${it.fileName}" }
            }
        }
        (common.toList() + versioned).distinct().forEach { path ->
            val project = directory.resolve(path.removePrefix(":").replace(':', '/'))
            Files.createDirectories(project)
            Files.writeString(project.resolve("build.gradle.kts"), "")
        }
        Files.writeString(
            directory.resolve("build.gradle.kts"),
            """
            val requested = gradle.startParameter.taskNames
            allprojects {
                requested.map { it.substringAfterLast(':') }.distinct().forEach { name -> tasks.register(name) }
            }
            val projects = subprojects.filter { it.file("build.gradle.kts").isFile }.map { it.path }.sorted()
            file("included-projects.txt").writeText(projects.joinToString("\n"))
            """.trimIndent(),
        )
    }

    @Test
    fun `JVM preparation and collection close over the shared fixture dependencies`() {
        assertEquals(
            jvmProjects,
            included(
                "-Pstrata.jvmOnly=true",
                ":runtime:minecraft:formatKotlin",
                ":runtime:minecraft-fonts-lwjgl:formatKotlin",
                ":quality:component-benchmarks:formatKotlin",
                ":quality:component-benchmarks:jmhCompileGeneratedClasses",
                ":quality:component-benchmarks:jmhComponents",
                ":quality:benchmarks:jmhHistorical",
                ":quality:remote-benchmarks:jmhRemote",
                ":performance-testkit:processEvidence",
                ":performance-testkit:jvmTest",
                ":runtime:minecraft-fonts-lwjgl:test",
                ":api:checkKotlinAbi",
            ),
        )
    }

    @Test
    fun `JVM model rejects full acceptance and mixed requests before project configuration`() {
        listOf(
            "check", ":check", ":quality:component-benchmarks:check", ":runtime:minecraft-fonts-lwjgl:check",
            ":verifyPublishedPerformanceInventory", ":quality:component-benchmarks:verifyPublishedHostInventory",
            ":quality:component-benchmarks:capturePublishedHostInventory", "publishToMavenLocal",
            ":performance-testkit:publishToMavenLocal", ":koverHtmlReport", ":koverXmlReport",
            ":runtime:minecraft-fonts-lwjgl:verifyOfflineFontParity", ":runtime:web:check",
            ":quality:component-benchmarks:jmhC", ":q:component-benchmarks:jmhComponents", ":ciMinecraftCheck",
            ":quality:component-benchmarks:jmhHistorical", ":quality:benchmarks:jmhRemote",
            ":quality:remote-benchmarks:processEvidence", ":quality:detekt-rules:checkKotlinAbi",
            ":quality:component-benchmarks:updateKotlinAbi", ":api:jar", ":runtime:headless:jvmTest",
        ).forEach { task ->
            rejected("fixture preparation", "-Pstrata.jvmOnly=true", ":quality:component-benchmarks:jmhComponents", task)
        }
        rejected("fixture preparation", "-Pstrata.jvmOnly=true")
    }

    @Test
    fun `scoped flags reject complete IDE and Minecraft target selection`() {
        listOf("strata.jvmOnly", "strata.webOnly").forEach { scope ->
            val tasks = arrayOf("-P$scope=true", ":quality:component-benchmarks:jmhComponents")
            rejected("IDE/Qodana", *tasks, "-Pstrata.completeIdeaModel=true")
            rejected("IDE/Qodana", *tasks, "-Didea.sync.active=true")
            rejected("Minecraft target selection", *tasks, "-Pstrata.minecraftVersions=1.20")
        }
        rejected("Choose one scoped", "-Pstrata.jvmOnly=true", "-Pstrata.webOnly=true", ":quality:component-benchmarks:jmhComponents")
    }

    @Test
    fun `Web collector is supported while component acceptance requires the complete model`() {
        assertEquals(jvmProjects + setOf(":runtime:web", ":integration:web", ":examples:web"), included("-Pstrata.webOnly=true", ":quality:component-benchmarks:jmhComponents"))
        rejected("component check", "-Pstrata.webOnly=true", ":quality:component-benchmarks:check")
        rejected("component check", "-Pstrata.webOnly=true", ":quality:component-benchmarks:jmhComponents", ":quality:component-benchmarks:check")
    }

    @Test
    fun `full acceptance and mixed collection requests keep all twenty two targets`() {
        val full = included("check", ":quality:component-benchmarks:jmhComponents")
        assertEquals(69, full.size)
        assertEquals(22, full.count { it.startsWith(":runtime:minecraft-fabric-") })
        assertEquals(22, full.count { it.startsWith(":integration:minecraft-fabric-") })
        assertTrue(full.containsAll(setOf(":detekt-rules", ":paper-api", ":velocity-api", ":integration:docs")))
        assertEquals(full, included(":ciMinecraftCheck", ":quality:component-benchmarks:jmhComponents", "-Pstrata.minecraftVersions=1.20"))
        assertEquals(full, included(":quality:component-benchmarks:check", "-Pstrata.jvmOnly=false", "-Pstrata.webOnly=false"))
    }

    @Test
    fun `complete IDEA model overrides every implicit target narrowing`() {
        val full = included("idea", "-Pstrata.completeIdeaModel=true")
        assertEquals(69, full.size)
        listOf(":ciMinecraftCheck", ":integration:docs:check", "benchmarkMinecraftQuick").forEach { task ->
            assertEquals(full, included(task, "-Pstrata.minecraftVersions=1.20", "-Pstrata.completeIdeaModel=true"))
            assertEquals(full, included(task, "-Pstrata.minecraftVersions=1.20", "-Didea.sync.active=true"))
        }
        val selected = included(":ciMinecraftCheck", "-Pstrata.minecraftVersions=1.20")
        assertEquals(setOf(":runtime:minecraft-fabric-1.20", ":integration:minecraft-fabric-1.20"), selected.filter { ":minecraft-fabric-" in it }.toSet())
    }

    /** Runs the real settings branch and returns projects with build files, excluding implicit parent projects. */
    private fun included(vararg arguments: String): Set<String> {
        runner(*arguments).build()
        return Files.readAllLines(directory.resolve("included-projects.txt")).toSet()
    }

    /** Requires settings to reject the request before evaluating even the fixture's root build script. */
    private fun rejected(reason: String, vararg arguments: String) {
        Files.deleteIfExists(directory.resolve("included-projects.txt"))
        val result = runner(*arguments).buildAndFail()
        assertTrue(result.output.contains(reason), result.output)
        assertTrue(Files.exists(directory.resolve("included-projects.txt")).not())
    }

    /** Uses the current Gradle distribution with offline, single-worker, plugin-free fixture builds. */
    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create().withProjectDir(directory.toFile())
        .withArguments(*arguments, "--offline", "--max-workers=1", "--stacktrace")

    private val jvmProjects = setOf(
        ":api", ":runtime:core", ":runtime:headless", ":runtime:minecraft", ":runtime:minecraft-fonts-lwjgl", ":runtime:remote",
        ":quality:detekt-rules", ":performance-testkit", ":quality:benchmarks", ":quality:component-benchmarks", ":quality:remote-benchmarks",
    )
}
