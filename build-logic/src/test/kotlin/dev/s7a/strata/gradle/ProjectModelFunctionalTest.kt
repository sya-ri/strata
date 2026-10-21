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

/**
 * Exercises production settings and native task resolution without game or product plugins.
 */
internal class ProjectModelFunctionalTest {
    @TempDir
    lateinit var directory: Path

    private val repository =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("gradle/performance-modules.tsv")) }

    /**
     * Copies real project selection, fixed available tasks and an executable typed-Test fixture.
     */
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
        listOf("runtime/core", "integration/api", "velocity-api", "runtime/velocity").forEach { project ->
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
    fun `Velocity archive and owner tasks use the scoped JVM model`() {
        assertEquals(
            jvmProjects,
            included(
                "-Pstrata.jvmOnly=true",
                ":velocity-api:jar",
                ":runtime:velocity:jar",
                ":runtime:velocity:testClasses",
                ":runtime:velocity:test",
                "--tests",
                "model.FilterTest.selected",
            ),
        )
        assertJUnit("runtime/velocity", "test")
        assertTrue(Files.readAllLines(directory.resolve("executed-tasks.txt")).contains(":runtime:velocity:koverFindJar"))
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
            ":runtime:velocity:check",
            ":velocity-api:publishToMavenLocal",
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
    fun `Web model retains every owned module check`() {
        assertEquals(sharedJvmProjects + setOf(":runtime:web", ":integration:web", ":examples:web"), included("-Pstrata.webOnly=true", ":quality:component-benchmarks:jmhComponents"))
        assertEquals(
            sharedJvmProjects + setOf(":runtime:web", ":integration:web", ":examples:web"),
            included("-Pstrata.webOnly=true", ":runtime:web:check", ":performance-testkit:publishToMavenLocal"),
        )
        Files.deleteIfExists(directory.resolve("executed-tasks.txt"))
        assertEquals(
            sharedJvmProjects + setOf(":runtime:web", ":integration:web", ":examples:web"),
            included("-Pstrata.webOnly=true", ":integration:web:check"),
        )
        assertEquals(
            listOf(":integration:web:koverCachedVerify", ":integration:web:koverVerify", ":integration:web:check"),
            Files.readAllLines(directory.resolve("executed-tasks.txt")),
        )
        assertEquals(69, included(":integration:web:check").size)
        rejected("complete build", false, "-Pstrata.webOnly=true", ":quality:component-benchmarks:check")
        rejected("complete build", false, "-Pstrata.webOnly=true", ":quality:component-benchmarks:jmhComponents", ":quality:component-benchmarks:check")
    }

    @Test
    fun `Web module verification cannot admit complete acceptance`() {
        listOf(
            ":check",
            ":koverVerify",
            ":koverCachedVerify",
            ":koverHtmlReport",
            ":koverXmlReport",
            ":integration:web:koverHtmlReport",
            ":performance-testkit:verifyPublishedPerformanceInventory",
            ":runtime:minecraft-fonts-lwjgl:verifyOfflineFontParity",
            ":runtime:core:publishToMavenLocal",
        ).forEach { complete ->
            rejected("complete build", false, "-Pstrata.webOnly=true", ":integration:web:check", complete)
        }
    }

    @Test
    fun `Web module coverage verification survives configuration cache reuse`() {
        val arguments = arrayOf("-Pstrata.webOnly=true", "--configuration-cache", ":integration:web:check")
        val stored = runner(*arguments).build()
        assertTrue(stored.output.contains("Configuration cache entry stored."), stored.output)
        Files.deleteIfExists(directory.resolve("executed-tasks.txt"))
        val reused = runner(*arguments).build()
        assertTrue(reused.output.contains("Configuration cache entry reused."), reused.output)
        assertEquals(
            listOf(":integration:web:koverCachedVerify", ":integration:web:koverVerify", ":integration:web:check"),
            Files.readAllLines(directory.resolve("executed-tasks.txt")),
        )
        rejected("complete build", false, *arguments, ":koverVerify")
    }

    @Test
    fun `Web source tasks use the complete scoped workspace without a CLI allowlist`() {
        val tasks =
            arrayOf(
                ":runtime:web:compileKotlinJs",
                ":runtime:web:compileTestKotlinJs",
                ":runtime:web:checkKotlinAbi",
                ":runtime:web:lintKotlin",
                ":runtime:web:detekt",
                ":integration:web:compileKotlinJs",
                ":integration:web:lintKotlin",
                ":integration:web:detekt",
            )
        assertEquals(sharedJvmProjects + setOf(":runtime:web", ":integration:web", ":examples:web"), included("-Pstrata.webOnly=true", *tasks))
        assertEquals(tasks.toSet(), Files.readAllLines(directory.resolve("executed-tasks.txt")).toSet())
        assertEquals(sharedJvmProjects + setOf(":runtime:web", ":integration:web", ":examples:web"), included("-Pstrata.webOnly=true", "lintKotlin"))
    }

    @Test
    fun `Web model preserves native Test options and rejects resolved complete acceptance`() {
        included("-Pstrata.webOnly=true", ":runtime:core:jvmTest", "--tests", "model.FilterTest.selected")
        assertJUnit("runtime/core", "jvmTest")
        listOf(
            ":check",
            ":quality:component-benchmarks:che",
            ":verifyPublishedPerformanceInventory",
            ":quality:component-benchmarks:verifyPublishedHostInventory",
            ":quality:component-benchmarks:capturePublishedHostInventory",
            ":runtime:web:publishToMavenLocal",
            ":performance-testkit:publish",
            ":koverHtmlReport",
            ":koverXmlReport",
            ":ciMinecraftCheck",
            ":benchmarkMinecraftQuick",
        ).forEach { task ->
            rejected("complete build", false, "-Pstrata.webOnly=true", ":runtime:web:compileKotlinJs", task)
        }
        val unknown = runner("-Pstrata.webOnly=true", ":runtime:web:missingTask").buildAndFail()
        assertTrue(unknown.output.contains("not found"), unknown.output)
        val excluded = runner("-Pstrata.webOnly=true", ":runtime:minecraft-fabric-1.20:jar").buildAndFail()
        assertTrue(excluded.output.contains("not found"), excluded.output)
    }

    @Test
    fun `Web configuration cache preserves source admission and complete acceptance refusal`() {
        val source = arrayOf("-Pstrata.webOnly=true", "--configuration-cache", ":runtime:web:compileKotlinJs")
        val stored = runner(*source).build()
        assertTrue(stored.output.contains("Configuration cache entry stored."), stored.output)
        val reused = runner(*source).build()
        assertTrue(reused.output.contains("Configuration cache entry reused."), reused.output)
        repeat(2) { rejected("complete build", false, *source, ":quality:component-benchmarks:check") }
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

    @Test
    fun `reader documentation checks retain every runtime and only the asset integration`() {
        val tasks = arrayOf(":integration:docs:checkDocumentationLinks", ":integration:docs:checkStrataSkill")
        val full = included("check")
        val selected = included(*tasks, "-Pstrata.minecraftVersions=26.3")
        assertEquals(full.filter { it.startsWith(":integration:minecraft-fabric-").not() || it.endsWith("-26.3") }.toSet(), selected)
        assertEquals(full, included(*tasks, ":runtime:core:test", "-Pstrata.minecraftVersions=26.3"))
        assertEquals(full, included(*tasks, "-Pstrata.minecraftVersions=26.3", "-Pstrata.completeIdeaModel=true"))
    }

    @Test
    fun `Minecraft source scope retains selected project dependencies and native Test options`() {
        val full = included("check")
        val common = full.filter { it.contains(":minecraft-fabric-").not() }.toSet() - ":integration:docs"
        listOf("1.20", "26.3").forEach { version ->
            val selected = setOf(":runtime:minecraft-fabric-$version", ":integration:minecraft-fabric-$version")
            assertEquals(
                common + selected,
                included(
                    "-Pstrata.minecraftSourceOnly=true",
                    "-Pstrata.minecraftVersions=$version",
                    ":runtime:core:jvmTest",
                    "--tests",
                    "model.FilterTest.selected",
                    ":runtime:minecraft-fabric-$version:jar",
                    ":integration:minecraft-fabric-$version:gametestClasses",
                ),
            )
            assertJUnit("runtime/core", "jvmTest")
        }
        assertEquals(
            common + setOf(":runtime:minecraft-fabric-1.20", ":integration:minecraft-fabric-1.20", ":runtime:minecraft-fabric-26.3", ":integration:minecraft-fabric-26.3"),
            included("-Pstrata.minecraftSourceOnly=true", "-Pstrata.minecraftVersions=1.20,26.3", ":runtime:core:classes"),
        )
        val unknown = runner("-Pstrata.minecraftSourceOnly=true", "-Pstrata.minecraftVersions=26.3", ":runtime:minecraft-fabric-1.20:jar").buildAndFail()
        assertTrue(unknown.output.contains("not found"), unknown.output)
    }

    @Test
    fun `Minecraft source scope rejects incompatible selections before tasks execute`() {
        listOf("", "1.20,", "1.20,,26.3", "1.20,1.20").forEach { versions ->
            rejected("nonempty distinct", true, "-Pstrata.minecraftSourceOnly=true", "-Pstrata.minecraftVersions=$versions", ":runtime:core:classes")
        }
        rejected("Unsupported source-preparation", true, "-Pstrata.minecraftSourceOnly=true", "-Pstrata.minecraftVersions=1.19", ":runtime:core:classes")
        listOf("-Pstrata.jvmOnly=true", "-Pstrata.webOnly=true").forEach { incompatible ->
            rejected(
                "Choose one",
                true,
                "-Pstrata.minecraftSourceOnly=true",
                "-Pstrata.minecraftVersions=26.3",
                incompatible,
                ":runtime:core:classes",
            )
        }
        listOf("-Pstrata.completeIdeaModel=true", "-Didea.sync.active=true").forEach { incompatible ->
            rejected("IDE/Qodana", true, "-Pstrata.minecraftSourceOnly=true", "-Pstrata.minecraftVersions=26.3", incompatible, ":runtime:core:classes")
        }
        listOf(":check", ":koverHtmlReport", ":performance-testkit:publishToMavenLocal", ":ciMinecraftCheck", ":benchmarkMinecraftQuick", ":integration:minecraft-fabric-26.3:runClientGameTest", ":integration:minecraft-fabric-26.3:runProductionClientGameTest").forEach { complete ->
            rejected("complete build", false, "-Pstrata.minecraftSourceOnly=true", "-Pstrata.minecraftVersions=26.3", ":runtime:core:classes", complete)
        }
    }

    @Test
    fun `Minecraft source configuration cache cannot narrow complete acceptance`() {
        val source = arrayOf("-Pstrata.minecraftSourceOnly=true", "-Pstrata.minecraftVersions=26.3", "--configuration-cache", ":runtime:core:classes")
        val stored = runner(*source).build()
        assertTrue(stored.output.contains("Configuration cache entry stored."), stored.output)
        val reused = runner(*source).build()
        assertTrue(reused.output.contains("Configuration cache entry reused."), reused.output)
        repeat(2) { rejected("complete build", false, *source, ":check") }
        val full = included(":check", "-Pstrata.minecraftVersions=26.3")
        assertEquals(22, full.count { it.startsWith(":runtime:minecraft-fabric-") })
        assertEquals(22, full.count { it.startsWith(":integration:minecraft-fabric-") })
    }

    /**
     * Reads actual fresh JUnit output, proving the option selected one of the two real tests.
     */
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

    /**
     * Returns projects with real build files, excluding implicit parent projects.
     */
    private fun included(vararg arguments: String): Set<String> {
        runner(*arguments).build()
        return Files.readAllLines(directory.resolve("included-projects.txt")).toSet()
    }

    /**
     * Distinguishes settings-time rejection from resolved-graph rejection before any task executes.
     */
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

    /**
     * Uses the current distribution with bounded plugin-free model tasks and one small JVM fixture.
     */
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
    private val jvmProjects = sharedJvmProjects + setOf(":integration:api", ":velocity-api", ":runtime:velocity")

    private val fixtureBuild =
        """
        import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
        import org.gradle.api.tasks.JavaExec
        import org.gradle.api.tasks.SourceSetContainer
        import org.gradle.api.tasks.testing.Test
        import org.gradle.language.base.plugins.LifecycleBasePlugin

        val junitJupiter = libs.junit.jupiter
        val junitLauncher = libs.junit.platform.launcher
        allprojects {
            apply(plugin = "base")
            val fixtureTests = path in setOf(":runtime:core", ":integration:api", ":velocity-api", ":runtime:velocity")
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
            val available = listOf("formatKotlin", "lintKotlin", "detekt", "classes", "jar", "test", "jvmJar", "checkKotlinAbi", "compileKotlinJs", "compileTestKotlinJs",
                "jmhClasses", "jmhRunBytecodeGenerator", "jmhCompileGeneratedClasses", "jmhHistorical", "jmhComponents", "jmhRemote",
                "verifyHistoricalWorkloads", "verifyComponentRenderingWork", "processEvidence", "checkApiOnlyClasspath",
                "verifyPublishedPerformanceInventory", "verifyPublishedHostInventory", "capturePublishedHostInventory",
                "verifyOfflineFontParity", "ciMinecraftCheck", "idea", "benchmarkMinecraftQuick", "gametestClasses", "checkDocumentationLinks", "checkStrataSkill")
            available.filter { tasks.names.contains(it).not() }.forEach { name -> tasks.register(name) }
            listOf("koverHtmlReport", "koverXmlReport", "koverBinaryReport", "koverVerify", "koverCachedVerify", "koverLog", "koverJvmTests", "koverHtmlReportJvm").forEach { name ->
                tasks.register(name) { group = LifecycleBasePlugin.VERIFICATION_GROUP }
            }
            if (path == ":integration:web") {
                tasks.named("koverVerify") { dependsOn("koverCachedVerify") }
                tasks.named("check") { dependsOn("koverVerify") }
            }
            if (tasks.names.contains("jvmTest").not()) tasks.register("jvmTest")
            if (path.startsWith(":integration:minecraft-fabric-")) {
                tasks.register<JavaExec>("runClientGameTest")
                tasks.register("runProductionClientGameTest")
            }
            tasks.register("publishToMavenLocal") { group = "publishing" }
            tasks.register("publish") { group = "publishing" }
            if (path == ":performance-testkit") {
                val localPublication = tasks.register<PublishToMavenLocal>("publishTestkitPublicationToMavenLocal") {
                    group = "publishing"
                    enabled = false
                }
                tasks.named("publishToMavenLocal") { dependsOn(localPublication) }
            }
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
