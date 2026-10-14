package dev.s7a.strata.gradle.performance

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.JavaExec
import java.io.File
import java.util.Properties

/**
 * Configures the shared JMH argument protocol and execution-time external-library inventory.
 * Callers own fixture selection, validation, launchers, native flags and runtime archive substitution.
 * Register runtime substitution after this call so its `doFirst` action precedes inventory capture.
 */
public fun JavaExec.configureJmhEvidence(
    output: File,
    repetition: Int,
    includes: String,
    mode: String,
    shortRun: Boolean,
    inputManifestName: String = "control-inputs.properties",
) {
    args(
        output.absolutePath,
        repetition.toString(),
        includes,
        "-bm",
        mode,
        "-wi",
        if (shortRun) "0" else "3",
        "-w",
        "1s",
        "-i",
        if (shortRun) "1" else "5",
        "-r",
        if (shortRun) "100ms" else "1s",
        "-f",
        "1",
        "-t",
        "1",
        "-tu",
        "us",
        "-foe",
        "true",
        "-prof",
        "gc",
    )
    val inputsManifest = project.layout.buildDirectory.file("performance/$inputManifestName")
    doFirst {
        val entries = Properties()
        project.configurations.getByName("jmhRuntimeClasspath").incoming.artifacts.artifacts.forEach { artifact ->
            val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier
            if (module != null) {
                val label = "${module.group}:${module.module}:${module.version}:${artifact.file.name}"
                require(entries.setProperty(label, artifact.file.absolutePath) == null) { "Duplicate resolved control library: $label" }
            }
        }
        require(entries.isNotEmpty()) { "The JMH control library inventory is missing" }
        val manifest = inputsManifest.get().asFile
        manifest.parentFile.mkdirs()
        manifest.bufferedWriter(Charsets.UTF_8).use { entries.store(it, "Resolved non-Strata control libraries") }
        systemProperty("strata.performance.inputs", manifest.absolutePath)
    }
}
