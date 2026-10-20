package dev.s7a.strata.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exercises the complete production init and real inherited JVM arguments without benchmark timing.
 */
internal class BoundedJvmClassLoadFunctionalTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `actual JavaExec and inherited child preserve complete PID logs outside console`() {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("gradle/libs.versions.toml")) }
        val workspace = directory.resolve("source with spaces")
        val logs = directory.resolve("class logs with spaces")
        val vmLogs = directory.resolve("VM logs with spaces")
        prepareWorkspace(workspace)
        Files.createDirectories(logs)
        Files.createDirectories(vmLogs)
        val environment = System.getenv().toMutableMap()
        environment["STRATA_PAIRED_WORKSPACE"] = workspace.toString()
        environment["STRATA_PAIRED_CLASS_LOGS"] = logs.toString()
        environment["STRATA_PAIRED_LOGS"] = vmLogs.toString()
        val result =
            GradleRunner
                .create()
                .withProjectDir(workspace.toFile())
                .withEnvironment(environment)
                .withArguments(
                    "verifyInheritedLogger",
                    "-I",
                    repository.resolve("gradle/performance/bounded-jvm.init.gradle.kts").toString(),
                    "--max-workers=1",
                    "--no-parallel",
                    "-Pkotlin.compiler.execution.strategy=in-process",
                    "-Pdetekt.use.worker.api=false",
                    "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=1g",
                    "--stacktrace",
                ).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyInheritedLogger")?.outcome)
        assertTrue(result.output.contains("[class,load]").not(), result.output)
        val pids = Regex("loaded-pid=([0-9]+)").findAll(result.output).map { it.groupValues[1] }.toSet()
        assertEquals(2, pids.size)
        val files = Files.list(logs).use { it.toList() }
        assertEquals(2, files.size)
        files.forEach { file ->
            val pid =
                file.fileName
                    .toString()
                    .substringAfter("classes-")
                    .substringBefore('-')
            val text = Files.readString(file)
            assertTrue(pid in pids)
            assertTrue(text.contains("[$pid]") && text.contains("fixture.Probe source:"), text)
            assertTrue(text.endsWith("\n") && Files.size(file) <= 1024 * 1024)
        }
    }

    /**
     * Creates the working JavaExec project and its deliberately spaced execution directory.
     */
    private fun prepareWorkspace(workspace: Path) {
        Files.createDirectories(workspace.resolve("src/main/java/fixture"))
        Files.createDirectories(workspace.resolve("execution with spaces"))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "rootProject.name = \"class-load-transport\"\n")
        Files.writeString(
            workspace.resolve("build.gradle.kts"),
            """
            plugins { java }
            tasks.register<JavaExec>("verifyInheritedLogger") {
                dependsOn("classes")
                classpath = sourceSets["main"].runtimeClasspath
                mainClass.set("fixture.Probe")
                workingDir(layout.projectDirectory.dir("execution with spaces").asFile)
            }
            """.trimIndent(),
        )
        writeProbe(workspace)
    }

    /**
     * Writes a real Java process that forwards inherited JVM arguments to its child.
     */
    private fun writeProbe(workspace: Path) {
        Files.writeString(
            workspace.resolve("src/main/java/fixture/Probe.java"),
            """
            package fixture;
            import java.lang.management.ManagementFactory;
            import java.nio.file.Path;
            import java.util.ArrayList;
            public class Probe {
                public static void main(String[] args) throws Exception {
                    System.out.println("loaded-pid=" + ProcessHandle.current().pid());
                    if (args.length == 0) {
                        ArrayList<String> command = new ArrayList<>();
                        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
                        command.add("-cp");
                        command.add(System.getProperty("java.class.path"));
                        command.add("fixture.Probe");
                        command.add("child");
                        int exit = new ProcessBuilder(command).inheritIO().start().waitFor();
                        if (exit != 0) throw new IllegalStateException("Inherited logger child failed: " + exit);
                    }
                }
            }
            """.trimIndent(),
        )
    }
}
