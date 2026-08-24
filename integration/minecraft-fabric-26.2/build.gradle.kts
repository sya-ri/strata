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

apply(from = rootProject.file("integration/minecraft-fabric-published-runtime.gradle.kts"))

evaluationDependsOn(":runtime:minecraft-fabric-26.2")
val runtimeFabricMain =
    project(":runtime:minecraft-fabric-26.2")
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
val sharedGameTest = rootProject.file("integration/minecraft-fabric-unobfuscated/src/gametest")

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "strata-integration-minecraft-fabric-26-2"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

extensions.configure<SourceSetContainer> {
    named("gametest") {
        java.srcDir("src/gametest26/java")
        resources.srcDir(sharedGameTest.resolve("resources"))
    }
}

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("gametest") {
        kotlin.srcDir(sharedGameTest.resolve("kotlin"))
    }
}

val gametestSourceSet = extensions.getByType<SourceSetContainer>().named("gametest")
tasks.named<ProcessResources>("processGametestResources") {
    inputs.property("version", project.version)
    inputs.property("minecraftVersion", libs.versions.minecraft262)
    inputs.property("integrationModId", "strata-integration-minecraft-fabric-26-2")
    inputs.property("runtimeModId", "strata")
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraftVersion" to libs.versions.minecraft262.get(),
            "integrationModId" to "strata-integration-minecraft-fabric-26-2",
            "runtimeModId" to "strata",
        )
    }
}

dependencies {
    minecraft(libs.minecraft262)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api262)
    add("gametestImplementation", files(runtimeFabricMain.map { sourceSet -> sourceSet.output }))
    add("gametestImplementation", project(":runtime:headless"))
    add("gametestImplementation", project(":runtime:minecraft"))
    add("gametestRuntimeOnly", libs.fabric.language.kotlin)
    add("productionRuntimeMods", libs.fabric.api262)
    add("productionRuntimeMods", libs.fabric.language.kotlin)
}

tasks.named<Jar>("jar") {
    dependsOn("gametestClasses")
    from(gametestSourceSet.map { sourceSet -> sourceSet.output })
}

val publishedCoordinateRunDirectory = layout.buildDirectory.dir("run/publishedCoordinateClientGameTest")
val deletePublishedCoordinateGameTestRunDir = tasks.register<Delete>("deletePublishedCoordinateGameTestRunDir") {
    delete(publishedCoordinateRunDirectory)
}
val publishedRuntimeJar =
    layout.buildDirectory.file(
        "published-runtime/strata-runtime-minecraft-fabric-26.2-${project.version}.jar",
    )
tasks.register<ClientProductionRunTask>("runPublishedCoordinateClientGameTest") {
    group = "verification"
    description = "Runs the loaded client against the externally resolved Minecraft 26.2 Strata runtime."
    dependsOn(deletePublishedCoordinateGameTestRunDir, "verifyPublishedRuntimeCoordinate")
    mods.from(publishedRuntimeJar)
    runDir.set(publishedCoordinateRunDirectory)
    jvmArgs.add("-Dfabric.client.gametest")
    val verificationOutput = layout.buildDirectory.dir("minecraft-published-coordinate-parity")
    jvmArgs.add(verificationOutput.map { directory -> "-Dstrata.minecraftParityOutput=${directory.asFile.absolutePath}" })
    jvmArgs.add(libs.versions.minecraft262.map { version -> "-Dstrata.minecraftVersion=$version" })
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
        systemProperty("strata.minecraftVersion", libs.versions.minecraft262.get())
    }
}
