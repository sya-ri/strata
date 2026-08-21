import org.gradle.api.tasks.SourceSetContainer

plugins {
    `java-library`
    alias(libs.plugins.fabricLoom)
}

val runtimeFabricMain =
    project(":runtime:minecraft-fabric-26.2")
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "strata-integration-minecraft-fabric-26-2"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
    add("gametestImplementation", files(runtimeFabricMain.map { sourceSet -> sourceSet.output }))
    add("gametestImplementation", project(":runtime:headless"))
    add("gametestImplementation", project(":runtime:minecraft"))
    add("gametestRuntimeOnly", libs.fabric.language.kotlin)
}

tasks.named("check") {
    dependsOn("runClientGameTest")
}

tasks.named<JavaExec>("runClientGameTest") {
    val parityOutput = layout.buildDirectory.dir("minecraft-parity")
    inputs.property("strataMinecraftParityOutput", parityOutput.map { it.asFile.absolutePath })
    doFirst {
        systemProperty("strata.minecraftParityOutput", parityOutput.get().asFile.absolutePath)
    }
}
