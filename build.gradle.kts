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

dependencies {
    dokka(project(":api"))
    dokka(project(":runtime:core"))
    dokka(project(":runtime:headless"))
    dokka(project(":runtime:minecraft"))
    dokka(project(":runtime:minecraft-fabric-1.21.8"))
    dokka(project(":runtime:minecraft-fabric-1.21.10"))
    dokka(project(":runtime:minecraft-fabric-1.21.9"))
    dokka(project(":runtime:minecraft-fabric-1.21.11"))
    dokka(project(":runtime:minecraft-fabric-26.1"))
    dokka(project(":runtime:minecraft-fabric-26.2"))
}

extensions.configure<DokkaExtension> {
    moduleName.set("Strata")
    dokkaPublications.named("html") {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
        includes.from(layout.projectDirectory.file("README.md"))
    }
}

val detektRulesProject = project(":quality:detekt-rules")
val baselineJavaVersion = libs.versions.java.baseline.get().toInt()
val minecraftJavaVersion = libs.versions.java.minecraft.get().toInt()
val minecraft121JavaVersion = libs.versions.java.minecraft121.get().toInt()
val minecraftGameTestProjects =
    listOf(
        ":integration:minecraft-fabric-26.2",
        ":integration:minecraft-fabric-26.1",
        ":integration:minecraft-fabric-1.21.8",
        ":integration:minecraft-fabric-1.21.11",
        ":integration:minecraft-fabric-1.21.10",
        ":integration:minecraft-fabric-1.21.9",
    )
val minecraftAssetPreparationTasks = minecraftGameTestProjects.map { projectPath -> "$projectPath:downloadAssets" }
val minecraftClientVerificationTasks =
    listOf(
        ":integration:minecraft-fabric-26.2:runClientGameTest",
        ":integration:minecraft-fabric-26.1:runClientGameTest",
        ":integration:minecraft-fabric-1.21.8:runClientGameTest",
        ":integration:minecraft-fabric-1.21.8:runProductionClientGameTest",
        ":integration:minecraft-fabric-1.21.11:runClientGameTest",
        ":integration:minecraft-fabric-1.21.11:runProductionClientGameTest",
        ":integration:minecraft-fabric-1.21.10:runClientGameTest",
        ":integration:minecraft-fabric-1.21.10:runProductionClientGameTest",
        ":integration:minecraft-fabric-1.21.9:runClientGameTest",
        ":integration:minecraft-fabric-1.21.9:runProductionClientGameTest",
    )
