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

evaluationDependsOn(":runtime:minecraft-fabric-1.21.9")
val runtimeFabricProject = project(":runtime:minecraft-fabric-1.21.9")
val runtimeFabricMain =
    runtimeFabricProject
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
val runtimeRemappedJar =
    runtimeFabricProject.layout.buildDirectory.file(
        "libs/${runtimeFabricProject.name}-${project.version}.jar",
    )

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "strata-integration-minecraft-fabric-1-21-9"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("gametest") {
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/transport/stream-codec/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("examples/paper/src/main/kotlin"))
        kotlin.exclude("**/PaperDemoPlugin.kt", "**/PaperDemoScreens.kt")
        kotlin.srcDirs(
            rootProject.file("integration/shared/minecraft-fabric/canvas/gui-graphics/src/gametest/kotlin"),
            rootProject.file("integration/shared/minecraft-fabric/runner/gui-graphics/src/gametest/kotlin"),
            rootProject.file("integration/shared/minecraft-fabric/transport/gui-graphics/src/gametest/kotlin"),
        )
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/runner/fabric-client-gametest/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/runner/version-accessor/src/gametest/kotlin"))
        kotlin.srcDirs(
            rootProject.file("integration/shared/minecraft-fabric/input/event-callbacks/src/gametest/kotlin"),
            rootProject.file("integration/shared/minecraft-fabric/transport/event-callbacks/src/gametest/kotlin"),
        )
    }
}

val gametestSourceSet = extensions.getByType<SourceSetContainer>().named("gametest")
tasks.named<ProcessResources>("processGametestResources") {
    inputs.property("version", project.version)
    inputs.property("minecraftVersion", libs.versions.minecraft1219)
    inputs.property("integrationModId", "strata-integration-minecraft-fabric-1-21-9")
    inputs.property("runtimeModId", "strata")
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraftVersion" to libs.versions.minecraft1219.get(),
            "integrationModId" to "strata-integration-minecraft-fabric-1-21-9",
            "runtimeModId" to "strata",
        )
    }
}

dependencies {
    minecraft(libs.minecraft1219)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api1219)
    add("gametestImplementation", files(runtimeFabricMain.map { sourceSet -> sourceSet.output }))
    add("gametestImplementation", project(":runtime:headless"))
    add("gametestImplementation", project(":runtime:remote"))
    add("gametestImplementation", project(":runtime:minecraft"))
    add("gametestImplementation", project(":runtime:minecraft-fonts-lwjgl"))
    add("gametestRuntimeOnly", libs.fabric.language.kotlin)
    add("productionRuntimeMods", libs.fabric.api1219)
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
    providers.gradleProperty("strata.paper.address").orNull?.let {
        jvmArgs.add("-Dstrata.paper.address=$it")
    }
    providers.gradleProperty("strata.paper.run").orNull?.let { jvmArgs.add("-Dstrata.paper.run=$it") }
    dependsOn(deleteProductionGameTestRunDir, ":runtime:minecraft-fabric-1.21.9:remapJar")
    mods.from(runtimeRemappedJar)
    runDir.set(productionRunDirectory)
    jvmArgs.add("-Dfabric.client.gametest")
    val verificationOutput = layout.buildDirectory.dir("minecraft-production-verification")
    jvmArgs.add(verificationOutput.map { directory -> "-Dstrata.minecraftLegacyOutput=${directory.asFile.absolutePath}" })
    jvmArgs.add(libs.versions.minecraft1219.map { version -> "-Dstrata.minecraftVersion=$version" })
}

tasks.named("check") {
    dependsOn("runClientGameTest", runProductionClientGameTest)
}

tasks.matching { task -> task.name == "koverGenerateArtifact" }.configureEach {
    dependsOn("gametestClasses")
}

tasks.named<JavaExec>("runClientGameTest") {
    providers.gradleProperty("strata.paper.address").orNull?.let {
        systemProperty("strata.paper.address", it)
    }
    providers.gradleProperty("strata.paper.run").orNull?.let { systemProperty("strata.paper.run", it) }
    val verificationOutput = layout.buildDirectory.dir("minecraft-verification")
    inputs.property("strataMinecraftLegacyOutput", verificationOutput.map { it.asFile.absolutePath })
    doFirst {
        systemProperty("strata.minecraftLegacyOutput", verificationOutput.get().asFile.absolutePath)
        systemProperty("strata.minecraftVersion", libs.versions.minecraft1219.get())
    }
}
