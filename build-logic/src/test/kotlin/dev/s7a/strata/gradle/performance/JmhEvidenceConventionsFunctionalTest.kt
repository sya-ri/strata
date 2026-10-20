package dev.s7a.strata.gradle.performance

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Exercises the actual three module task bodies with resolved libraries and a loaded untimed probe.
 */
internal class JmhEvidenceConventionsFunctionalTest {
    @TempDir
    lateinit var directory: Path

    private val repository =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("gradle/libs.versions.toml")) }

    private val collectors = Collector.entries.map(Collector::taskName)
    private val descriptors = Collector.entries.associateBy(Collector::taskName)

    /**
     * Installs production task configuration, real project artifacts and a local external module.
     */
    @BeforeEach
    fun prepare() {
        write("settings.gradle.kts", "rootProject.name = \"jmh-conventions\"\ninclude(\"api\", \"runtime:core\", \"runtime:headless\", \":quality:benchmarks\", \":quality:remote-benchmarks\", \":quality:component-benchmarks\")\n")
        Collector.entries
            .map(Collector::module)
            .distinct()
            .forEach { write("quality/$it/build.gradle.kts", "") }
        Files.createDirectories(directory.resolve("gradle"))
        Files.copy(repository.resolve("gradle/libs.versions.toml"), directory.resolve("gradle/libs.versions.toml"))
        val helper =
            Path
                .of(
                    PerformanceModuleHost::class.java.protectionDomain.codeSource.location
                        .toURI(),
                ).toString()
                .replace('\\', '/')
        val tasks =
            Collector.entries
                .groupBy(Collector::module)
                .map { (module, names) ->
                    val source = Files.readString(repository.resolve("quality/$module/build.gradle.kts")).replace("\r\n", "\n")
                    "if (path == \":quality:$module\") {\n" + names.map(Collector::taskName).joinToString("\n") { name -> "tasks.register<JavaExec>(\"$name\") {" + source.substringAfter("tasks.register<JavaExec>(\"$name\") {").substringBefore("\n}\n") + "\n}\n" } + "\n}\n"
                }.joinToString("\n")
        writeTaskModel(helper, tasks)
        writeProbe()
        listOf("api", "runtime/core", "runtime/headless").forEach { project ->
            write("$project/build.gradle.kts", "")
            write("$project/src/main/resources/${project.substringAfterLast('/')}.txt", "ordinary")
        }
        write("repository/fixture/control/1/control-1.pom", "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>control</artifactId><version>1</version></project>")
        archive("repository/fixture/control/1/control-1.jar", "control.txt", "external")
        archive("fabric.jar", "fabric.txt", "explicit")
    }

    @Test
    fun `standard tasks retain complete arguments external inputs and distinct runtime behavior`() {
        val targets = Properties()
        mapOf(":api" to "api", ":runtime:core" to "core", ":runtime:headless" to "headless").forEach { (project, name) ->
            val target = archive("frozen/$name.jar", "$name.txt", "frozen")
            targets.setProperty(project, target.toString())
        }
        Files.newBufferedWriter(directory.resolve("runtime.properties")).use { targets.store(it, "Frozen actual archives") }
        val result = runner(*collectors.toTypedArray(), "-Pstrata.performance.historicalRuntime=runtime.properties", "-Pstrata.performance.portableTileRuntime=fabric.jar").build()
        collectors.forEach { assertEquals(TaskOutcome.SUCCESS, result.task(collectorPath(it))?.outcome) }
        collectors.forEach { collector ->
            val expected = descriptors.getValue(collector)
            val receipt = receipt(collector)
            val arguments = receipt.getProperty("arguments").lines()
            val workingDir = directory.resolve("quality/${descriptors.getValue(collector).module}").toFile().canonicalPath
            assertEquals(workingDir, receipt.getProperty("configuredWorkingDir"))
            assertEquals(workingDir, receipt.getProperty("workingDir"))
            assertEquals(expected.selector, arguments[2])
            assertEquals(listOf("-bm", "avgt", "-wi", "3", "-w", "1s", "-i", "5", "-r", "1s", "-f", "1", "-t", "1", "-tu", "us", "-foe", "true", "-prof", "gc"), arguments.drop(3).take(20))
            assertEquals(expected.jvmArguments, arguments.drop(23))
            val manifest = Properties().apply { Files.newBufferedReader(Path.of(receipt.getProperty("strata.performance.inputs"))).use(::load) }
            assertEquals(setOf("fixture:control:1:control-1.jar"), manifest.stringPropertyNames())
            val library = Path.of(manifest.values.single() as String)
            assertTrue(Files.readAllBytes(directory.resolve("repository/fixture/control/1/control-1.jar")).contentEquals(Files.readAllBytes(library)))
            assertTrue(receipt.getProperty("classpath").split(File.pathSeparator).contains(library.toString()))
            assertEquals(expected.inputFile, Path.of(receipt.getProperty("strata.performance.inputs")).fileName.toString())
            listOf("api", "core", "headless").forEach { assertEquals(expected.commonContent, receipt.getProperty("loaded.$it")) }
            assertEquals(expected.fabricContent, receipt.getProperty("loaded.fabric"))
            assertTrue(receipt.getProperty("classpath").contains("control-1.jar"))
        }
    }

    @Test
    fun `short sample sessions selectors and output rules remain module owned`() {
        runner("jmhHistorical", "jmhRemote", "-Pstrata.performance.quick=true", "-Pstrata.performance.smoke=true", "-Pstrata.performance.mode=sample", "-Pstrata.performance.remoteSessions=true", "-Pstrata.performance.workloads=declared", "-Pstrata.performance.historicalOutputRoot=history", "-Pstrata.performance.output=direct").build()
        val historical = receipt("jmhHistorical")
        val remote = receipt("jmhRemote")
        assertEquals(directory.resolve("history/selected-smoke-quick-sample/run-0").toString(), historical.getProperty("arguments").lineSequence().first())
        assertEquals(directory.resolve("direct").toString(), remote.getProperty("arguments").lineSequence().first())
        assertEquals("RemoteSessionBenchmark.*", remote.getProperty("arguments").lines()[2])
        listOf(historical, remote).forEach {
            assertEquals(
                listOf("-bm", "sample", "-wi", "0", "-w", "1s", "-i", "1", "-r", "100ms"),
                it
                    .getProperty("arguments")
                    .lines()
                    .drop(3)
                    .take(10),
            )
            assertEquals("declared", it.getProperty("strata.performance.workloads"))
        }
        runner("jmhComponents", "-Pstrata.performance.quick=true", "-Pstrata.performance.mode=sample", "-Pstrata.performance.benchmarks=DeclaredFixture", "-Pstrata.performance.parameters=parameters.tsv", "-Pstrata.performance.fixtureInputs=inputs.properties").build()
        val component = receipt("jmhComponents")
        assertEquals(".*", component.getProperty("arguments").lines()[2])
        assertEquals("false", component.getProperty("strata.performance.smoke"))
        assertEquals(directory.resolve("parameters.tsv").toString(), component.getProperty("strata.performance.parameters"))
        assertEquals(directory.resolve("inputs.properties").toString(), component.getProperty("strata.performance.fixtureInputs"))
    }

    @Test
    fun `obsolete scopes and missing provenance refuse collection`() {
        listOf("jmhHistorical" to "childLayout", "jmhComponents" to "fonts").forEach { (collector, flag) ->
            assertTrue(runner(collector, "-Pstrata.performance.$flag=false").buildAndFail().output.contains("Select generated fixture classes"))
        }
        assertTrue(runner("jmhComponents", "-Pstrata.performance.parameters=parameters.tsv").buildAndFail().output.contains("Compiled parameter selection requires explicit fixtures"))
        assertTrue(runner("jmhRemote", "-Pfixture.emptyLibraries=true").buildAndFail().output.contains("The JMH control library inventory is missing"))
        assertTrue(runner("jmhPortableTiles", "-Pstrata.performance.portableTileRuntime=missing.jar").buildAndFail().output.contains("Portable tile collection requires an actual runtime JAR"))
    }

    /**
     * Installs the actual collector bodies with an isolated classpath and bounded probe process.
     */
    private fun writeTaskModel(
        helper: String,
        tasks: String,
    ) {
        write(
            "build.gradle.kts",
            """
            import dev.s7a.strata.gradle.performance.configureJmhEvidence
            import org.gradle.api.artifacts.component.ProjectComponentIdentifier
            import org.gradle.api.attributes.LibraryElements
            import org.gradle.api.attributes.Usage
            import org.gradle.api.tasks.SourceSetContainer
            import java.util.Properties
            buildscript { dependencies { classpath(files("$helper")) } }
            plugins { java }
            subprojects { apply(plugin = "java-library") }
            configure(listOf(project(":quality:benchmarks"), project(":quality:remote-benchmarks"), project(":quality:component-benchmarks"))) {
            repositories { maven { url = uri(rootProject.file("repository")) } }
            val runtime = configurations.create("jmhRuntimeClasspath") {
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
                }
            }
            listOf(":api", ":runtime:core", ":runtime:headless").forEach { dependencies.add(runtime.name, dependencies.project(it)) }
            if (providers.gradleProperty("fixture.emptyLibraries").isPresent.not()) dependencies.add(runtime.name, "fixture:control:1")
            val historicalGenerated = tasks.register("generated")
            val historicalGenerator = tasks.register("generator")
            val historicalClasspath = rootProject.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath + runtime
            val historicalLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(JavaVersion.current().majorVersion.toInt())) }
            val remoteGenerated = historicalGenerated
            val remoteGenerator = historicalGenerator
            val remoteClasspath = historicalClasspath
            val remoteLauncher = historicalLauncher
            val componentGenerated = historicalGenerated
            val componentGenerator = historicalGenerator
            val componentClasspath = historicalClasspath
            val componentLauncher = historicalLauncher
            $tasks
            tasks.withType<JavaExec>().configureEach {
                dependsOn(":classes", runtime)
                // Substitute only the process entry point and finite test launcher, never production argument/actions.
                mainClass.set("fixture.Probe")
                javaLauncher.set(historicalLauncher)
                maxHeapSize = "64m"
                jvmArgs("-XX:MaxMetaspaceSize=128m")
                systemProperty("fixture.receipt", layout.buildDirectory.file("probes/${'$'}name.properties").get().asFile.absolutePath)
                val argumentFile = layout.buildDirectory.file("probes/${'$'}name.arguments").get().asFile
                systemProperty("fixture.arguments", argumentFile.absolutePath)
                systemProperty("fixture.workingDir", workingDir.absolutePath)
                doFirst {
                    argumentFile.parentFile.mkdirs()
                    argumentFile.writeText(args.orEmpty().joinToString("\n"))
                }
            }
            }
            """.trimIndent(),
        )
    }

    /**
     * Writes the loaded Java probe that records arguments, libraries and runtime resources.
     */
    private fun writeProbe() {
        write(
            "src/main/java/fixture/Probe.java",
            """
            package fixture;
            import java.io.InputStream;
            import java.io.OutputStream;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.Properties;
            public class Probe {
                public static void main(String[] args) throws Exception {
                    Properties receipt = new Properties();
                    receipt.setProperty("arguments", Files.readString(Path.of(System.getProperty("fixture.arguments"))));
                    receipt.setProperty("child.arguments", String.join("\n", args));
                    receipt.setProperty("classpath", System.getProperty("java.class.path"));
                    receipt.setProperty("workingDir", System.getProperty("user.dir"));
                    receipt.setProperty("configuredWorkingDir", System.getProperty("fixture.workingDir"));
                    System.getProperties().forEach((key, value) -> {
                        if (key.toString().startsWith("strata.performance.")) receipt.put(key, value);
                    });
                    for (String name : new String[] { "api", "core", "headless", "fabric" }) {
                        try (InputStream input = Probe.class.getResourceAsStream("/" + name + ".txt")) {
                            if (input != null) receipt.setProperty("loaded." + name, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                    Path output = Path.of(System.getProperty("fixture.receipt"));
                    Files.createDirectories(output.getParent());
                    try (OutputStream stream = Files.newOutputStream(output)) { receipt.store(stream, "Actual JavaExec probe"); }
                }
            }
            """.trimIndent(),
        )
    }

    /**
     * Reads only the real child-process receipt produced after all production `doFirst` actions.
     */
    private fun receipt(collector: String): Properties = Properties().apply { Files.newBufferedReader(directory.resolve("quality/${descriptors.getValue(collector).module}/build/probes/$collector.properties")).use(::load) }

    /**
     * Uses each task's real module owner rather than relocating its working directory to the root.
     */
    private fun collectorPath(collector: String): String = ":quality:${descriptors.getValue(collector).module}:$collector"

    /**
     * Creates a real immutable input archive before Gradle resolves the fixture classpath.
     */
    private fun archive(
        relative: String,
        resource: String,
        value: String,
    ): Path {
        val target = directory.resolve(relative)
        Files.createDirectories(target.parent)
        JarOutputStream(Files.newOutputStream(target)).use {
            it.putNextEntry(JarEntry(resource))
            it.write(value.toByteArray())
            it.closeEntry()
        }
        return target
    }

    /**
     * Writes authored fixture build and probe sources.
     */
    private fun write(
        relative: String,
        text: String,
    ) {
        val target = directory.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    /**
     * Keeps nested configuration and untimed probes finite without running a benchmark or game.
     */
    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(directory.toFile()).withArguments(
            *arguments.map { if (it in collectors) collectorPath(it) else it }.toTypedArray(),
            "--max-workers=1",
            "--no-parallel",
            "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m",
            "--stacktrace",
        )

    /**
     * Names the real collectors and their independently expected child-process contracts.
     */
    private enum class Collector(
        val taskName: String,
        val module: String,
        val selector: String,
    ) {
        Historical("jmhHistorical", "benchmarks", ".*"),
        Remote("jmhRemote", "remote-benchmarks", "RemoteProtocolBenchmark.*"),
        Components("jmhComponents", "component-benchmarks", "ComponentRenderingBenchmark.*"),
        PortableTiles("jmhPortableTiles", "benchmarks", "PortableTileBenchmark.partition"),
        ;

        val jvmArguments: List<String>
            get() = if (this == Components) listOf("-jvmArgsAppend", "--enable-native-access=ALL-UNNAMED") else emptyList()

        val inputFile: String
            get() = if (this == PortableTiles) "portable-tile-inputs.properties" else "control-inputs.properties"

        val commonContent: String
            get() = if (this == Historical) "frozen" else "ordinary"

        val fabricContent: String?
            get() = if (this == PortableTiles) "explicit" else null
    }
}
