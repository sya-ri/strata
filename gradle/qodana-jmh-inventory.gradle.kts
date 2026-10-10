import groovy.json.JsonOutput
import java.io.File
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Reads Git's selected revision and tracked membership without consulting generated IDEA metadata. */
fun gitOutput(vararg arguments: String): String =
    providers.exec {
        workingDir(rootDir)
        commandLine("git", *arguments)
    }.standardOutput.asText.get()

/** Hashes current bytes; declaration evidence cannot be restored from the build cache. */
fun File.sha256(): String {
    val bytes = if (isDirectory) {
        val files = walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(this).invariantSeparatorsPath }.associate { it.relativeTo(this).invariantSeparatorsPath to it.sha256() }
        JsonOutput.toJson(files).toByteArray(Charsets.UTF_8)
    } else {
        readBytes()
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Returns a canonical repository path, rejecting linked roots outside the selected checkout. */
fun File.repositoryPath(): String {
    val root = rootDir.canonicalFile.toPath()
    val path = canonicalFile.toPath()
    require(path.startsWith(root) && path != root) { "Declaration path must be inside the repository: $this" }
    return root.relativize(path).toString().replace(File.separatorChar, '/')
}

tasks.register("qodanaDeclarationInventory") {
    group = "verification"
    description = "Recreates revision-bound JMH owner, root, SDK and dependency expectations from Gradle declarations."
    notCompatibleWithConfigurationCache("Reads the complete configured project and resolution model.")
    outputs.file(layout.buildDirectory.file("qodana/declarations.json"))
    outputs.upToDateWhen { false }
    dependsOn(provider {
        allprojects.filter { it.plugins.hasPlugin("me.champeau.jmh") }.map { it.tasks.named("jmhClasses") }
    })
    mustRunAfter(provider { allprojects.flatMap { it.tasks.matching { task -> task.name == "ideaModule" }.toList() } })
    doLast {
        require(gitOutput("diff", "--name-only", "HEAD").isBlank()) { "Declaration acceptance requires the selected revision's clean tracked sources." }
        val tracked = gitOutput("ls-files", "-z").split('\u0000').filter(String::isNotEmpty).sorted().associateWith { file(it).sha256() }
        val projects = allprojects.sortedBy { it.path }.map { owner ->
            val idea = owner.extensions.findByType<IdeaModel>()?.module
            val sourceSets = owner.extensions.findByType<SourceSetContainer>()
            val kotlin = owner.extensions.findByType<KotlinJvmProjectExtension>()
            val roots = sourceSets?.flatMap { sourceSet ->
                val inputs = kotlin?.target?.compilations?.findByName(sourceSet.name)?.allKotlinSourceSets.orEmpty()
                val test = sourceSet.name != "main"
                listOf(
                    (if (test) "TestSource" else "Source") to (sourceSet.java.srcDirs + inputs.flatMap { it.kotlin.srcDirs }),
                    (if (test) "TestResource" else "Resource") to (sourceSet.resources.srcDirs + inputs.flatMap { it.resources.srcDirs }),
                ).flatMap { (kind, directories) -> directories.map { mapOf("kind" to kind, "path" to it.repositoryPath()) } }
            }.orEmpty().distinct()
            mapOf(
                "project" to owner.path,
                "module" to idea?.name,
                "directory" to owner.projectDir.takeUnless { it.canonicalFile == rootDir.canonicalFile }?.repositoryPath(),
                "roots" to roots,
                "jmh" to (owner.plugins.hasPlugin("me.champeau.jmh") && sourceSets?.findByName("jmh") != null),
            )
        }
        val owners = allprojects.filter { it.plugins.hasPlugin("me.champeau.jmh") }.sortedBy { it.path }.map { owner ->
            val sourceSets = owner.extensions.getByType<SourceSetContainer>()
            val kotlin = owner.extensions.findByType<KotlinJvmProjectExtension>()
            val idea = owner.extensions.getByType<IdeaModel>().module
            val jmh = sourceSets.getByName("jmh")
            val java = owner.extensions.getByType<JavaPluginExtension>().toolchain.languageVersion.get().asInt()
            val compiler = owner.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(owner.extensions.getByType<JavaPluginExtension>().toolchain.languageVersion)
            }.get().metadata
            val roots = listOf("main", "test", "jmh").flatMap { sourceSetName ->
                val sourceSet = sourceSets.getByName(sourceSetName)
                val kotlinSets = kotlin?.target?.compilations?.findByName(sourceSetName)?.allKotlinSourceSets.orEmpty()
                val inputs = if (sourceSetName == "main") kotlinSets else kotlinSets - kotlin?.target?.compilations?.findByName("main")?.allKotlinSourceSets.orEmpty()
                val sourceRoots = sourceSet.java.srcDirs + inputs.flatMap { it.kotlin.srcDirs }
                val resourceRoots = sourceSet.resources.srcDirs + inputs.flatMap { it.resources.srcDirs }
                val test = sourceSetName != "main"
                listOf((if (test) "TestSource" else "Source") to sourceRoots, (if (test) "TestResource" else "Resource") to resourceRoots).flatMap { (kind, directories) ->
                    directories.sortedBy { it.repositoryPath() }.map { directory ->
                        val generated = directory.canonicalFile.toPath().startsWith(owner.layout.buildDirectory.get().asFile.canonicalFile.toPath())
                        // Authored declarations under build must fail rather than disappear inside IDEA's default exclusion.
                        require(generated.not() || directory.walkTopDown().none { it.isFile && it.extension in setOf("kt", "java") }) {
                            "JMH declarations must not hide authored compiler inputs under build: $directory"
                        }
                        val files = if (directory.isDirectory) directory.walkTopDown().filter { it.isFile }.sortedBy { it.repositoryPath() }.associate { it.repositoryPath() to it.sha256() } else emptyMap()
                        require(tracked.keys.containsAll(files.keys)) { "Authored JMH model roots must belong to the selected revision: ${owner.path}: $directory" }
                        mapOf(
                            "sourceSet" to sourceSetName,
                            "kind" to kind,
                            "path" to directory.repositoryPath(),
                            "exists" to directory.isDirectory,
                            "generated" to generated,
                            "files" to files,
                        )
                    }
                }
            }.distinctBy { it["kind"] to it["path"] }
            val ownOutputs = sourceSets.flatMap { it.output.files }.map { it.canonicalFile }.toSet()
            val artifacts = listOf("main", "test", "jmh").flatMap { sourceSetName ->
                val sourceSet = sourceSets.getByName(sourceSetName)
                listOf(sourceSet.compileClasspathConfigurationName, sourceSet.runtimeClasspathConfigurationName).flatMap { configurationName ->
                    owner.configurations.getByName(configurationName).incoming.artifacts.artifacts
                }
            }.distinctBy { it.file.canonicalFile }
            val dependencies = listOf(jmh.compileClasspathConfigurationName to jmh.compileClasspath, jmh.runtimeClasspathConfigurationName to jmh.runtimeClasspath).associate { (configurationName, classpath) ->
                val requiredFiles = classpath.files.map { it.canonicalFile }.toSet() - ownOutputs
                val resolved = artifacts.filter { it.file.canonicalFile in requiredFiles }
                val resolvedFiles = resolved.map { it.file.canonicalFile }.toSet()
                val identities = resolved.sortedBy { it.id.displayName }.map { artifact ->
                    val component = artifact.id.componentIdentifier
                    when (component) {
                        is ProjectComponentIdentifier -> {
                            val dependency = rootProject.project(component.projectPath)
                            mapOf("kind" to "Module", "identity" to dependency.extensions.getByType<IdeaModel>().module.name, "project" to component.projectPath)
                        }
                        is ModuleComponentIdentifier -> mapOf(
                            "kind" to "Library",
                            "identity" to "${component.group}:${component.module}:${component.version}:${artifact.file.name}",
                            "path" to artifact.file.canonicalPath.replace(File.separatorChar, '/'),
                            "sha256" to artifact.file.sha256(),
                        )
                        else -> mapOf("kind" to "Library", "identity" to component.displayName, "path" to artifact.file.canonicalPath.replace(File.separatorChar, '/'), "sha256" to artifact.file.sha256())
                    }
                }.distinct() + (requiredFiles - resolvedFiles).sortedBy { it.path }.map { file ->
                    mapOf("kind" to "Library", "identity" to "file:${file.canonicalPath}", "path" to file.canonicalPath.replace(File.separatorChar, '/'), "sha256" to file.sha256())
                }
                configurationName to identities
            }
            mapOf(
                "project" to owner.path,
                "directory" to owner.projectDir.repositoryPath(),
                "module" to idea.name,
                "iml" to idea.outputFile.repositoryPath(),
                "java" to java,
                "toolchain" to mapOf("vendor" to compiler.vendor.toString(), "runtimeVersion" to compiler.javaRuntimeVersion, "installation" to compiler.installationPath.asFile.canonicalPath.replace(File.separatorChar, '/')),
                "roots" to roots,
                "compilations" to kotlin?.target?.compilations?.map { compilation ->
                    mapOf("name" to compilation.name, "sourceSets" to compilation.allKotlinSourceSets.map { it.name }.sorted(), "associatedWith" to compilation.associatedCompilations.map { it.name }.sorted())
                }.orEmpty(),
                "classpaths" to mapOf("compile" to jmh.compileClasspathConfigurationName, "runtime" to jmh.runtimeClasspathConfigurationName),
                "dependencies" to dependencies,
            )
        }
        require(owners.isNotEmpty()) { "The complete model must discover configured working JMH owners." }
        val inventory = mapOf("revision" to gitOutput("rev-parse", "HEAD").trim(), "tracked" to tracked, "projects" to projects, "owners" to owners, "taskGraph" to gradle.taskGraph.allTasks.map { it.path })
        val output = layout.buildDirectory.file("qodana/declarations.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(inventory)) + "\n")
        logger.lifecycle("Inventoried ${owners.size} declared JMH owners independently of generated IDEA and Qodana metadata.")
    }
}
