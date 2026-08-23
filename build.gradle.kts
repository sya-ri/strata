import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
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
}

group = "dev.s7a.strata"
version = "0.1.0"

private data class MinecraftFabricTarget(
    val version: String,
    val javaVersion: Int,
    val remapped: Boolean,
    val sourceLinkPaths: List<String>,
) {
    val runtimeProjectPath: String = ":runtime:minecraft-fabric-$version"
    val integrationProjectPath: String = ":integration:minecraft-fabric-$version"
}

val baselineJavaVersion = libs.versions.java.baseline.get().toInt()
val minecraftJavaVersion = libs.versions.java.minecraft.get().toInt()
val minecraft121JavaVersion = libs.versions.java.minecraft121.get().toInt()
val sharedLegacyRuntimeSourceLinks =
    listOf(
        "runtime/minecraft-fabric-1.21-legacy",
        "runtime/minecraft-fabric-shared",
    )
private val minecraftFabricTargets =
    listOf(
        MinecraftFabricTarget(
            version = libs.versions.minecraft121.get(),
            javaVersion = minecraft121JavaVersion,
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
            javaVersion = minecraft121JavaVersion,
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
            javaVersion = minecraft121JavaVersion,
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
            javaVersion = minecraft121JavaVersion,
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
            javaVersion = minecraft121JavaVersion,
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
            javaVersion = minecraft121JavaVersion,
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
            javaVersion = minecraft121JavaVersion,
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
            javaVersion = minecraft121JavaVersion,
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
            javaVersion = minecraft121JavaVersion,
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
            javaVersion = minecraft121JavaVersion,
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
            javaVersion = minecraft121JavaVersion,
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
            javaVersion = minecraft121JavaVersion,
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
val minecraftGameTestProjects = minecraftFabricTargets.map(MinecraftFabricTarget::integrationProjectPath)
val minecraftAssetPreparationTasks = minecraftGameTestProjects.map { projectPath -> "$projectPath:downloadAssets" }
val minecraftClientVerificationTasks =
    minecraftFabricTargets.flatMap { target ->
        buildList {
            add("${target.integrationProjectPath}:runClientGameTest")
            if (target.remapped) {
                add("${target.integrationProjectPath}:runProductionClientGameTest")
            }
        }
    }
val minecraftRemapTasks =
    minecraftFabricTargets
        .filter(MinecraftFabricTarget::remapped)
        .flatMap { target ->
            listOf(
                "${target.runtimeProjectPath}:remapJar",
                "${target.integrationProjectPath}:remapJar",
            )
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

    val minecraftGameTestProjectIndex = minecraftGameTestProjects.indexOf(path)
    if (minecraftGameTestProjectIndex != -1) {
        tasks
            .matching { task -> task.name in setOf("runClientGameTest", "runProductionClientGameTest") }
            .configureEach {
                mustRunAfter(minecraftAssetPreparationTasks)
                val verificationTaskIndex = minecraftClientVerificationTasks.indexOf(path)
                if (verificationTaskIndex != -1) {
                    mustRunAfter(minecraftClientVerificationTasks.take(verificationTaskIndex))
                }
            }
        tasks.matching { task -> task.name == "downloadAssets" }.configureEach {
            mustRunAfter(
                minecraftAssetPreparationTasks.take(minecraftGameTestProjectIndex),
            )
        }
    }

    tasks.matching { task -> task.name == "remapJar" }.configureEach {
        val remapTaskIndex = minecraftRemapTasks.indexOf(path)
        if (remapTaskIndex != -1) {
            mustRunAfter(minecraftRemapTasks.take(remapTaskIndex))
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

val koverJvmTests = tasks.register("koverJvmTests") {
    group = "verification"
    description = "Runs ordinary JVM test tasks selected for Kover aggregation."
    dependsOn(
        subprojects.flatMap { project ->
            project.tasks.withType<Test>().matching { task -> task.name == "test" }
        },
    )
}

tasks.matching { task -> task.name in setOf("koverHtmlReport", "koverXmlReport") }.configureEach {
    dependsOn(koverJvmTests)
}
