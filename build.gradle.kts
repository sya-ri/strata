import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.SourceSetContainer
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

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokkaJavadocPlugin) apply false
    alias(libs.plugins.fabricLoom) apply false
    alias(libs.plugins.fabricLoomRemap) apply false
    alias(libs.plugins.kover)
}

group = "dev.s7a.strata"
version = "0.1.0"

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
val sharedLegacyRuntimeSourceLinks =
    listOf(
        "runtime/minecraft-fabric-1.21-legacy",
        "runtime/minecraft-fabric-shared",
    )
private val minecraftFabricTargets =
    listOf(
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
    minecraftFabricTargets.forEach { target -> dokka(project(target.runtimeProjectPath)) }
}

extensions.configure<DokkaExtension> {
    moduleName.set("Strata")
    dokkaPublications.named("html") {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
        includes.from(layout.projectDirectory.file("README.md"))
    }
}

val detektRulesProject = project(":quality:detekt-rules")
private val minecraftClientTaskNames = setOf("runClientGameTest", "runProductionClientGameTest")
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
private val selectedMinecraftExecutionTargets =
    when {
        ciMinecraftVersions.isNotEmpty() -> ciMinecraftTargets
        selectsEveryMinecraftClient -> minecraftFabricTargets
        else ->
            minecraftFabricTargets.filter { target ->
                normalizedRequestedTaskNames.any { taskName -> taskName.startsWith("${target.integrationProjectPath}:") }
            }
    }
private val selectedMinecraftAssetTasks =
    selectedMinecraftExecutionTargets.map { target -> "${target.integrationProjectPath}:downloadAssets" }
private val selectedMinecraftClientTasks =
    selectedMinecraftExecutionTargets.flatMap { target ->
        buildList {
            add("${target.integrationProjectPath}:runClientGameTest")
            if (target.remapped) {
                add("${target.integrationProjectPath}:runProductionClientGameTest")
            }
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
    }

    tasks.matching { task -> task.name == "downloadAssets" }.configureEach {
        usesService(minecraftClientExecutionService)
        val assetTaskIndex = selectedMinecraftAssetTasks.indexOf(path)
        if (assetTaskIndex != -1) {
            mustRunAfter(selectedMinecraftAssetTasks.take(assetTaskIndex))
        }
    }
    tasks
        .matching { task -> task.name in minecraftClientTaskNames }
        .configureEach {
            usesService(minecraftClientExecutionService)
            mustRunAfter(selectedMinecraftAssetTasks)
            val clientTaskIndex = selectedMinecraftClientTasks.indexOf(path)
            if (clientTaskIndex != -1) {
                mustRunAfter(selectedMinecraftClientTasks.take(clientTaskIndex))
            }
        }

    tasks.matching { task -> task.name == "remapJar" }.configureEach {
        usesService(minecraftRemapExecutionService)
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
    }

    val publishableModule = path in publishableProjectPaths
    if (publishableModule) {
        apply(plugin = "com.vanniktech.maven.publish")
        apply(plugin = "org.jetbrains.dokka")
        apply(plugin = "org.jetbrains.dokka-javadoc")
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
        extensions.configure<KotlinJvmProjectExtension> {
            @OptIn(ExperimentalAbiValidation::class)
            abiValidation {
                binariesSource.set(MAVEN_PUBLICATIONS)
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
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
                    sourceLink {
                        localDirectory.set(rootProject.file("$sourcePath/src/main/kotlin"))
                        remoteUrl("https://github.com/sya-ri/strata/tree/master/$sourcePath/src/main/kotlin")
                        remoteLineSuffix.set("#L")
                    }
                }
            }
        }

        tasks.withType<GenerateModuleMetadata>().configureEach {
            dependsOn("dokkaJavadocJar")
        }

        tasks.named("check").configure {
            dependsOn("dokkaGeneratePublicationJavadoc")
        }
    }
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
