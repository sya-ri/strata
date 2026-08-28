import com.vanniktech.maven.publish.Checksum
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import dev.detekt.gradle.extensions.DetektExtension
import dev.s7a.strata.gradle.fabric.FabricClientTestOptions
import dev.s7a.strata.gradle.fabric.FabricToolchainManifest
import dev.s7a.strata.gradle.release.StrataReleaseExtension
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.AbstractRunTask
import net.fabricmc.loom.task.prod.ClientProductionRunTask
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.BinariesSource.MAVEN_PUBLICATIONS
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.zip.ZipFile

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokkaJavadocPlugin) apply false
    alias(libs.plugins.fabricLoom) apply false
    alias(libs.plugins.fabricLoomRemap) apply false
    alias(libs.plugins.kover)
    id("dev.s7a.strata.release")
}

group = "dev.s7a.strata"
version = "0.1.1"
private val sourceRevision = providers.gradleProperty("strata.sourceRevision").getOrElse("master")
check(sourceRevision.matches(Regex("(?:master|v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?|[0-9a-f]{40})"))) {
    "strata.sourceRevision must be master, a release tag, or a full lowercase Git commit."
}
private val sourceCommit =
    providers
        .gradleProperty("strata.sourceCommit")
        .orElse(providers.environmentVariable("GITHUB_SHA"))
        .orElse(
            providers
                .exec {
                    workingDir(rootDir)
                    commandLine("git", "rev-parse", "HEAD")
                }.standardOutput.asText
                .map(String::trim),
        ).map { commit ->
            check(commit.matches(Regex("[0-9a-f]{40}"))) {
                "strata.sourceCommit must be the full lowercase Git commit that produced the documentation."
            }
            commit
        }

private data class MinecraftFabricTarget(
    val version: String,
    val javaVersion: Int,
    val remapped: Boolean,
    val sourceLinkPaths: List<String>,
) {
    /** Gradle-owned lock service used to limit unrelated tasks that share mutable external resources. */
    abstract class ExclusiveTaskService : BuildService<BuildServiceParameters.None>

    val runtimeProjectPath: String = ":runtime:minecraft-fabric-$version"
    val integrationProjectPath: String = ":integration:minecraft-fabric-$version"
}

val baselineJavaVersion = libs.versions.java.baseline.get().toInt()
val minecraftJavaVersion = libs.versions.java.minecraft.get().toInt()
val minecraftJava21Version = libs.versions.java.minecraft121.get().toInt()
private val legacyScrollTargets = setOf(libs.versions.minecraft120.get(), libs.versions.minecraft1201.get())
private val fabricLoaderVersion = libs.versions.fabric.loader.get()
private val fabricMixinDependency = libs.fabric.mixin
val sharedLegacyRuntimeSourceLinks =
    listOf(
        "runtime/minecraft-fabric-1.21-legacy",
        "runtime/minecraft-fabric-shared",
    )
private val minecraftFabricTargets =
    listOf(
        MinecraftFabricTarget(
            version = libs.versions.minecraft120.get(),
            javaVersion = baselineJavaVersion,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.20",
                    "runtime/minecraft-fabric-1.20.1",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1201.get(),
            javaVersion = baselineJavaVersion,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.20.1",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1202.get(),
            javaVersion = baselineJavaVersion,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.20.2",
                    "runtime/minecraft-fabric-1.20.4",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1203.get(),
            javaVersion = baselineJavaVersion,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.20.3",
                    "runtime/minecraft-fabric-1.20.4",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1204.get(),
            javaVersion = baselineJavaVersion,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.20.4",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1205.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.20.5",
                    "runtime/minecraft-fabric-1.21.3-legacy",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1206.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.20.6",
                    "runtime/minecraft-fabric-1.21.3-legacy",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft121.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21",
                    "runtime/minecraft-fabric-1.21.3-legacy",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1211.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21.1",
                    "runtime/minecraft-fabric-1.21.3-legacy",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1212.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21.2",
                    "runtime/minecraft-fabric-1.21.3-legacy",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1213.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21.3",
                    "runtime/minecraft-fabric-1.21.3-legacy",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1214.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21.4",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1215.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21.5",
                    "runtime/minecraft-fabric-1.21.5-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1216.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21.6",
                    "runtime/minecraft-fabric-1.21.6-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1217.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21.7",
                    "runtime/minecraft-fabric-1.21.6-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1218.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21.8",
                    "runtime/minecraft-fabric-1.21.6-legacy",
                    "runtime/minecraft-fabric-1.21.8-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft1219.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21.9",
                    "runtime/minecraft-fabric-1.21.6-legacy",
                    "runtime/minecraft-fabric-1.21.9-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft12110.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21.10",
                    "runtime/minecraft-fabric-1.21.6-legacy",
                    "runtime/minecraft-fabric-1.21.9-legacy",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft12111.get(),
            javaVersion = minecraftJava21Version,
            remapped = true,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-1.21.11",
                    "runtime/minecraft-fabric-1.21.6-legacy",
                    "runtime/minecraft-fabric-1.21.9-legacy",
                    "runtime/minecraft-fabric-identifier",
                ) + sharedLegacyRuntimeSourceLinks,
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft261.get(),
            javaVersion = minecraftJavaVersion,
            remapped = false,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-identifier",
                    "runtime/minecraft-fabric-shared",
                    "runtime/minecraft-fabric-unobfuscated",
                ),
        ),
        MinecraftFabricTarget(
            version = libs.versions.minecraft262.get(),
            javaVersion = minecraftJavaVersion,
            remapped = false,
            sourceLinkPaths =
                listOf(
                    "runtime/minecraft-fabric-identifier",
                    "runtime/minecraft-fabric-shared",
                    "runtime/minecraft-fabric-unobfuscated",
                ),
        ),
    )
