import net.fabricmc.loom.task.prod.ClientProductionRunTask
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    `java-library`
    alias(libs.plugins.fabricLoomRemap)
}

val runtimeFabricProject = project(":runtime:minecraft-fabric-1.21.7")
val runtimeFabricMain =
    runtimeFabricProject
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
val runtimeRemappedJar =
    runtimeFabricProject.layout.buildDirectory.file(
        "libs/${runtimeFabricProject.name}-${project.version}.jar",
    )
val sharedLegacyGameTest = rootProject.file("integration/minecraft-fabric-1.21-legacy/src/gametest")
val primitiveInputGameTest = rootProject.file("integration/minecraft-fabric-1.21.8-legacy/src/gametest")

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "strata-integration-minecraft-fabric-1-21-7"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("gametest") {
        kotlin.srcDir(sharedLegacyGameTest.resolve("kotlin"))
        kotlin.srcDir(primitiveInputGameTest.resolve("kotlin"))
    }
}

val gametestSourceSet = extensions.getByType<SourceSetContainer>().named("gametest")
tasks.named<ProcessResources>("processGametestResources") {
    inputs.property("version", project.version)
    inputs.property("minecraftVersion", libs.versions.minecraft1217)
    inputs.property("integrationModId", "strata-integration-minecraft-fabric-1-21-7")
    inputs.property("runtimeModId", "strata-runtime-minecraft-fabric-1-21-7")
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraftVersion" to libs.versions.minecraft1217.get(),
            "integrationModId" to "strata-integration-minecraft-fabric-1-21-7",
            "runtimeModId" to "strata-runtime-minecraft-fabric-1-21-7",
        )
    }
}

dependencies {
    minecraft(libs.minecraft1217)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api1217)
    add("gametestImplementation", files(runtimeFabricMain.map { sourceSet -> sourceSet.output }))
    add("gametestImplementation", project(":runtime:headless"))
    add("gametestImplementation", project(":runtime:minecraft"))
    add("gametestRuntimeOnly", libs.fabric.language.kotlin)
    add("productionRuntimeMods", libs.fabric.api1217)
    add("productionRuntimeMods", libs.fabric.language.kotlin)
}

tasks.named<Jar>("jar") {
    dependsOn("gametestClasses")
    from(gametestSourceSet.map { sourceSet -> sourceSet.output })
}

val productionRunDirectory = layout.buildDirectory.dir("run/productionClientGameTest")
val deleteProductionGameTestRunDir = tasks.register<Delete>("deleteProductionGameTestRunDir") {
    delete(productionRunDirectory)
}
val runProductionClientGameTest = tasks.register<ClientProductionRunTask>("runProductionClientGameTest") {
    group = "verification"
    description = "Runs the client GameTest from the actual remapped integration and runtime mod jars."
    dependsOn(deleteProductionGameTestRunDir, ":runtime:minecraft-fabric-1.21.7:remapJar")
    mods.from(runtimeRemappedJar)
    runDir.set(productionRunDirectory)
    jvmArgs.add("-Dfabric.client.gametest")
    val verificationOutput = layout.buildDirectory.dir("minecraft-production-verification")
    jvmArgs.add(verificationOutput.map { directory -> "-Dstrata.minecraftLegacyOutput=${directory.asFile.absolutePath}" })
    jvmArgs.add(libs.versions.minecraft1217.map { version -> "-Dstrata.minecraftVersion=$version" })
}

tasks.named("check") {
    dependsOn("runClientGameTest", runProductionClientGameTest)
}

tasks.matching { task -> task.name == "koverGenerateArtifact" }.configureEach {
    dependsOn("gametestClasses")
}

tasks.named<JavaExec>("runClientGameTest") {
    val verificationOutput = layout.buildDirectory.dir("minecraft-verification")
    inputs.property("strataMinecraftLegacyOutput", verificationOutput.map { it.asFile.absolutePath })
    doFirst {
        systemProperty("strata.minecraftLegacyOutput", verificationOutput.get().asFile.absolutePath)
        systemProperty("strata.minecraftVersion", libs.versions.minecraft1217.get())
    }
}
