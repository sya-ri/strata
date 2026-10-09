import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar

// Export resolved existing task inputs, without introducing another benchmark task.
gradle.projectsEvaluated {
    val benchmark = rootProject.findProject(":quality:benchmarks") ?: return@projectsEvaluated
    val runtimeMetadata = rootProject.providers.gradleProperty("strata.cpuCampaign.runtimeMetadata").orNull
    if (runtimeMetadata != null) {
        val archives = listOf(":api", ":runtime:core", ":runtime:headless").associateWith { path ->
            rootProject.project(path).tasks.withType(Jar::class.java).single { it.name == "jvmJar" || it.name == "jar" }
        }
        val export = archives.getValue(":runtime:headless")
        export.dependsOn(archives.values.filter { it != export })
        // Export the current resolved paths even when every archive already exists.
        export.outputs.upToDateWhen { false }
        export.outputs.cacheIf { false }
        export.doLast {
            val metadata = rootProject.file(runtimeMetadata)
            metadata.parentFile.mkdirs()
            metadata.writeText(archives.entries.joinToString("\n", postfix = "\n") { (path, task) -> "runtime\t$path\t${task.archiveFile.get().asFile.canonicalPath}" })
        }
    } else {
        val export = benchmark.tasks.named("jmhCompileGeneratedClasses").get()
        export.dependsOn(":quality:benchmarks:jmhRunBytecodeGenerator")
        export.outputs.upToDateWhen { false }
        export.outputs.cacheIf { false }
        export.doLast {
            val metadata = rootProject.file(rootProject.providers.gradleProperty("strata.cpuCampaign.metadata").get())
            val historical = benchmark.tasks.named("jmhHistorical", JavaExec::class.java).get()
            val lines = historical.classpath.files.map { "classpath\t${it.canonicalPath}" }.toMutableList()
            val runtimeProjects = setOf(":api", ":runtime:core", ":runtime:headless")
            val artifacts = benchmark.configurations.getByName("jmhRuntimeClasspath").incoming.artifacts.artifacts
            val runtime = artifacts.filter { (it.id.componentIdentifier as? ProjectComponentIdentifier)?.projectPath in runtimeProjects }
            require(runtime.map { (it.id.componentIdentifier as ProjectComponentIdentifier).projectPath }.toSet() == runtimeProjects)
            runtime.forEach { artifact ->
                val path = (artifact.id.componentIdentifier as ProjectComponentIdentifier).projectPath
                lines += "runtime\t$path\t${artifact.file.canonicalPath}"
            }
            artifacts.forEach { artifact ->
                val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                if (module != null) lines += "control\t${module.group}:${module.module}:${module.version}:${artifact.file.name}\t${artifact.file.canonicalPath}"
            }
            metadata.parentFile.mkdirs()
            metadata.writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }
}