private val koverJvmProjectPaths = rootProject.file("gradle/kover-jvm-projects.txt").readLines().filter(String::isNotBlank)
check(koverJvmProjectPaths.distinct().size == koverJvmProjectPaths.size) {
    "gradle/kover-jvm-projects.txt must not contain duplicate project paths."
}
check(koverJvmProjectPaths.all { projectPath -> findProject(projectPath) != null }) {
    "gradle/kover-jvm-projects.txt must contain only included Gradle project paths."
}
private val minecraftTargetByProjectPath =
    minecraftFabricTargets
        .flatMap { target -> listOf(target.runtimeProjectPath, target.integrationProjectPath).map { path -> path to target } }
        .toMap()
val publishableProjectPaths =
    setOf(
        ":api",
        ":runtime:core",
        ":runtime:headless",
        ":runtime:minecraft",
        ":runtime:minecraft-fonts-lwjgl",
    ) + minecraftFabricTargets.map(MinecraftFabricTarget::runtimeProjectPath)
val verifyMinecraftFabricTargetMatrix = tasks.register("verifyMinecraftFabricTargetMatrix") {
    group = "verification"
    description = "Verifies that the typed Minecraft target matrix covers every versioned runtime and integration project."
    inputs.property("targets", minecraftFabricTargets.map(MinecraftFabricTarget::version))
    inputs.property("sourceLinkPaths", minecraftFabricTargets.flatMap(MinecraftFabricTarget::sourceLinkPaths))
    doLast {
        val expectedRuntimePaths = minecraftFabricTargets.map(MinecraftFabricTarget::runtimeProjectPath).toSet()
        val actualRuntimePaths =
            project(":runtime")
                .subprojects
                .filter { candidate -> candidate.name.startsWith("minecraft-fabric-") }
                .map { candidate -> candidate.path }
                .toSet()
        check(actualRuntimePaths == expectedRuntimePaths) {
            "Minecraft runtime projects must match the target matrix: expected=$expectedRuntimePaths actual=$actualRuntimePaths"
        }
        val expectedIntegrationPaths = minecraftFabricTargets.map(MinecraftFabricTarget::integrationProjectPath).toSet()
        val actualIntegrationPaths =
            project(":integration")
                .subprojects
                .filter { candidate -> candidate.name.startsWith("minecraft-fabric-") }
                .map { candidate -> candidate.path }
                .toSet()
        check(actualIntegrationPaths == expectedIntegrationPaths) {
            "Minecraft integration projects must match the target matrix: expected=$expectedIntegrationPaths actual=$actualIntegrationPaths"
        }
        check(minecraftFabricTargets.map(MinecraftFabricTarget::version).distinct().size == minecraftFabricTargets.size) {
            "Minecraft target versions must be unique."
        }
        minecraftFabricTargets.forEach { target ->
            check(target.sourceLinkPaths.distinct().size == target.sourceLinkPaths.size) {
                "Minecraft ${target.version} source-link paths must be unique: ${target.sourceLinkPaths}"
            }
            target.sourceLinkPaths.forEach { sourcePath ->
                check(rootProject.file(sourcePath).isDirectory) {
                    "Minecraft ${target.version} source-link path does not exist: $sourcePath"
                }
            }
        }
    }
}
tasks.named("check") {
    dependsOn(verifyMinecraftFabricTargetMatrix)
}

