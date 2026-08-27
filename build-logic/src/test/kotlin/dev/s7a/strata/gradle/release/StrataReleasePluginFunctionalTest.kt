package dev.s7a.strata.gradle.release

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Verifies plugin application, lazy target wiring, task dependencies, and generated bundle structure with TestKit. */
internal class StrataReleasePluginFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `manifest task wires twenty one lazy verified targets and canonical output`() {
        prepareFixture()
        Files.writeString(projectDirectory.resolve("build.gradle.kts"), buildScript())

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withArguments("githubReleaseBundle", "-x", "mavenCentralReleaseVerify", "--stacktrace")
                .build()

        GAME_VERSIONS.indices.forEach { index ->
            assertTrue(result.task(":verify$index")?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE))
        }
        assertEquals(TaskOutcome.SUCCESS, result.task(":modrinthReleaseManifest")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":githubReleaseBundle")?.outcome)
        val manifestFile = projectDirectory.resolve("build/release/modrinth/manifest.json").toFile()
        assertTrue(manifestFile.isFile)
        val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
        val artifacts = manifest["artifacts"] as List<*>
        assertEquals(21, artifacts.size)
        artifacts.forEach { value ->
            val artifact = value as Map<*, *>
            assertTrue((artifact["sha256"] as String).matches(Regex("[0-9a-f]{64}")))
            assertTrue((artifact["sha512"] as String).matches(Regex("[0-9a-f]{128}")))
            assertEquals(artifact["fileName"], artifact["githubAssetName"])
            assertTrue((artifact["mavenCoordinate"] as String).startsWith("dev.s7a.strata:"))
        }
        val githubAssets =
            projectDirectory
                .resolve("build/release/github")
                .toFile()
                .listFiles()
                .orEmpty()
        assertEquals(43, githubAssets.size)
        assertEquals(21, githubAssets.count { file -> file.name.endsWith(".jar") })
        assertEquals(21, githubAssets.count { file -> file.name.endsWith(".jar.asc") })
        githubAssets.filter { file -> file.name.endsWith(".jar.asc") }.forEach { file ->
            assertTrue(file.readText().startsWith("central-signature-"))
        }
        assertTrue(0L < githubAssets.single { file -> file.name == "SHA256SUMS" }.length())

        val staleReceipt = projectDirectory.resolve("build/release/modrinth-receipts/preflight.json")
        Files.createDirectories(staleReceipt.parent)
        Files.writeString(staleReceipt, "stale success evidence")
        val failedResult =
            GradleRunner
                .create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withEnvironment(System.getenv().filterKeys { key -> key != "MODRINTH_TOKEN" })
                .withArguments("modrinthReleasePreflight", "--stacktrace")
                .buildAndFail()
        assertEquals(TaskOutcome.UP_TO_DATE, failedResult.task(":modrinthReleaseManifest")?.outcome)
        assertTrue(failedResult.output.contains("MODRINTH_TOKEN is required"))
        assertFalse(Files.exists(staleReceipt))
    }

    @Test
    fun `manifest cache identity preserves ordered target artifact hashes lazily`() {
        prepareFixture()
        Files.writeString(projectDirectory.resolve("build.gradle.kts"), buildScript())

        val lazyConfiguration =
            GradleRunner
                .create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withArguments("help", "-PfailArtifactResolution=true", "--stacktrace")
                .build()
        assertEquals(TaskOutcome.SUCCESS, lazyConfiguration.task(":help")?.outcome)

        val first = runManifestWithBuildCache()
        assertEquals(TaskOutcome.SUCCESS, first.task(":modrinthReleaseManifest")?.outcome)
        val initialHashes = manifestArtifactHashes()

        Files.writeString(projectDirectory.resolve("build.gradle.kts"), buildScript(swapFirstArtifacts = true))
        projectDirectory.resolve("build/release/modrinth").toFile().deleteRecursively()
        val swapped = runManifestWithBuildCache()
        assertEquals(TaskOutcome.SUCCESS, swapped.task(":modrinthReleaseManifest")?.outcome)
        val swappedHashes = manifestArtifactHashes()
        assertEquals(initialHashes.getValue(GAME_VERSIONS[1]), swappedHashes.getValue(GAME_VERSIONS[0]))
        assertEquals(initialHashes.getValue(GAME_VERSIONS[0]), swappedHashes.getValue(GAME_VERSIONS[1]))

        projectDirectory.resolve("build/release/modrinth").toFile().deleteRecursively()
        val repeated = runManifestWithBuildCache()
        assertEquals(TaskOutcome.FROM_CACHE, repeated.task(":modrinthReleaseManifest")?.outcome)

        val githubFirst = runGithubWithBuildCache()
        assertEquals(TaskOutcome.SUCCESS, githubFirst.task(":githubReleaseBundle")?.outcome)
        projectDirectory.resolve("build/release/github").toFile().deleteRecursively()
        val githubCached = runGithubWithBuildCache()
        assertEquals(TaskOutcome.FROM_CACHE, githubCached.task(":githubReleaseBundle")?.outcome)

        val canonicalArtifact =
            projectDirectory.resolve(
                "build/release/modrinth/artifacts/strata-runtime-minecraft-fabric-${GAME_VERSIONS[0]}-0.1.1.jar",
            )
        val mutatedArtifact = Files.readAllBytes(canonicalArtifact)
        mutatedArtifact[0] = (mutatedArtifact[0] + 1).toByte()
        Files.write(canonicalArtifact, mutatedArtifact)
        projectDirectory.resolve("build/release/github").toFile().deleteRecursively()
        val mutation =
            GradleRunner
                .create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withArguments(
                    "githubReleaseBundle",
                    "-x",
                    "mavenCentralReleaseVerify",
                    "-x",
                    "modrinthReleaseManifest",
                    "--build-cache",
                    "--stacktrace",
                ).buildAndFail()
        assertEquals(TaskOutcome.FAILED, mutation.task(":githubReleaseBundle")?.outcome)
        assertTrue(mutation.output.contains("differs from manifest SHA-256"))
    }

    private fun prepareFixture() {
        Files.writeString(
            projectDirectory.resolve("settings.gradle.kts"),
            """
            rootProject.name = "release-fixture"
            buildCache {
                local {
                    directory = file(".test-build-cache")
                }
            }
            """.trimIndent() + "\n",
        )
        val icon = write("icon.png", "icon")
        val gallery =
            listOf("overview", "inventory", "progress").map { id ->
                id to write("docs/components/$id.png", "gallery-$id")
            }
        write("release-notes.md", "# Fixture release\n")
        write("project-body.md", "# Strata\n\nCompiled fixture example.\n")
        write("project.json", projectMetadata(icon, gallery))
        GAME_VERSIONS.forEachIndexed { index, gameVersion ->
            write("inputs/$gameVersion.jar", "artifact-$index-$gameVersion")
            write(
                "build/release/maven-central/signatures/strata-runtime-minecraft-fabric-$gameVersion-0.1.1.jar.asc",
                "central-signature-$index-$gameVersion",
            )
            write("inputs/local-$gameVersion.jar.asc", "different-local-signature-$index-$gameVersion")
        }
    }

    private fun runManifestWithBuildCache() =
        GradleRunner
            .create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withArguments("modrinthReleaseManifest", "--build-cache", "--stacktrace")
            .build()

    private fun runGithubWithBuildCache() =
        GradleRunner
            .create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withArguments("githubReleaseBundle", "-x", "mavenCentralReleaseVerify", "--build-cache", "--stacktrace")
            .build()

    private fun manifestArtifactHashes(): Map<String, String> {
        val manifest = JsonSlurper().parse(projectDirectory.resolve("build/release/modrinth/manifest.json").toFile()) as Map<*, *>
        val artifacts = manifest["artifacts"] as List<*>
        return artifacts.associate { value ->
            val artifact = value as Map<*, *>
            (artifact["gameVersion"] as String) to (artifact["sha256"] as String)
        }
    }

    private fun buildScript(swapFirstArtifacts: Boolean = false): String =
        buildString {
            appendLine("import dev.s7a.strata.gradle.release.StrataReleaseExtension")
            appendLine("plugins { id(\"dev.s7a.strata.release\") }")
            appendLine("version = \"0.1.1\"")
            GAME_VERSIONS.indices.forEach { index -> appendLine("tasks.register(\"verify$index\")") }
            appendLine("extensions.configure<StrataReleaseExtension> {")
            appendLine("  modrinthProjectId.set(\"project-id\")")
            appendLine("  releaseVersion.set(\"0.1.1\")")
            appendLine("  releaseNotesFile.set(layout.projectDirectory.file(\"release-notes.md\"))")
            appendLine("  modrinthProjectMetadataFile.set(layout.projectDirectory.file(\"project.json\"))")
            appendLine("  modrinthProjectBodyFile.set(layout.projectDirectory.file(\"project-body.md\"))")
            appendLine("  projectAssetFiles.from(\"icon.png\", \"docs/components/overview.png\", \"docs/components/inventory.png\", \"docs/components/progress.png\")")
            GAME_VERSIONS.forEachIndexed { index, gameVersion ->
                val artifactGameVersion =
                    when {
                        swapFirstArtifacts && index == 0 -> GAME_VERSIONS[1]
                        swapFirstArtifacts && index == 1 -> GAME_VERSIONS[0]
                        else -> gameVersion
                    }
                appendLine("  target(")
                appendLine("    gameVersion = \"$gameVersion\",")
                appendLine("    canonicalFileName = \"strata-runtime-minecraft-fabric-$gameVersion-0.1.1.jar\",")
                appendLine("    artifact = providers.provider {")
                appendLine("      check(providers.gradleProperty(\"failArtifactResolution\").orNull != \"true\")")
                appendLine("      layout.projectDirectory.file(\"inputs/$artifactGameVersion.jar\")")
                appendLine("    },")
                appendLine("    verificationTaskPath = \":verify$index\",")
                appendLine("  )")
            }
            appendLine("}")
        }

    private fun projectMetadata(
        icon: ByteArray,
        gallery: List<Pair<String, ByteArray>>,
    ): String =
        JsonOutput.prettyPrint(
            JsonOutput.toJson(
                linkedMapOf(
                    "projectId" to "project-id",
                    "slug" to "strata-ui",
                    "title" to "Strata",
                    "description" to ModrinthManifest.PROJECT_DESCRIPTION,
                    "categories" to listOf("library"),
                    "additionalCategories" to listOf("utility"),
                    "licenseId" to "MIT",
                    "clientSide" to "required",
                    "serverSide" to "unsupported",
                    "sourceUrl" to ModrinthManifest.SOURCE_URL,
                    "issuesUrl" to ModrinthManifest.ISSUES_URL,
                    "documentationUrl" to ModrinthManifest.DOCUMENTATION_URL,
                    "aiDisclosureNote" to ModrinthManifest.AI_DISCLOSURE_NOTE,
                    "aiDisclosureUses" to listOf("code", "text"),
                    "icon" to mapOf("path" to "icon.png", "sha256" to icon.hash()),
                    "gallery" to
                        gallery.mapIndexed { index, entry ->
                            val (id, bytes) = entry
                            linkedMapOf(
                                "id" to id,
                                "path" to "docs/components/$id.png",
                                "sha256" to bytes.hash(),
                                "featured" to (index == 0),
                                "title" to id.replaceFirstChar(Char::uppercase),
                                "description" to "$id gallery",
                                "ordering" to index,
                            )
                        },
                ),
            ),
        ) + "\n"

    private fun write(
        relativePath: String,
        content: String,
    ): ByteArray {
        val path = projectDirectory.resolve(relativePath)
        Files.createDirectories(path.parent)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        Files.write(path, bytes)
        return bytes
    }

    private fun ByteArray.hash(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private val GAME_VERSIONS =
            listOf(
                "1.20",
                "1.20.1",
                "1.20.2",
                "1.20.3",
                "1.20.4",
                "1.20.5",
                "1.20.6",
                "1.21",
                "1.21.1",
                "1.21.2",
                "1.21.3",
                "1.21.4",
                "1.21.5",
                "1.21.6",
                "1.21.7",
                "1.21.8",
                "1.21.9",
                "1.21.10",
                "1.21.11",
                "26.1",
                "26.2",
            )
    }
}
