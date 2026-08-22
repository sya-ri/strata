import net.fabricmc.loom.task.prod.ClientProductionRunTask
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    `java-library`
    alias(libs.plugins.fabricLoomRemap)
}

val runtimeFabricProject = project(":runtime:minecraft-fabric-1.21.11")
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
        modId = "strata-integration-minecraft-fabric-1-21-11"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

tasks.named<ProcessResources>("processGametestResources") {
    inputs.property("version", project.version)
    inputs.property("minecraftVersion", libs.versions.minecraft12111)
    inputs.property("integrationModId", "strata-integration-minecraft-fabric-1-21-11")
    inputs.property("runtimeModId", "strata-runtime-minecraft-fabric-1-21-11")
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraftVersion" to libs.versions.minecraft12111.get(),
            "integrationModId" to "strata-integration-minecraft-fabric-1-21-11",
            "runtimeModId" to "strata-runtime-minecraft-fabric-1-21-11",
        )
    }
}

dependencies {
    minecraft(libs.minecraft12111)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api12111)
    add("gametestImplementation", files(runtimeFabricMain.map { sourceSet -> sourceSet.output }))
    add("gametestImplementation", project(":runtime:headless"))
    add("gametestImplementation", project(":runtime:minecraft"))
    add("gametestRuntimeOnly", libs.fabric.language.kotlin)
    add("productionRuntimeMods", libs.fabric.api12111)
    add("productionRuntimeMods", libs.fabric.language.kotlin)
}

val gametestSourceSet = extensions.getByType<SourceSetContainer>().named("gametest")
tasks.named<Jar>("jar") {
    dependsOn("gametestClasses")
    from(gametestSourceSet.map { sourceSet -> sourceSet.output })
}

tasks.named("remapJar") {
    mustRunAfter(":runtime:minecraft-fabric-1.21.11:remapJar")
}

val productionRunDirectory = layout.buildDirectory.dir("run/productionClientGameTest")
val deleteProductionGameTestRunDir = tasks.register<Delete>("deleteProductionGameTestRunDir") {
    delete(productionRunDirectory)
}
val runProductionClientGameTest = tasks.register<ClientProductionRunTask>("runProductionClientGameTest") {
    group = "verification"
    description = "Runs the client GameTest from the actual remapped integration and runtime mod jars."
    dependsOn(deleteProductionGameTestRunDir, ":runtime:minecraft-fabric-1.21.11:remapJar")
    mustRunAfter("runClientGameTest")
    mods.from(runtimeRemappedJar)
    runDir.set(productionRunDirectory)
    jvmArgs.add("-Dfabric.client.gametest")
    val verificationOutput = layout.buildDirectory.dir("minecraft-production-verification")
    jvmArgs.add(verificationOutput.map { directory -> "-Dstrata.minecraft12111Output=${directory.asFile.absolutePath}" })
    jvmArgs.add(libs.versions.minecraft12111.map { version -> "-Dstrata.minecraftVersion=$version" })
}

tasks.named("check") {
    dependsOn("runClientGameTest", runProductionClientGameTest)
}

tasks.matching { task -> task.name == "koverGenerateArtifact" }.configureEach {
    dependsOn("gametestClasses")
}

tasks.named<JavaExec>("runClientGameTest") {
    mustRunAfter(":integration:minecraft-fabric-26.1:runClientGameTest")
    val verificationOutput = layout.buildDirectory.dir("minecraft-verification")
    inputs.property("strataMinecraft12111Output", verificationOutput.map { it.asFile.absolutePath })
    doFirst {
        systemProperty("strata.minecraft12111Output", verificationOutput.get().asFile.absolutePath)
        systemProperty("strata.minecraftVersion", libs.versions.minecraft12111.get())
    }
}
