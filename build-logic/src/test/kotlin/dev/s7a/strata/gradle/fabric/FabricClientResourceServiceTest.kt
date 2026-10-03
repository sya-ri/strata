package dev.s7a.strata.gradle.fabric

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exercise actual Gradle completion delivery so failed client tasks cannot leak an admission slot.
 */
internal class FabricClientResourceServiceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `failed task releases admission before the next task runs`() {
        Files.writeString(directory.resolve("settings.gradle.kts"), "rootProject.name = \"client-admission-fixture\"")
        Files.writeString(
            directory.resolve("build.gradle.kts"),
            """
            import dev.s7a.strata.gradle.fabric.FabricClientResourceService
            import org.gradle.build.event.BuildEventsListenerRegistry
            import javax.inject.Inject

            plugins { id("dev.s7a.strata.release") }
            abstract class Events {
                @get:Inject abstract val registry: BuildEventsListenerRegistry
            }
            val service = gradle.sharedServices.registerIfAbsent("clientResources", FabricClientResourceService::class) {
                parameters.maximum.set(1)
                parameters.clientHeap.set("1k")
                maxParallelUsages.set(1)
            }
            objects.newInstance(Events::class.java).registry.onTaskCompletion(service)
            tasks.register("failedClient") {
                usesService(service)
                doLast { service.get().acquire(path); throw GradleException("Expected client failure") }
            }
            tasks.register("nextClient") {
                usesService(service)
                mustRunAfter("failedClient")
                doLast { service.get().acquire(path) }
            }
            """.trimIndent(),
        )
        val result =
            GradleRunner
                .create()
                .withProjectDir(directory.toFile())
                .withPluginClasspath()
                .withArguments("failedClient", "nextClient", "--continue", "--offline", "--max-workers=2")
                .buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":failedClient")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":nextClient")?.outcome)
    }
}
