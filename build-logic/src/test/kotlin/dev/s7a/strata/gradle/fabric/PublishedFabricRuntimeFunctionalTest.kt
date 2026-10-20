package dev.s7a.strata.gradle.fabric

import groovy.json.JsonOutput
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exercises the production coordinate verifier with a local Maven fixture without loading Minecraft.
 */
internal class PublishedFabricRuntimeFunctionalTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `published runtime accepts both environments and rejects incomplete server integration`() {
        val artifactDirectory = prepareRepository()
        val nested = listOf("api", "core", "headless", "minecraft", "minecraft-fonts-lwjgl", "remote").map { "META-INF/jars/$it-0.3.0.jar" }
        val entrypoints =
            mapOf(
                "main" to listOf("dev.s7a.strata.runtime.minecraft.fabric.StrataFabric"),
                "client" to listOf("dev.s7a.strata.runtime.minecraft.fabric.StrataFabricClient"),
            )
        val metadata =
            mapOf(
                "id" to "strata",
                "version" to "0.3.0",
                "name" to "Strata",
                "environment" to "*",
                "license" to "MIT",
                "contact" to mapOf("homepage" to "https://gh.s7a.dev/strata/"),
                "entrypoints" to entrypoints,
                "depends" to mapOf("minecraft" to "26.3", "fabric-api" to ">=0"),
                "jars" to nested.map { mapOf("file" to it) },
            )
        val artifact = artifactDirectory.resolve("strata-runtime-minecraft-fabric-26.3-0.3.0.jar")

        val runner =
            GradleRunner.create().withProjectDir(directory.toFile()).withArguments(
                "verifyPublishedRuntimeCoordinate",
                "-Pstrata.releaseRepository=${directory.resolve("repository").toUri()}",
                "--refresh-dependencies",
                "--max-workers=1",
                "-Dorg.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=256m",
            )
        writeArtifact(artifact, nested, metadata)
        assertEquals(TaskOutcome.SUCCESS, runner.build().task(":verifyPublishedRuntimeCoordinate")?.outcome)
        assertEquals(-1L, Files.mismatch(artifact, directory.resolve("build/published-runtime/${artifact.fileName}")))
        val rejected =
            listOf(
                metadata + ("environment" to "client") to "both physical environments",
                metadata + ("entrypoints" to (entrypoints - "main")) to "server and client entrypoints",
                metadata + ("depends" to mapOf("minecraft" to "26.3")) to "require Fabric API",
            )
        rejected.forEach { (values, diagnostic) ->
            writeArtifact(artifact, nested, values)
            val failed = runner.buildAndFail()
            assertEquals(TaskOutcome.FAILED, failed.task(":verifyPublishedRuntimeCoordinate")?.outcome)
            assertTrue(failed.output.contains(diagnostic), failed.output)
        }
    }

    /**
     * Creates a local Maven coordinate and applies the production artifact verifier.
     */
    private fun prepareRepository(): Path {
        val repository =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("integration/minecraft-fabric-published-runtime.gradle.kts")) }
        Files.copy(repository.resolve("integration/minecraft-fabric-published-runtime.gradle.kts"), directory.resolve("published-runtime.gradle.kts"))
        Files.writeString(directory.resolve("settings.gradle.kts"), "rootProject.name = \"minecraft-fabric-26.3\"\n")
        Files.writeString(
            directory.resolve("build.gradle.kts"),
            """
            plugins { base }
            version = "0.3.0"
            repositories { maven { url = uri("repository") } }
            apply(from = "published-runtime.gradle.kts")
            """.trimIndent(),
        )
        val artifactDirectory = directory.resolve("repository/dev/s7a/strata/strata-runtime-minecraft-fabric-26.3/0.3.0")
        Files.createDirectories(artifactDirectory)
        Files.writeString(
            artifactDirectory.resolve("strata-runtime-minecraft-fabric-26.3-0.3.0.pom"),
            "<project><modelVersion>4.0.0</modelVersion><groupId>dev.s7a.strata</groupId>" +
                "<artifactId>strata-runtime-minecraft-fabric-26.3</artifactId><version>0.3.0</version></project>",
        )
        return artifactDirectory
    }

    /**
     * Rewrites the local published artifact so every metadata mutation reaches the real verifier.
     */
    private fun writeArtifact(
        artifact: Path,
        nested: List<String>,
        values: Map<String, Any>,
    ) {
        ZipOutputStream(Files.newOutputStream(artifact)).use { archive ->
            (mapOf("fabric.mod.json" to JsonOutput.toJson(values), "META-INF/LICENSE-strata" to "MIT") + nested.associateWith { "fixture" })
                .forEach { (path, text) ->
                    archive.putNextEntry(ZipEntry(path))
                    archive.write(text.toByteArray())
                    archive.closeEntry()
                }
        }
    }
}
