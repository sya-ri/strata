import groovy.json.JsonSlurper
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.util.zip.ZipFile

plugins {
    `java-library`
    alias(libs.plugins.fabricLoom)
}

val sharedRuntime = rootProject.file("runtime/minecraft-fabric-unobfuscated/src/main")
val sharedTests = rootProject.file("runtime/minecraft-fabric-unobfuscated/src/test")

extensions.configure<SourceSetContainer> {
    named("main") {
        java.srcDir(sharedRuntime.resolve("java"))
        java.srcDir("src/version/java")
    }
    named("test") {
        java.srcDir(sharedTests.resolve("java"))
    }
}

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("main") {
        kotlin.srcDir(sharedRuntime.resolve("kotlin"))
    }
    sourceSets.named("test") {
        kotlin.srcDir(sharedTests.resolve("kotlin"))
    }
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)
    inputs.property("fabricLoaderVersion", libs.versions.fabric.loader)
    inputs.property("minecraftVersion", libs.versions.minecraft262)
    inputs.property("fabricLanguageKotlinVersion", libs.versions.fabric.language.kotlin)
    inputs.property("javaVersion", libs.versions.java.minecraft)
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "fabricLoader" to libs.versions.fabric.loader.get(),
            "minecraft" to libs.versions.minecraft262.get(),
            "fabricLanguageKotlin" to libs.versions.fabric.language.kotlin.get(),
            "java" to libs.versions.java.minecraft.get(),
        )
    }
}

val jarArtifact = tasks.named<Jar>("jar")
val verifyFabricModArtifact = tasks.register("verifyFabricModArtifact") {
    group = "verification"
    description = "Verifies the exact Fabric mod metadata and nested common-runtime jars."
    dependsOn(jarArtifact)
    inputs.file(jarArtifact.flatMap { task -> task.archiveFile })
    inputs.property("fabricLoaderVersion", libs.versions.fabric.loader)
    inputs.property("minecraftVersion", libs.versions.minecraft262)
    inputs.property("fabricLanguageKotlinVersion", libs.versions.fabric.language.kotlin)
    inputs.property("javaVersion", libs.versions.java.minecraft)
    doLast {
        val artifact = jarArtifact.get().archiveFile.get().asFile
        ZipFile(artifact).use { archive ->
            val entryNames = archive.entries().asSequence().map { entry -> entry.name }.toList()
            val nestedNames =
                entryNames
                    .filter { name -> name.startsWith("META-INF/jars/") && name.endsWith(".jar") }
                    .map { name -> name.substringAfterLast('/') }
                    .sorted()
            val expectedNestedNames =
                listOf(
                    "api-${project.version}.jar",
                    "core-${project.version}.jar",
                    "headless-${project.version}.jar",
                    "minecraft-${project.version}.jar",
                ).sorted()
            check(nestedNames == expectedNestedNames) {
                "Fabric artifact must contain each common runtime jar exactly once: $nestedNames"
            }

            val metadataEntries = entryNames.filter { name -> name == "fabric.mod.json" }
            check(metadataEntries.size == 1) {
                "Fabric artifact must contain exactly one top-level fabric.mod.json: $metadataEntries"
            }
            val metadata =
                archive.getInputStream(archive.getEntry(metadataEntries.single())).bufferedReader().use { reader ->
                    JsonSlurper().parse(reader) as Map<*, *>
                }
            check(metadata["schemaVersion"] == 1) {
                "Fabric metadata must use schema version 1."
            }
            check(metadata["id"] == "strata-runtime-minecraft-fabric-26-2") {
                "Fabric metadata must use the reserved module id."
            }
            check(metadata["version"] == project.version.toString()) {
                "Fabric metadata must contain the expanded project version."
            }
            check(metadata["environment"] == "client") {
                "Fabric metadata must remain client-only."
            }
            val nestedMetadata =
                (metadata["jars"] as? List<*>)
                    ?.map { entry ->
                        val item = entry as? Map<*, *> ?: error("Fabric nested-jar metadata entries must be objects: $entry")
                        item["file"] as? String ?: error("Fabric nested-jar metadata entries must contain a file path: $entry")
                    }
                    ?.sorted()
            val expectedNestedMetadata = expectedNestedNames.map { name -> "META-INF/jars/$name" }.sorted()
            check(nestedMetadata == expectedNestedMetadata) {
                "Fabric metadata must reference each common runtime jar exactly once: $nestedMetadata"
            }
            check(
                metadata["depends"] ==
                    mapOf(
                        "fabricloader" to ">=${libs.versions.fabric.loader.get()}",
                        "minecraft" to libs.versions.minecraft262.get(),
                        "fabric-language-kotlin" to ">=${libs.versions.fabric.language.kotlin.get()}",
                        "java" to ">=${libs.versions.java.minecraft.get()}",
                    ),
            ) {
                "Fabric metadata dependencies must match the version catalog: ${metadata["depends"]}"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyFabricModArtifact)
}

dependencies {
    api(project(":runtime:minecraft"))
    implementation(project(":runtime:headless"))
    minecraft(libs.minecraft262)
    implementation(libs.fabric.loader)
    runtimeOnly(libs.fabric.language.kotlin)
    include(project(":api"))
    include(project(":runtime:core"))
    include(project(":runtime:headless"))
    include(project(":runtime:minecraft"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

loom {
    mods {
        register("strata-runtime-minecraft-fabric-26.2") {
            sourceSet(sourceSets.main.get())
        }
    }
}
