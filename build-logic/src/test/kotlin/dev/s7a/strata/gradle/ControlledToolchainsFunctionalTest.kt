package dev.s7a.strata.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Exercises the real init script against Gradle-selected compiler and launcher metadata. */
internal class ControlledToolchainsFunctionalTest {
    @TempDir
    lateinit var directory: Path

    private val repository =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("gradle/controlled-toolchains.init.gradle")) }

    private lateinit var binding: MutableMap<String, Any?>
    private lateinit var jdk: MutableMap<String, Any?>

    /** Captures actual metadata through Gradle before any controlled fixture execution. */
    @BeforeEach
    fun prepare() {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'controlled-fixture'\n")
        Files.writeString(directory.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=256m\n")
        Files.createDirectories(directory.resolve("src/main/java"))
        Files.writeString(
            directory.resolve("src/main/java/Fixture.java"),
            "public class Fixture { public static void main(String[] args) { System.out.println(\"Complete compiled fixture\"); } }\n",
        )
        Files.writeString(
            directory.resolve("build.gradle"),
            """
            import groovy.json.JsonOutput
            plugins { id 'java' }
            tasks.register('captureJdk') {
                doLast {
                    def tool = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(JavaVersion.current().majorVersion.toInteger()) }.get()
                    def metadata = tool.metadata
                    def suffix = tool.executablePath.asFile.name.endsWith('.exe') ? '.exe' : ''
                    file('actual-jdk.json').text = JsonOutput.toJson([
                        home: metadata.installationPath.asFile.canonicalPath, major: metadata.languageVersion.asInt(),
                        vendor: metadata.vendor, runtime_version: metadata.javaRuntimeVersion, jvm_version: metadata.jvmVersion,
                        implementor: System.getProperty('java.vendor'), java: tool.executablePath.asFile.canonicalPath,
                        javac: new File(metadata.installationPath.asFile, "bin/javac${'$'}suffix").canonicalPath,
                        gradle_version: gradle.gradleVersion, gradle_installation_home: gradle.gradleHomeDir.canonicalPath
                    ])
                }
            }
            tasks.register('runFixture', JavaExec) {
                dependsOn classes
                classpath = sourceSets.main.runtimeClasspath
                mainClass = 'Fixture'
                maxHeapSize = '128m'
                jvmArgs '-XX:MaxMetaspaceSize=128m'
            }
            """.trimIndent(),
        )
        runner("captureJdk").build()
        jdk = readMap(directory.resolve("actual-jdk.json"))
        binding =
            mutableMapOf(
                "root" to directory.toString(),
                "jvm_only" to false,
                "profile_sha256" to "functional-control",
                "receipts" to directory.resolve("selected").toString(),
                "profile" to
                    mutableMapOf(
                        "jdks" to listOf(jdk),
                        "daemon_major" to jdk.getValue("major"),
                        "gradle_version" to jdk.getValue("gradle_version"),
                        "gradle_installation_home" to jdk.getValue("gradle_installation_home"),
                    ),
                "properties" to
                    mapOf(
                        "org.gradle.java.home" to jdk.getValue("home"),
                        "org.gradle.java.installations.auto-detect" to "false",
                        "org.gradle.java.installations.auto-download" to "false",
                        "org.gradle.java.installations.paths" to jdk.getValue("home"),
                        "org.gradle.java.installations.fromEnv" to "",
                    ),
            )
    }

    @Test
    fun `complete compiler and launcher execution records actual identities and graph`() {
        val result = controlled("runFixture", extra = listOf("test")).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":runFixture")?.outcome)
        assertEquals(TaskOutcome.NO_SOURCE, result.task(":test")?.outcome)
        assertTrue(result.output.contains("Complete compiled fixture"), result.output)
        val receipt = receipts().single()
        assertEquals("passed", receipt["status"])
        val tools = receipt["tools"] as List<*>
        val roles = tools.map { (it as Map<*, *>)["role"] }.toSet()
        assertEquals(setOf("compiler", "launcher"), roles)
        assertTrue(tools.all { (it as Map<*, *>)["vendor"] == jdk["vendor"] })
        assertTrue(tools.all { (it as Map<*, *>)["runtime_version"] == jdk["runtime_version"] })
        assertTrue(tools.any { (it as Map<*, *>)["task"] == ":test" && it["role"] == "launcher" })
        val tasks = receipt["tasks"] as List<*>
        assertTrue(tasks.any { (it as Map<*, *>)["path"] == ":runFixture" && it["did_work"] == true })
    }

    @Test
    fun `vendor runtime and executable fallback fail before fixture execution`() {
        listOf("vendor", "runtime_version", "java", "javac").forEach { field ->
            val original = jdk.getValue(field)
            jdk[field] = if (field in setOf("java", "javac")) directory.resolve("unbound-java").toString() else "unbound-metadata"
            val result = controlled("runFixture").buildAndFail()
            assertTrue(result.output.contains("Controlled toolchain refusal"), result.output)
            assertFalse(result.output.contains("Complete compiled fixture"), result.output)
            clearReceipts()
            jdk[field] = original
        }
    }

    @Test
    fun `unbound daemon and overridden installation properties are refused`() {
        val result = controlled("runFixture", extra = listOf("-Dorg.gradle.java.installations.auto-detect=true")).buildAndFail()
        assertTrue(result.output.contains("standard Java installation property differs"), result.output)
        assertFalse(result.output.contains("Complete compiled fixture"), result.output)
        jdk["home"] = directory.resolve("other-jdk").toString()
        val daemon = controlled("runFixture").buildAndFail()
        assertTrue(daemon.output.contains("actual Gradle daemon identity differs"), daemon.output)
        assertFalse(daemon.output.contains("Complete compiled fixture"), daemon.output)
    }

    @Test
    fun `explicit task executable override is refused and ordinary builds remain usable`() {
        Files.writeString(
            directory.resolve("build.gradle"),
            "\n" +
                """
                tasks.named('runFixture') {
                    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(JavaVersion.current().majorVersion.toInteger()) }
                    executable = '${directory.resolve("unbound-java").toString().replace('\\', '/')}'
                }
                """.trimIndent() + "\n",
            StandardOpenOption.APPEND,
        )
        val result = controlled("runFixture").buildAndFail()
        assertTrue(result.output.contains("Java executable override"), result.output)
        assertFalse(result.output.contains("Complete compiled fixture"), result.output)
        assertEquals(TaskOutcome.SUCCESS, runner("compileJava").build().task(":compileJava")?.outcome)
    }

    @Test
    fun `included Kotlin compiler preserves separate build and task identities`() {
        val included = directory.resolve("compiler-fixture")
        Files.createDirectories(included.resolve("src/main/kotlin"))
        Files.writeString(included.resolve("settings.gradle"), "rootProject.name = 'compiler-fixture'\n")
        Files.writeString(included.resolve("gradle.properties"), "kotlin.compiler.execution.strategy=in-process\n")
        Files.writeString(included.resolve("src/main/kotlin/Compiled.kt"), "fun compiledFixture(): Int = 42\n")
        val catalog = Files.readString(repository.resolve("gradle/libs.versions.toml"))
        val kotlinVersion = requireNotNull(Regex("^kotlin\\s*=\\s*\"([^\"]+)\"", RegexOption.MULTILINE).find(catalog)).groupValues[1]
        Files.writeString(
            included.resolve("build.gradle"),
            """
            plugins { id 'org.jetbrains.kotlin.jvm' version '$kotlinVersion' }
            repositories { mavenCentral() }
            kotlin { jvmToolchain(${jdk.getValue("major")}) }
            """.trimIndent(),
        )
        Files.writeString(directory.resolve("settings.gradle"), "\nincludeBuild('compiler-fixture')\n", StandardOpenOption.APPEND)
        Files.writeString(directory.resolve("build.gradle"), "\ntasks.named('runFixture') { dependsOn gradle.includedBuild('compiler-fixture').task(':classes') }\n", StandardOpenOption.APPEND)
        val result = controlled("runFixture", extra = listOf("--info", "--console=plain")).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":runFixture")?.outcome)
        val builds = receipts()
        assertEquals(2, builds.size)
        assertEquals(2, builds.map { it.getValue("build_path") }.toSet().size)
        val child = builds.single { Path.of(it.getValue("project_dir").toString()) == included }
        val tasks = child["tasks"] as List<*>
        assertTrue(tasks.any { (it as Map<*, *>)["path"] == ":compileKotlin" && it["did_work"] == true })
        assertTrue(result.output.contains("[KOTLIN] Kotlin compilation 'jdkHome' argument:"), result.output)
    }

    /** Reads every participating build receipt without filtering included build names. */
    private fun receipts(): List<MutableMap<String, Any?>> = Files.list(directory.resolve("selected")).use { paths -> paths.toList().map(::readMap) }

    /** Clears only synthetic previous-case receipt files inside the temporary fixture. */
    private fun clearReceipts() {
        val selected = directory.resolve("selected")
        if (Files.isDirectory(selected)) {
            Files.list(selected).use { paths -> paths.forEach { Files.delete(it) } }
            Files.delete(selected)
        }
    }

    /** Uses the existing TestKit launcher and a fresh invocation manifest. */
    private fun controlled(
        task: String,
        extra: List<String> = emptyList(),
    ): GradleRunner {
        val manifest = directory.resolve("binding.json")
        Files.writeString(manifest, JsonOutput.toJson(binding))
        val properties = binding.getValue("properties") as Map<*, *>
        return runner(task, "--no-configuration-cache", "--init-script", repository.resolve("gradle/controlled-toolchains.init.gradle").toString(), *properties.map { "-D${it.key}=${it.value}" }.toTypedArray(), *extra.toTypedArray())
            .withEnvironment(System.getenv() + mapOf("JAVA_HOME" to requireNotNull(properties["org.gradle.java.home"]).toString(), "STRATA_TOOLCHAIN_BINDING" to manifest.toString()))
    }

    /** Keeps fixture JVM work serial and bounded through ordinary Gradle options. */
    private fun runner(vararg arguments: String): GradleRunner {
        val home = System.getProperty("java.home")
        return GradleRunner
            .create()
            .withProjectDir(directory.toFile())
            .withArguments(
                "-Dorg.gradle.java.home=$home",
                "-Dorg.gradle.java.installations.auto-detect=false",
                "-Dorg.gradle.java.installations.auto-download=false",
                "-Dorg.gradle.java.installations.paths=$home",
                "-Dorg.gradle.java.installations.fromEnv=",
                *arguments,
                "--no-daemon",
                "--no-parallel",
                "--max-workers=1",
                "--stacktrace",
            ).withEnvironment(System.getenv() + mapOf("JAVA_HOME" to home))
    }

    /** Copies parsed JSON into a mutable fixture binding without changing the production reader. */
    private fun readMap(path: Path): MutableMap<String, Any?> {
        val parsed = JsonSlurper().parse(path.toFile()) as Map<*, *>
        return parsed.entries.associate { entry -> entry.key.toString() to entry.value }.toMutableMap()
    }
}