val minecraftRemapTasks =
    listOf(
        ":runtime:minecraft-fabric-1.21.8:remapJar",
        ":integration:minecraft-fabric-1.21.8:remapJar",
        ":runtime:minecraft-fabric-1.21.11:remapJar",
        ":integration:minecraft-fabric-1.21.11:remapJar",
        ":runtime:minecraft-fabric-1.21.10:remapJar",
        ":integration:minecraft-fabric-1.21.10:remapJar",
        ":runtime:minecraft-fabric-1.21.9:remapJar",
        ":integration:minecraft-fabric-1.21.9:remapJar",
    )

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

    val publishableModule = path in setOf(
        ":api",
        ":runtime:core",
        ":runtime:headless",
        ":runtime:minecraft",
        ":runtime:minecraft-fabric-1.21.8",
        ":runtime:minecraft-fabric-1.21.10",
        ":runtime:minecraft-fabric-1.21.9",
        ":runtime:minecraft-fabric-1.21.11",
        ":runtime:minecraft-fabric-26.1",
        ":runtime:minecraft-fabric-26.2",
    )
    if (publishableModule) {
        apply(plugin = "com.vanniktech.maven.publish")
        apply(plugin = "org.jetbrains.dokka")
        apply(plugin = "org.jetbrains.dokka-javadoc")
    }

    val versionSpecificMinecraftModules =
        setOf(
            ":runtime:minecraft-fabric-1.21.8",
            ":integration:minecraft-fabric-1.21.8",
            ":runtime:minecraft-fabric-1.21.10",
            ":integration:minecraft-fabric-1.21.10",
            ":runtime:minecraft-fabric-1.21.9",
            ":integration:minecraft-fabric-1.21.9",
            ":runtime:minecraft-fabric-1.21.11",
            ":integration:minecraft-fabric-1.21.11",
            ":runtime:minecraft-fabric-26.1",
            ":integration:minecraft-fabric-26.1",
            ":runtime:minecraft-fabric-26.2",
            ":integration:minecraft-fabric-26.2",
        )
    val javaVersion =
        when (path) {
            in setOf(
                ":runtime:minecraft-fabric-1.21.8",
                ":integration:minecraft-fabric-1.21.8",
                ":runtime:minecraft-fabric-1.21.10",
                ":integration:minecraft-fabric-1.21.10",
                ":runtime:minecraft-fabric-1.21.9",
                ":integration:minecraft-fabric-1.21.9",
                ":runtime:minecraft-fabric-1.21.11",
                ":integration:minecraft-fabric-1.21.11",
            ) -> minecraft121JavaVersion
            in versionSpecificMinecraftModules -> minecraftJavaVersion
            else -> baselineJavaVersion
        }

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
        val artifactId =
            mapOf(
                ":api" to "strata-api",
                ":runtime:core" to "strata-runtime-core",
                ":runtime:headless" to "strata-runtime-headless",
                ":runtime:minecraft" to "strata-runtime-minecraft",
                ":runtime:minecraft-fabric-1.21.8" to "strata-runtime-minecraft-fabric-1.21.8",
                ":runtime:minecraft-fabric-1.21.10" to "strata-runtime-minecraft-fabric-1.21.10",
                ":runtime:minecraft-fabric-1.21.9" to "strata-runtime-minecraft-fabric-1.21.9",
                ":runtime:minecraft-fabric-1.21.11" to "strata-runtime-minecraft-fabric-1.21.11",
                ":runtime:minecraft-fabric-26.1" to "strata-runtime-minecraft-fabric-26.1",
                ":runtime:minecraft-fabric-26.2" to "strata-runtime-minecraft-fabric-26.2",
            ).getValue(path)
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
                when (path) {
                    ":runtime:minecraft-fabric-1.21.8" ->
                        listOf(
                            "runtime/minecraft-fabric-1.21.8",
                            "runtime/minecraft-fabric-1.21.8-legacy",
                            "runtime/minecraft-fabric-1.21-legacy",
                            "runtime/minecraft-fabric-shared",
                        )
                    ":runtime:minecraft-fabric-1.21.10" ->
                        listOf(
                            "runtime/minecraft-fabric-1.21.10",
                            "runtime/minecraft-fabric-1.21.9-legacy",
                            "runtime/minecraft-fabric-1.21-legacy",
                            "runtime/minecraft-fabric-shared",
                        )
                    ":runtime:minecraft-fabric-1.21.9" ->
                        listOf(
                            "runtime/minecraft-fabric-1.21.9",
                            "runtime/minecraft-fabric-1.21.9-legacy",
                            "runtime/minecraft-fabric-1.21-legacy",
                            "runtime/minecraft-fabric-shared",
                        )
                    ":runtime:minecraft-fabric-1.21.11" ->
                        listOf(
                            "runtime/minecraft-fabric-1.21.11",
                            "runtime/minecraft-fabric-1.21.9-legacy",
                            "runtime/minecraft-fabric-1.21-legacy",
                            "runtime/minecraft-fabric-identifier",
                            "runtime/minecraft-fabric-shared",
                        )
                    ":runtime:minecraft-fabric-26.1", ":runtime:minecraft-fabric-26.2" ->
                        listOf(
                            "runtime/minecraft-fabric-identifier",
                            "runtime/minecraft-fabric-shared",
                            "runtime/minecraft-fabric-unobfuscated",
                        )
                    else -> listOf(path.removePrefix(":").replace(":", "/"))
                }
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
