import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    `java-library`
    alias(libs.plugins.fabricLoom)
}

evaluationDependsOn(":runtime:minecraft-fabric-26.1")
val runtimeFabricMain =
    project(":runtime:minecraft-fabric-26.1")
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
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

extensions.configure<SourceSetContainer> {
    named("gametest") {
        resources.srcDir(sharedGameTest.resolve("resources"))
    }
}

tasks.named<ProcessResources>("processGametestResources") {
    inputs.property("version", project.version)
    inputs.property("minecraftVersion", libs.versions.minecraft261)
    inputs.property("integrationModId", "strata-integration-minecraft-fabric-26-1")
    inputs.property("runtimeModId", "strata")
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraftVersion" to libs.versions.minecraft261.get(),
            "integrationModId" to "strata-integration-minecraft-fabric-26-1",
            "runtimeModId" to "strata",
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
    add("gametestRuntimeOnly", libs.fabric.language.kotlin)
}

tasks.named("check") {
    dependsOn("runClientGameTest")
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