dependencies {
    dokka(project(":api"))
    dokka(project(":runtime:core"))
    dokka(project(":runtime:headless"))
    dokka(project(":runtime:minecraft"))
    dokka(project(":runtime:minecraft-fonts-lwjgl"))
    minecraftFabricTargets.forEach { target -> dokka(project(target.runtimeProjectPath)) }
}

extensions.configure<DokkaExtension> {
    moduleName.set("Strata")
    dokkaPublications.named("html") {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
        includes.from(layout.projectDirectory.file("docs/dokka-module.md"))
    }
}

val verifyGeneratedDokkaSourceLinks =
    tasks.register("verifyGeneratedDokkaSourceLinks") {
        group = "verification"
        description = "Verifies that generated Dokka HTML source links use the configured immutable revision."
        dependsOn("dokkaGenerate")
        val dokkaHtml = layout.buildDirectory.dir("dokka/html")
        inputs.files(
            dokkaHtml.map { directory ->
                directory.asFileTree.matching { include("**/*.html") }
            },
        )
        inputs.property("sourceRevision", sourceRevision)
        doLast {
            val sourceLinkPattern = Regex("https://github\\.com/sya-ri/strata/tree/([^/\"]+)/")
            val revisions =
                dokkaHtml
                    .get()
                    .asFile
                    .walkTopDown()
                    .filter { file -> file.isFile && file.extension == "html" }
                    .flatMap { file -> sourceLinkPattern.findAll(file.readText()).map { match -> match.groupValues[1] } }
                    .toSet()
            check(revisions.isNotEmpty()) { "Generated Dokka HTML contains no GitHub source links." }
            check(revisions == setOf(sourceRevision)) {
                "Generated Dokka source-link revisions differ from $sourceRevision: $revisions"
            }
        }
    }

val stagePagesSourceRevision =
    tasks.register("stagePagesSourceRevision") {
        group = "documentation"
        description = "Stages the exact Dokka source revision and commit as public Pages release evidence."
        dependsOn("dokkaGenerate")
        val revisionFile = layout.buildDirectory.file("dokka/html/source-revision.txt")
        val receiptFile = layout.buildDirectory.file("dokka/html/source-receipt.json")
        inputs.property("sourceRevision", sourceRevision)
        inputs.property("sourceCommit", sourceCommit)
        outputs.files(revisionFile, receiptFile)
        doLast {
            val commit = sourceCommit.get()
            revisionFile.get().asFile.writeText("$sourceRevision\n")
            receiptFile.get().asFile.writeText("{\"commit\":\"$commit\",\"revision\":\"$sourceRevision\"}\n")
        }
    }

val detektRulesProject = project(":quality:detekt-rules")
dependencies {
    add("detektPlugins", detektRulesProject)
}

extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom(layout.projectDirectory.dir("build-logic/src/main/kotlin"))
}

private val minecraftClientTaskNames =
    setOf("runClientGameTest", "runProductionClientGameTest", "runPublishedCoordinateClientGameTest")
private val ideaSyncActive = providers.systemProperty("idea.sync.active").map(String::toBoolean).getOrElse(false)
private val completeIdeaModelActive =
    ideaSyncActive || providers.gradleProperty("strata.completeIdeaModel").map(String::toBoolean).getOrElse(false)
