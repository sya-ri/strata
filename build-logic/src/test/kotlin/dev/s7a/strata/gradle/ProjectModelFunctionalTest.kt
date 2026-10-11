package dev.s7a.strata.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** Exercises production settings and native task resolution without game or product plugins. */
internal class ProjectModelFunctionalTest {
    @TempDir
    lateinit var directory: Path

    private val repository =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("gradle/performance-modules.tsv")) }

    /** Copies real project selection, fixed available tasks and an executable typed-Test fixture. */
    @BeforeEach
    fun prepare() {
        val settings = Files.readString(repository.resolve("settings.gradle.kts"))
        val settingsImports = settings.lineSequence().filter { it.startsWith("import ") }.joinToString("\n")
        Files.writeString(
            directory.resolve("settings.gradle.kts"),
            "$settingsImports\nrootProject.name = \"model-fixture\"\n" + settings.substringAfter("rootProject.name = \"strata\""),
        )
        val common =
            Regex("\"(:[a-z][a-z0-9:-]*)\"")
                .findAll(settings)
                .map { it.groupValues[1] }
                .filter { Files.isRegularFile(repository.resolve(it.removePrefix(":").replace(':', '/')).resolve("build.gradle.kts")) }
        val versioned =
            listOf("runtime", "integration").flatMap { parent ->
                Files.newDirectoryStream(repository.resolve(parent), "minecraft-fabric-*").use { projects ->
                    projects.filter { Files.isRegularFile(it.resolve("build.gradle.kts")) }.map { ":$parent:${it.fileName}" }
                }
            }
        (common.toList() + versioned).distinct().forEach { path ->
            val project = directory.resolve(path.removePrefix(":").replace(':', '/'))
            Files.createDirectories(project)
            Files.writeString(project.resolve("build.gradle.kts"), "")
        }
        Files.createDirectories(directory.resolve("gradle"))
        Files.copy(repository.resolve("gradle/libs.versions.toml"), directory.resolve("gradle/libs.versions.toml"))
        listOf("runtime/core", "integration/api").forEach { project ->
            val sources = directory.resolve("$project/src/test/java/model")
            Files.createDirectories(sources)
            Files.writeString(
                sources.resolve("FilterTest.java"),
                """
                package model;
                import org.junit.jupiter.api.Test;
                public class FilterTest {
                    @Test void selected() {}
                    @Test void other() {}
                }
                """.trimIndent(),
            )
        }
        Files.writeString(directory.resolve("build.gradle.kts"), fixtureBuild)
    }

    @Test
    fun `JVM tasks close over fixture dependencies and the API consumer`() {
        assertEquals(
            jvmProjects,
            included(
                "-Pstrata.jvmOnly=true",
                ":runtime:minecraft:formatKotlin",
                ":runtime:minecraft-fonts-lwjgl:formatKotlin",
                ":quality:component-benchmarks:jmhCompileGeneratedClasses",
                ":quality:component-benchmarks:jmhComponents",
                ":quality:component-benchmarks:verifyComponentRenderingWork",
                ":quality:benchmarks:verifyHistoricalWorkloads",
                ":quality:benchmarks:jmhHistorical",
                ":quality:remote-benchmarks:jmhRemote",
                ":performance-testkit:processEvidence",
                ":performance-testkit:jvmTest",
                ":api:checkKotlinAbi",
                ":integration:api:checkApiOnlyClasspath",
            ),
        )
        assertEquals(jvmProjects, included("-Pstrata.jvmOnly=true", ":q:component-benchmarks:vCRW"))
        assertEquals(jvmProjects, included("-Pstrata.jvmOnly=true", "formatKotlin"))
        assertEquals(jvmProjects, included("-Pstrata.jvmOnly=true"))
    }

    @Test
    fun `native Test options preserve exact executed JUnit counts`() {
        assertEquals(
            jvmProjects,
            included("-Pstrata.jvmOnly=true", ":runtime:core:jvmTest", "--tests", "model.FilterTest.selected"),
        )
        assertJUnit("runtime/core", "jvmTest")
        assertTrue(Files.readAllLines(directory.resolve("executed-tasks.txt")).contains(":runtime:core:koverFindJar"))
        assertEquals(
            jvmProjects,
            included("-Pstrata.jvmOnly=true", ":integration:api:test", "--tests=model.FilterTest.selected"),
        )
        assertJUnit("integration/api", "test")
        assertTrue(Files.readAllLines(directory.resolve("executed-tasks.txt")).contains(":integration:api:koverFindJar"))
        val result = runner("-Pstrata.jvmOnly=true", ":runtime:core:jvmTest", "--tests", ":check").buildAndFail()
        assertTrue(result.output.contains("No tests found"), result.output)
        assertTrue(result.output.contains("complete build").not(), result.output)
    }

    @Test
    fun `JVM model rejects resolved complete acceptance before task execution`() {
        listOf(
            "check",
            ":check",
            ":quality:component-benchmarks:check",
            ":runtime:minecraft-fonts-lwjgl:check",
            ":verifyPublishedPerformanceInventory",
            ":quality:component-benchmarks:verifyPublishedHostInventory",
            ":quality:component-benchmarks:capturePublishedHostInventory",
            "publishToMavenLocal",
            ":performance-testkit:publishToMavenLocal",
            ":koverHtmlReport",
            ":koverXmlReport",
            ":koverBinaryReport",
            ":koverVerify",
            ":koverLog",
            ":koverJvmTests",
            ":koverHtmlReportJvm",
            ":runtime:minecraft-fonts-lwjgl:verifyOfflineFontParity",
            ":ciMinecraftCheck",
            ":runtime:core:che",
        ).forEach { task ->
            rejected("complete build", false, "-Pstrata.jvmOnly=true", ":quality:component-benchmarks:jmhComponents", task)
        }
    }

    @Test
    fun `configuration cache reuses admitted tasks and refuses complete graph fallback`() {
        val control = arrayOf("-Pstrata.jvmOnly=true", "--configuration-cache", ":quality:component-benchmarks:jmhComponents")
        val stored = runner(*control).build()
        assertTrue(stored.output.contains("Configuration cache entry stored."), stored.output)
        Files.deleteIfExists(directory.resolve("included-projects.txt"))
        Files.deleteIfExists(directory.resolve("executed-tasks.txt"))
        val reused = runner(*control).build()
        assertTrue(reused.output.contains("Configuration cache entry reused."), reused.output)
        assertTrue(Files.exists(directory.resolve("included-projects.txt")).not())
        assertEquals(listOf(":quality:component-benchmarks:jmhComponents"), Files.readAllLines(directory.resolve("executed-tasks.txt")))
        listOf("fail", "warn").forEach { problemMode ->
            listOf(":runtime:core:check", ":performance-testkit:publishToMavenLocal", ":koverHtmlReport").forEach { task ->
                repeat(2) {
                    Files.deleteIfExists(directory.resolve("included-projects.txt"))
                    Files.deleteIfExists(directory.resolve("executed-tasks.txt"))
                    val result =
                        runner("-Pstrata.jvmOnly=true", "--configuration-cache", "--configuration-cache-problems=$problemMode", ":quality:component-benchmarks:jmhComponents", task)
                            .buildAndFail()
                    assertTrue(result.output.contains("strata.jvmOnly cannot run complete acceptance tasks"), result.output)
                    // Scheduling fails before graph serialization; a stored diagnostic alone does not prove a reusable entry.
                    assertTrue(result.output.contains("Calculating task graph"), result.output)
                    assertTrue(result.output.contains("Reusing configuration cache").not(), result.output)
                    assertTrue(result.output.contains("Configuration cache entry reused").not(), result.output)
                    assertTrue(Files.exists(directory.resolve("included-projects.txt")))
                    assertTrue(Files.exists(directory.resolve("executed-tasks.txt")).not())
                }
            }
        }
    }

    @Test
    fun `Gradle rejects unknown tasks projects and Test options`() {
        listOf(":runtime:core:missingTask", ":runtime:web:check", ":runtime:minecraft-fabric-1.20:check").forEach { task ->
            val result = runner("-Pstrata.jvmOnly=true", task).buildAndFail()
            assertTrue(result.output.contains("not found"), result.output)
        }
        val result = runner("-Pstrata.jvmOnly=true", ":runtime:core:jvmTest", "--unknown-test-option").buildAndFail()
        assertTrue(result.output.contains("Unknown command-line option"), result.output)
    }

    @Test
    fun `scoped flags reject complete IDE and Minecraft target selection`() {
        listOf("strata.jvmOnly", "strata.webOnly").forEach { scope ->
            val tasks = arrayOf("-P$scope=true", ":quality:component-benchmarks:jmhComponents")
            rejected("IDE/Qodana", true, *tasks, "-Pstrata.completeIdeaModel=true")
            rejected("IDE/Qodana", true, *tasks, "-Didea.sync.active=true")
            rejected("Minecraft target selection", true, *tasks, "-Pstrata.minecraftVersions=1.20")
        }
        rejected("Choose one scoped", true, "-Pstrata.jvmOnly=true", "-Pstrata.webOnly=true", ":quality:component-benchmarks:jmhComponents")
    }

    @Test
    fun `Web model retains its original project set and acceptance boundary`() {
        assertEquals(sharedJvmProjects + setOf(":runtime:web", ":integration:web", ":examples:web"), included("-Pstrata.webOnly=true", ":quality:component-benchmarks:jmhComponents"))
        rejected("component check", true, "-Pstrata.webOnly=true", ":quality:component-benchmarks:check")
        rejected("component check", true, "-Pstrata.webOnly=true", ":quality:component-benchmarks:jmhComponents", ":quality:component-benchmarks:check")
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

    /** Reads actual fresh JUnit output, proving the option selected one of the two real tests. */
    private fun assertJUnit(
        project: String,
        task: String,
    ) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val suite = factory.newDocumentBuilder().parse(directory.resolve("$project/build/test-results/$task/TEST-model.FilterTest.xml").toFile()).documentElement
        assertEquals("1", suite.getAttribute("tests"))
        assertEquals("0", suite.getAttribute("skipped"))
        assertEquals("0", suite.getAttribute("failures"))
        assertEquals("0", suite.getAttribute("errors"))
        assertEquals(1, suite.getElementsByTagName("testcase").length)
        assertEquals(
            "selected()",
            suite
                .getElementsByTagName("testcase")
                .item(0)
                .attributes
                .getNamedItem("name")
                .nodeValue,
        )
    }

    /** Returns projects with real build files, excluding implicit parent projects. */
    private fun included(vararg arguments: String): Set<String> {
        runner(*arguments).build()
        return Files.readAllLines(directory.resolve("included-projects.txt")).toSet()
    }

    /** Distinguishes settings-time rejection from resolved-graph rejection before any task executes. */
    private fun rejected(
        reason: String,
        beforeConfiguration: Boolean,
        vararg arguments: String,
    ) {
        Files.deleteIfExists(directory.resolve("included-projects.txt"))
        Files.deleteIfExists(directory.resolve("executed-tasks.txt"))
        val result = runner(*arguments).buildAndFail()
        assertTrue(result.output.contains(reason), result.output)
        assertEquals(beforeConfiguration.not(), Files.exists(directory.resolve("included-projects.txt")))
        assertTrue(Files.exists(directory.resolve("executed-tasks.txt")).not())
    }

    /** Uses the current distribution with bounded plugin-free model tasks and one small JVM fixture. */
    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(directory.toFile())
            .withArguments(
                *arguments,
                "--max-workers=1",
                "--no-parallel",
                "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m",
                "--stacktrace",
            )

    private val sharedJvmProjects =
        setOf(
            ":api",
            ":runtime:core",
            ":runtime:headless",
            ":runtime:minecraft",
            ":runtime:minecraft-fonts-lwjgl",
            ":runtime:remote",
            ":quality:detekt-rules",
            ":performance-testkit",
            ":quality:benchmarks",
            ":quality:component-benchmarks",
            ":quality:remote-benchmarks",
        )
    private val jvmProjects = sharedJvmProjects + ":integration:api"

    private val fixtureBuild =
        """
        import org.gradle.api.tasks.SourceSetContainer
        import org.gradle.api.tasks.testing.Test
        import org.gradle.language.base.plugins.LifecycleBasePlugin

        val junitJupiter = libs.junit.jupiter
        val junitLauncher = libs.junit.platform.launcher
        allprojects {
            apply(plugin = "base")
            val fixtureTests = path in setOf(":runtime:core", ":integration:api")
            if (fixtureTests) {
                apply(plugin = "java")
                repositories { mavenCentral() }
                dependencies {
                    add("testImplementation", junitJupiter)
                    add("testRuntimeOnly", junitLauncher)
                }
                val instrumentation = tasks.register("koverFindJar")
                tasks.withType<Test>().configureEach {
                    dependsOn(instrumentation)
                    useJUnitPlatform()
                    maxHeapSize = "128m"
                    maxParallelForks = 1
                    jvmArgs("-XX:MaxMetaspaceSize=256m")
                }
                if (path == ":runtime:core") {
                    val sources = extensions.getByType<SourceSetContainer>()
                    tasks.register<Test>("jvmTest") {
                        dependsOn("testClasses")
                        testClassesDirs = sources.named("test").get().output.classesDirs
                        classpath = sources.named("test").get().runtimeClasspath
                    }
                }
            }
            val available = listOf("formatKotlin", "lintKotlin", "detekt", "classes", "jar", "test", "jvmJar", "checkKotlinAbi",
                "jmhClasses", "jmhRunBytecodeGenerator", "jmhCompileGeneratedClasses", "jmhHistorical", "jmhComponents", "jmhRemote",
                "verifyHistoricalWorkloads", "verifyComponentRenderingWork", "processEvidence", "checkApiOnlyClasspath",
                "verifyPublishedPerformanceInventory", "verifyPublishedHostInventory", "capturePublishedHostInventory",
                "verifyOfflineFontParity", "ciMinecraftCheck", "idea", "benchmarkMinecraftQuick")
            available.filter { tasks.names.contains(it).not() }.forEach { name -> tasks.register(name) }
            listOf("koverHtmlReport", "koverXmlReport", "koverBinaryReport", "koverVerify", "koverLog", "koverJvmTests", "koverHtmlReportJvm").forEach { name ->
                tasks.register(name) { group = LifecycleBasePlugin.VERIFICATION_GROUP }
            }
            if (tasks.names.contains("jvmTest").not()) tasks.register("jvmTest")
            tasks.register("publishToMavenLocal") { group = "publishing" }
            val executedTasks = rootProject.file("executed-tasks.txt")
            tasks.configureEach {
                val taskPath = path
                doLast { executedTasks.appendText(taskPath + "\n") }
            }
        }
        val projects = subprojects.filter { it.file("build.gradle.kts").isFile }.map { it.path }.sorted()
        file("included-projects.txt").writeText(projects.joinToString("\n"))
        """.trimIndent()
}
