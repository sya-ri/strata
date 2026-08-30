import dev.detekt.gradle.extensions.DetektExtension
import groovy.json.JsonOutput
import net.fabricmc.loom.task.prod.ClientProductionRunTask
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    `java-library`
    alias(libs.plugins.fabricLoom)
}

evaluationDependsOn(":runtime:minecraft-fabric-26.1")
val runtimeFabricProject = project(":runtime:minecraft-fabric-26.1")
val runtimeFabricMain =
    runtimeFabricProject
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
val runtimeJar = runtimeFabricProject.tasks.named<Jar>("jar").flatMap { task -> task.archiveFile }
val sharedGameTest = rootProject.file("integration/minecraft-fabric-unobfuscated/src/gametest")

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "strata-integration-minecraft-fabric-26-1"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("gametest") {
        kotlin.srcDir(sharedGameTest.resolve("kotlin"))
    }
}

extensions.configure<DetektExtension> {
    source.from(layout.projectDirectory.dir("src/gametest/kotlin"))
}

extensions.configure<SourceSetContainer> {
    named("gametest") {
        resources.srcDir(sharedGameTest.resolve("resources"))
    }
}

val gametestSourceSet = extensions.getByType<SourceSetContainer>().named("gametest")
tasks.named<ProcessResources>("processGametestResources") {
    val gameTestEntrypoint = "dev.s7a.strata.integration.minecraft.fabric.StrataMinecraftClientGameTest"
    val gameTestMixins = listOf("strata.canvas.tests.mixins.json")
    inputs.property("version", project.version)
    inputs.property("minecraftVersion", libs.versions.minecraft261)
    inputs.property("integrationModId", "strata-integration-minecraft-fabric-26-1")
    inputs.property("runtimeModId", "strata")
    inputs.property("gameTestEntrypoint", gameTestEntrypoint)
    inputs.property("gameTestMixins", gameTestMixins)
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraftVersion" to libs.versions.minecraft261.get(),
            "integrationModId" to "strata-integration-minecraft-fabric-26-1",
            "runtimeModId" to "strata",
            "gameTestEntrypoint" to gameTestEntrypoint,
            "gameTestMixins" to JsonOutput.toJson(gameTestMixins),
        )
    }
}

dependencies {
    minecraft(libs.minecraft261)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api261)
    add("gametestImplementation", files(runtimeFabricMain.map { sourceSet -> sourceSet.output }))
    add("gametestImplementation", project(":runtime:headless"))
    add("gametestImplementation", project(":runtime:minecraft"))
    add("gametestImplementation", project(":runtime:minecraft-fonts-lwjgl"))
    add("gametestRuntimeOnly", libs.fabric.language.kotlin)
    add("productionRuntimeMods", libs.fabric.api261)
    add("productionRuntimeMods", libs.fabric.language.kotlin)
}

tasks.named<Jar>("jar") {
    dependsOn("gametestClasses")
    from(gametestSourceSet.map { sourceSet -> sourceSet.output })
}

/**
 * Uses the development GameTest identity and its vanilla offline UUID for production rendering.
 * Both unobfuscated releases select the original slim Efe skin from this UUID.
 */
val showcaseClientIdentityArguments =
    listOf("--username", "Player0", "--uuid", "2654e3c3-150d-3857-a426-0b141796a4e0")

val productionRunDirectory = layout.buildDirectory.dir("run/productionClientGameTest")
val deleteProductionGameTestRunDir = tasks.register<Delete>("deleteProductionGameTestRunDir") {
    delete(productionRunDirectory)
}
val runProductionClientGameTest = tasks.register<ClientProductionRunTask>("runProductionClientGameTest") {
    group = "verification"
    description = "Runs the client GameTest from the actual integration and runtime mod jars."
    dependsOn(deleteProductionGameTestRunDir)
    mods.from(runtimeJar)
    runDir.set(productionRunDirectory)
    programArgs.addAll(showcaseClientIdentityArguments)
    jvmArgs.add("-Dfabric.client.gametest")
    val verificationOutput = layout.buildDirectory.dir("minecraft-production-parity")
    jvmArgs.add(verificationOutput.map { directory -> "-Dstrata.minecraftParityOutput=${directory.asFile.absolutePath}" })
    jvmArgs.add(libs.versions.minecraft261.map { version -> "-Dstrata.minecraftVersion=$version" })
}

tasks.named("check") {
    dependsOn("runClientGameTest", runProductionClientGameTest)
}

tasks.matching { task -> task.name == "koverGenerateArtifact" }.configureEach {
    dependsOn("gametestClasses")
}

tasks.named<JavaExec>("runClientGameTest") {
    val parityOutput = layout.buildDirectory.dir("minecraft-parity")
    inputs.property("strataMinecraftParityOutput", parityOutput.map { it.asFile.absolutePath })
    doFirst {
        systemProperty("strata.minecraftParityOutput", parityOutput.get().asFile.absolutePath)
        systemProperty("strata.minecraftVersion", libs.versions.minecraft261.get())
    }
}
