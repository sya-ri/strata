package dev.s7a.strata.gradle.release

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Generates the canonical versionless Maven artifact inventory from the configured publication projects.
 */
@CacheableTask
internal abstract class GenerateMavenArtifactInventory : DefaultTask() {
    @get:Input
    abstract val artifacts: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    /**
     * Validates [artifacts] and replaces [outputFile] with their declaration-order `group:artifact` representation.
     *
     * The action writes only the task-owned output and fails before publication when the canonical inventory is empty or a non-blank entry is malformed or duplicated.
     */
    @TaskAction
    fun generate() {
        val canonicalArtifacts = MavenReleaseCoordinates.canonicalArtifacts(artifacts.get())
        val destination = outputFile.get().asFile
        check(destination.parentFile.mkdirs() || destination.parentFile.isDirectory) {
            "Unable to create the Maven artifact inventory directory: ${destination.parentFile}"
        }
        destination.writeText(canonicalArtifacts.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
    }
}