private val ciMinecraftVersions =
    providers
        .gradleProperty("strata.minecraftVersions")
        .map { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
        .getOrElse(emptyList())
private val ciMinecraftTargets = minecraftFabricTargets.filter { target -> target.version in ciMinecraftVersions }
private val requestedTaskNames = gradle.startParameter.taskNames
private val normalizedRequestedTaskNames =
    requestedTaskNames.map { taskName -> taskName.takeIf { it.startsWith(':') } ?: ":$taskName" }
private val selectsEveryMinecraftClient =
    requestedTaskNames.any { taskName -> taskName == "check" || taskName in minecraftClientTaskNames }
private val selectsFontParityClients = requestedTaskNames.any { taskName -> taskName.substringAfterLast(':') == "verifyOfflineFontParity" }
private val fontParityComparisonsByVersion =
    mapOf(
        libs.versions.minecraft120.get() to ":runtime:minecraft-fonts-lwjgl:compareOfflineFontMinecraft120",
        libs.versions.minecraft1205.get() to ":runtime:minecraft-fonts-lwjgl:compareOfflineFontMinecraft1205",
        libs.versions.minecraft262.get() to ":runtime:minecraft-fonts-lwjgl:compareOfflineFontMinecraft262",
    )
private val fontParityMinecraftVersions = fontParityComparisonsByVersion.keys
private val selectedMinecraftExecutionTargets =
    when {
        ciMinecraftVersions.isNotEmpty() ->
            minecraftFabricTargets.filter { target ->
                target in ciMinecraftTargets || (selectsFontParityClients && target.version in fontParityMinecraftVersions)
            }
        selectsEveryMinecraftClient -> minecraftFabricTargets
        else ->
            minecraftFabricTargets.filter { target ->
                normalizedRequestedTaskNames.any { taskName -> taskName.startsWith("${target.integrationProjectPath}:") } ||
                    (selectsFontParityClients && target.version in fontParityMinecraftVersions)
            }
    }
private val selectedMinecraftAssetTasks =
    selectedMinecraftExecutionTargets.map { target -> "${target.integrationProjectPath}:downloadAssets" }
private val selectedMinecraftClientTasks =
    selectedMinecraftExecutionTargets.flatMap { target ->
        buildList {
            add("${target.integrationProjectPath}:runClientGameTest")
            add("${target.integrationProjectPath}:runProductionClientGameTest")
        }
    }
private val minecraftClientExecutionService =
    gradle.sharedServices.registerIfAbsent(
        "minecraftClientExecution",
        MinecraftFabricTarget.ExclusiveTaskService::class,
    ) {
        maxParallelUsages.set(1)
    }
private val minecraftRemapExecutionService =
    gradle.sharedServices.registerIfAbsent(
        "minecraftRemapExecution",
        MinecraftFabricTarget.ExclusiveTaskService::class,
    ) {
        maxParallelUsages.set(1)
    }

if (completeIdeaModelActive) {
    apply(plugin = "idea")
}

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    if (file("build.gradle.kts").isFile.not()) {
        return@subprojects
    }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    apply(plugin = "dev.detekt")
    apply(plugin = "org.jmailen.kotlinter")
    if (completeIdeaModelActive) {
        apply(plugin = "idea")
    }
    if (path in koverJvmProjectPaths) {
        apply(plugin = "org.jetbrains.kotlinx.kover")
        if (path == ":runtime:minecraft-fonts-lwjgl") {
            // Native receipt comparison belongs to the integration gate, not the CPU coverage dependency graph.
            extensions.configure<KoverProjectExtension> {
                currentProject.instrumentation.disabledForTestTasks.addAll(
                    fontParityComparisonsByVersion.values.map { comparisonPath -> comparisonPath.substringAfterLast(':') },
                )
            }
        }
    }

    tasks.matching { task -> task.name == "downloadAssets" }.configureEach {
        usesService(minecraftClientExecutionService)
        val assetTaskIndex = selectedMinecraftAssetTasks.indexOf(path)
        if (assetTaskIndex != -1) {
            mustRunAfter(selectedMinecraftAssetTasks.take(assetTaskIndex))
        }
    }
    tasks
        .matching { task ->
            task.name in minecraftClientTaskNames &&
                minecraftTargetByProjectPath[task.project.path]?.integrationProjectPath == task.project.path
        }
        .configureEach {
            usesService(minecraftClientExecutionService)
            mustRunAfter(selectedMinecraftAssetTasks)
            val clientTaskIndex = selectedMinecraftClientTasks.indexOf(path)
            if (clientTaskIndex != -1) {
                mustRunAfter(selectedMinecraftClientTasks.take(clientTaskIndex))
            }
            doFirst {
                val runDirectory =
                    when (this) {
                        is ClientProductionRunTask -> runDir.get().asFile
                        is AbstractRunTask ->
                            project.extensions
                                .getByType<LoomGradleExtensionAPI>()
                                .runConfigs
                                .named("clientGameTest")
                                .get()
                                .runDirectory
                                .get()
                                .asFile
                        else -> error("Unsupported Minecraft client verification task: $path")
                    }
                FabricClientTestOptions.prepare(project.layout.buildDirectory.get().asFile, runDirectory)
                logger.lifecycle("Prepared silent Minecraft client test options without initial narrator setup for $path")
            }
        }

    tasks.matching { task -> task.name in setOf("remapJar", "remapSourcesJar") }.configureEach {
        usesService(minecraftRemapExecutionService)
    }

    tasks
        .withType<AbstractArchiveTask>()
        .matching { task -> task.name in setOf("jar", "sourcesJar", "dokkaJavadocJar") }
        .configureEach {
            from(rootProject.file("LICENSE")) {
                into("META-INF")
                rename { "LICENSE-strata" }
            }
        }

    tasks
        .matching { task -> task.name in setOf("jar", "sourcesJar", "dokkaJavadocJar", "remapJar", "remapSourcesJar") }
        .configureEach {
            doLast {
                val archiveTask = this as? AbstractArchiveTask
                    ?: error("Published archive verification requires an AbstractArchiveTask: $path")
                val archive = archiveTask.archiveFile.get().asFile
                ZipFile(archive).use { zip ->
                    val licenseEntries = zip.entries().asSequence().count { entry -> entry.name == "META-INF/LICENSE-strata" }
                    check(licenseEntries == 1) {
                        "Published archive ${archive.name} must contain META-INF/LICENSE-strata exactly once; found $licenseEntries."
                    }
                }
            }
        }

    if (this != detektRulesProject) {
        dependencies {
            add("detektPlugins", detektRulesProject)
        }
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        val configFile = if (this@subprojects == detektRulesProject) {
            "config/detekt/detekt-rules.yml"
        } else {
            "config/detekt/detekt.yml"
        }
        config.setFrom(rootProject.file(configFile))
        minecraftTargetByProjectPath[this@subprojects.path]?.let { target ->
            if (this@subprojects.path == target.runtimeProjectPath) {
                source.from(layout.projectDirectory.dir("src/font/kotlin"))
            }
        }
    }

    val publishableModule = path in publishableProjectPaths
    if (publishableModule) {
        apply(plugin = "com.vanniktech.maven.publish")
        apply(plugin = "org.jetbrains.dokka")
        apply(plugin = "org.jetbrains.dokka-javadoc")
    }

    minecraftTargetByProjectPath[path]?.let { target ->
        // Why: Loom otherwise selects native library upgrades using the Gradle daemon's Java instead of this game's toolchain.
        extensions.extraProperties["fabric.loom.runtimeJavaCompatibilityVersion"] = target.javaVersion
        if (path == target.integrationProjectPath) {
            val profileCacheTests = rootProject.file("integration/minecraft-fabric-client-gametest/src/profile-cache/kotlin")
            val continuousInputTests = rootProject.file("integration/minecraft-fabric-client-gametest/src/continuous-input/kotlin")
            val continuousScrollTests =
                rootProject.file(
                    if (target.version in legacyScrollTargets) {
                        "integration/minecraft-fabric-client-gametest/src/continuous-input-legacy-scroll/kotlin"
                    } else {
                        "integration/minecraft-fabric-client-gametest/src/continuous-input-directional-scroll/kotlin"
                    },
                )
            extensions.configure<KotlinJvmProjectExtension> {
                sourceSets.matching { sourceSet -> sourceSet.name == "gametest" }.configureEach {
                    kotlin.srcDir(profileCacheTests)
                    kotlin.srcDir(continuousInputTests)
                    kotlin.srcDir(continuousScrollTests)
                }
            }
            extensions.configure<DetektExtension> {
                source.from(profileCacheTests)
                source.from(continuousInputTests, continuousScrollTests)
            }
            fontParityComparisonsByVersion[target.version]?.let { comparison ->
                tasks.named("check") { dependsOn(comparison) }
            }
        }
    }
    val javaVersion = minecraftTargetByProjectPath[path]?.javaVersion ?: baselineJavaVersion

    extensions.configure<JavaPluginExtension> {
        val compatibility = JavaVersion.toVersion(javaVersion)
        sourceCompatibility = compatibility
        targetCompatibility = compatibility
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }
        withSourcesJar()
    }

    if (completeIdeaModelActive && path in minecraftTargetByProjectPath) {
        afterEvaluate {
            val sourceSets = extensions.getByType<SourceSetContainer>()
            val mainSourceSet = sourceSets.named("main").get()
            val testSourceSet = sourceSets.named("test").get()
            val gameTestSourceSet = sourceSets.findByName("gametest")
            extensions.configure<IdeaModel> {
                module {
                    sourceDirs = mainSourceSet.allSource.srcDirs - mainSourceSet.resources.srcDirs
                    resourceDirs = mainSourceSet.resources.srcDirs
                    testSources.setFrom(testSourceSet.allSource.srcDirs - testSourceSet.resources.srcDirs)
                    testResources.setFrom(testSourceSet.resources.srcDirs)
                    if (gameTestSourceSet != null) {
                        testSources.from(gameTestSourceSet.allSource.srcDirs - gameTestSourceSet.resources.srcDirs)
                        testResources.from(gameTestSourceSet.resources.srcDirs)
                    }

                    val compilePlus = requireNotNull(scopes["COMPILE"]?.get("plus")) {
                        "The Gradle IDEA model must expose its compile-plus dependency scope."
                    }
                    compilePlus.add(configurations.getByName(mainSourceSet.compileClasspathConfigurationName))
                    val testPlus = requireNotNull(scopes["TEST"]?.get("plus")) {
                        "The Gradle IDEA model must expose its test-plus dependency scope."
                    }
                    testPlus.add(configurations.getByName(testSourceSet.compileClasspathConfigurationName))
                    if (gameTestSourceSet != null) {
                        testPlus.add(configurations.getByName(gameTestSourceSet.compileClasspathConfigurationName))
                    }
                }
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(javaVersion)
        options.isWarnings = true
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
            allWarningsAsErrors.set(true)
            freeCompilerArgs.add("-Xexplicit-api=strict")
        }
    }

    if (publishableModule) {
        if (minecraftTargetByProjectPath[project.path]?.runtimeProjectPath == project.path) {
            dependencies.add("compileOnly", fabricMixinDependency)
            val target = minecraftTargetByProjectPath.getValue(project.path)
            val loaderVersion = fabricLoaderVersion
            val mixin = fabricMixinDependency.get()
            val mixinVersion = mixin.versionConstraint.requiredVersion
            val mixinGroup = mixin.module.group
            val toolchainManifest = FabricToolchainManifest(loaderVersion, mixinVersion, mixinGroup)
            tasks.matching { task -> task.name == "verifyFabricModArtifact" }.configureEach {
                val artifact = tasks.named<AbstractArchiveTask>(if (target.remapped) "remapJar" else "jar")
                inputs.file(artifact.flatMap { task -> task.archiveFile })
                inputs.property("manifestLoaderVersion", loaderVersion)
                inputs.property("manifestMixinVersion", mixinVersion)
                inputs.property("manifestMixinGroup", mixinGroup)
                doLast {
                    ZipFile(artifact.get().archiveFile.get().asFile).use(toolchainManifest::verify)
                }
            }
            val sharedFabricRuntime = rootProject.file("runtime/minecraft-fabric-shared/src/main")
            extensions.configure<SourceSetContainer> {
                named("main") {
                    resources.srcDir(sharedFabricRuntime.resolve("resources"))
                }
            }
            extensions.configure<DetektExtension> {
                source.from(
                    sharedFabricRuntime.resolve("kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftProfileLifecycle.kt"),
                    sharedFabricRuntime.resolve("kotlin/dev/s7a/strata/runtime/minecraft/fabric/mixin"),
                )
            }
        }
        extensions.configure<KotlinJvmProjectExtension> {
            if (minecraftTargetByProjectPath[project.path]?.runtimeProjectPath == project.path) {
                // Why: exact font capabilities must not leak through another target's shared main source roots.
                sourceSets.named("main") {
                    kotlin.srcDir(layout.projectDirectory.dir("src/font/kotlin"))
                }
            }
            @OptIn(ExperimentalAbiValidation::class)
            abiValidation {
                binariesSource.set(MAVEN_PUBLICATIONS)
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        if (project.path == ":runtime:minecraft") {
            systemProperty("strata.minecraftTargetVersions", minecraftFabricTargets.joinToString(",", transform = MinecraftFabricTarget::version))
        }
    }

    if (publishableModule) {
        val artifactId = "strata-${path.removePrefix(":").replace(':', '-')}"
        extensions.configure<MavenPublishBaseExtension> {
            coordinates(group.toString(), artifactId, version.toString())
            configure(
                KotlinJvm(
                    javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationJavadoc"),
                    sourcesJar = SourcesJar.Sources(),
                ),
            )
            publishToMavenCentral()
            checksums(Checksum.MD5, Checksum.SHA1, Checksum.SHA256, Checksum.SHA512)
            excludeSignatureChecksums()
            signAllPublications()
            pom {
                name.set("Strata ${project.name}")
                description.set(
                    "Declarative Minecraft UI with reusable component trees, version-independent layout and state, and headless testing without launching Minecraft.",
                )
                url.set("https://github.com/sya-ri/strata")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("sya-ri")
                        name.set("sya-ri")
                        url.set("https://github.com/sya-ri")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/sya-ri/strata.git")
                    developerConnection.set("scm:git:ssh://git@github.com/sya-ri/strata.git")
                    tag.set("v${project.version}")
                    url.set("https://github.com/sya-ri/strata")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/sya-ri/strata/issues")
                }
            }
        }

        extensions.configure<DokkaExtension> {
            val sourcePaths =
                minecraftFabricTargets
                    .singleOrNull { target -> path == target.runtimeProjectPath }
                    ?.sourceLinkPaths
                    ?: listOf(path.removePrefix(":").replace(":", "/"))
            dokkaSourceSets.named("main") {
                for (sourcePath in sourcePaths) {
                    for (language in listOf("kotlin", "java")) {
                        val sourceDirectory = rootProject.file("$sourcePath/src/main/$language")
                        if (sourceDirectory.isDirectory) {
                            sourceLink {
                                localDirectory.set(sourceDirectory)
                                remoteUrl("https://github.com/sya-ri/strata/tree/$sourceRevision/$sourcePath/src/main/$language")
                                remoteLineSuffix.set("#L")
                            }
                        }
                    }
                }
            }

            val verifyDokkaSourceLinks =
                tasks.register("verifyDokkaSourceLinks") {
                    group = "verification"
                    description = "Verifies that every existing Kotlin and Java source root uses the configured Dokka revision."
                    inputs.property("sourceRevision", sourceRevision)
                    doLast {
                        sourcePaths.forEach { sourcePath ->
                            listOf("kotlin", "java").forEach { language ->
                                val sourceDirectory = rootProject.file("$sourcePath/src/main/$language")
                                if (sourceDirectory.isDirectory) {
                                    val expectedUrl =
                                        "https://github.com/sya-ri/strata/tree/$sourceRevision/$sourcePath/src/main/$language"
                                    check(expectedUrl.contains("/tree/$sourceRevision/")) {
                                        "Dokka source link is not pinned to $sourceRevision: $expectedUrl"
                                    }
                                }
                            }
                        }
                    }
                }
            tasks.named("check").configure { dependsOn(verifyDokkaSourceLinks) }
        }

        tasks.withType<GenerateModuleMetadata>().configureEach {
            dependsOn("dokkaJavadocJar")
        }

        tasks.named("check").configure {
            dependsOn("dokkaGeneratePublicationJavadoc")
        }
    }
}

private val selectsFontParityComparison =
    requestedTaskNames.any { taskName ->
        fontParityComparisonsByVersion.values.any { comparisonPath -> comparisonPath.substringAfterLast(':') == taskName.substringAfterLast(':') }
    }
if (selectsFontParityClients || selectsFontParityComparison) {
    // Cross-project task-path discovery does not configure an empty parent project before its Kotlin-script children.
    evaluationDependsOn(":integration")
}

private val koverTaskNames = setOf("koverJvmTests", "koverHtmlReport", "koverXmlReport")
if (requestedTaskNames.any { taskName -> taskName.substringAfterLast(':') in koverTaskNames }) {
    koverJvmProjectPaths.forEach(::evaluationDependsOn)
    dependencies {
        koverJvmProjectPaths.forEach { projectPath -> kover(project(projectPath)) }
    }
}

val koverJvmTests = tasks.register("koverJvmTests") {
    group = "verification"
    description = "Runs ordinary JVM test tasks selected for Kover aggregation."
    dependsOn(koverJvmProjectPaths.map { projectPath -> "$projectPath:test" })
}

tasks.matching { task -> task.name in setOf("koverHtmlReport", "koverXmlReport") }.configureEach {
    dependsOn(koverJvmTests)
}

ciMinecraftTargets.forEach { target ->
    evaluationDependsOn(target.runtimeProjectPath)
    evaluationDependsOn(target.integrationProjectPath)
}
val ciMinecraftCheck = tasks.register("ciMinecraftCheck") {
    group = "verification"
    description = "Runs runtime and loaded-client checks for the exact Minecraft versions selected by strata.minecraftVersions."
    inputs.property("minecraftVersions", ciMinecraftVersions)
    dependsOn(
        ciMinecraftTargets.flatMap { target ->
            listOf("${target.runtimeProjectPath}:check", "${target.integrationProjectPath}:check")
        },
    )
    doFirst {
        check(ciMinecraftVersions.isNotEmpty()) {
            "strata.minecraftVersions must select at least one exact Minecraft version."
        }
        check(ciMinecraftVersions.distinct().size == ciMinecraftVersions.size) {
            "strata.minecraftVersions must not contain duplicate versions: $ciMinecraftVersions"
        }
        val supportedVersions = minecraftFabricTargets.map(MinecraftFabricTarget::version).toSet()
        val unsupportedVersions = ciMinecraftVersions.filterNot(supportedVersions::contains)
        check(unsupportedVersions.isEmpty()) {
            "strata.minecraftVersions contains unsupported versions: $unsupportedVersions"
        }
    }
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"), verifyGeneratedDokkaSourceLinks)
}

val expectedReleaseCoordinates =
    listOf(
        "dev.s7a.strata:strata-api:$version",
        "dev.s7a.strata:strata-runtime-core:$version",
        "dev.s7a.strata:strata-runtime-headless:$version",
        "dev.s7a.strata:strata-runtime-minecraft:$version",
        "dev.s7a.strata:strata-runtime-minecraft-fonts-lwjgl:$version",
    ) +
        minecraftFabricTargets.map { target ->
            "dev.s7a.strata:strata-runtime-minecraft-fabric-${target.version}:$version"
        }
val verifyReleasePublicationMatrix =
    tasks.register("verifyReleasePublicationMatrix") {
        group = "verification"
        description = "Verifies the exact 26-coordinate Maven Central release inventory."
        val coordinatesFile = layout.projectDirectory.file("release/maven-coordinates.txt")
        inputs.file(coordinatesFile)
        inputs.property("expectedCoordinates", expectedReleaseCoordinates)
        doLast {
            val trackedCoordinates = coordinatesFile.asFile.readLines().filter(String::isNotBlank)
            check(expectedReleaseCoordinates.size == 26) { "The release must publish exactly 26 Maven coordinates." }
            check(trackedCoordinates == expectedReleaseCoordinates) {
                "release/maven-coordinates.txt differs from the typed publication matrix."
            }
        }
    }

val publishToMavenLocal =
    tasks.register("publishToMavenLocal") {
        group = "publishing"
        description = "Publishes the exact 26-artifact Strata release matrix to Maven Local."
        dependsOn(publishableProjectPaths.sorted().map { projectPath -> "$projectPath:publishToMavenLocal" })
    }

val verifyPublishedConsumer =
    tasks.register<GradleBuild>("verifyPublishedConsumer") {
        group = "verification"
        description = "Publishes all 26 Maven artifacts locally and checks a standalone coordinate-only consumer."
        dependsOn(publishToMavenLocal, verifyReleasePublicationMatrix)
        dir = layout.projectDirectory.dir("release/consumer").asFile
        tasks = listOf("clean", "check")
    }

tasks.named("mavenCentralReleasePreflight") {
    dependsOn(verifyPublishedConsumer)
}

tasks.named("mavenCentralReleaseVerify") {
    dependsOn(verifyPublishedConsumer)
}

tasks.named("mavenCentralPortalPreflight") {
    dependsOn(verifyPublishedConsumer)
}

tasks.named("mavenCentralPortalVerify") {
    dependsOn(verifyPublishedConsumer)
}

extensions.configure<StrataReleaseExtension> {
    releaseVersion.set(project.version.toString())
    modrinthProjectId.set(
        providers
            .gradleProperty("strata.modrinthProjectId")
            .orElse(providers.environmentVariable("MODRINTH_PROJECT_ID"))
            .orElse(""),
    )
    modrinthApiBaseUrl.set(
        providers
            .gradleProperty("strata.modrinthApiBaseUrl")
            .orElse("https://api.modrinth.com/v2"),
    )
    releaseNotesFile.set(layout.projectDirectory.file("docs/releases/v${project.version}.md"))
    modrinthProjectMetadataFile.set(layout.projectDirectory.file("release/modrinth-project.json"))
    modrinthProjectBodyFile.set(layout.projectDirectory.file("docs/modrinth-project.md"))
    projectAssetFiles.from(
        layout.projectDirectory.file("icon.png"),
        layout.projectDirectory.file("docs/components/overview.png"),
        layout.projectDirectory.file("docs/components/screen-inventory.png"),
        layout.projectDirectory.file("docs/components/screen-progress.png"),
    )
    mavenCoordinatesFile.set(layout.projectDirectory.file("release/maven-coordinates.txt"))
    mavenLocalRepository.set(
        layout.dir(providers.provider { file("${System.getProperty("user.home")}/.m2/repository") }),
    )
    minecraftFabricTargets.forEach { fabricTarget ->
        target(
            gameVersion = fabricTarget.version,
            canonicalFileName = "strata-runtime-minecraft-fabric-${fabricTarget.version}-${project.version}.jar",
            artifact =
                providers.provider {
                    layout.projectDirectory.file(
                        "runtime/minecraft-fabric-${fabricTarget.version}/build/libs/minecraft-fabric-${fabricTarget.version}-${project.version}.jar",
                    )
                },
            verificationTaskPath = "${fabricTarget.runtimeProjectPath}:verifyFabricModArtifact",
        )
    }
}
