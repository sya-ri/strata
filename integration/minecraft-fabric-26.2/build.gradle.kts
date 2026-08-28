import dev.detekt.gradle.extensions.DetektExtension
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.task.DownloadAssetsTask
import net.fabricmc.loom.task.prod.ClientProductionRunTask
import org.gradle.api.provider.Provider
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
val runtimeFabricProject = project(":runtime:minecraft-fabric-26.2")
val runtimeFabricMain =
    runtimeFabricProject
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
val runtimeJar = runtimeFabricProject.tasks.named<Jar>("jar").flatMap { task -> task.archiveFile }
val sharedGameTest = rootProject.file("integration/minecraft-fabric-unobfuscated/src/gametest")
val fontParityGameTest = rootProject.file("integration/minecraft-font-parity/src/gametest")
val nativeFontParityGameTest = rootProject.file("integration/minecraft-font-parity-26/src/gametest")

extensions.configure<DetektExtension> {
    source.from(fontParityGameTest.resolve("kotlin"), nativeFontParityGameTest.resolve("kotlin"))
}

loom {
    accessWidenerPath.set(nativeFontParityGameTest.resolve("resources/strata-font-parity.accesswidener"))
}

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
        resources.srcDir(fontParityGameTest.resolve("resources"))
        resources.srcDir(nativeFontParityGameTest.resolve("resources"))
    }
}

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("gametest") {
        kotlin.srcDir(sharedGameTest.resolve("kotlin"))
        kotlin.srcDir(fontParityGameTest.resolve("kotlin"))
        kotlin.srcDir(nativeFontParityGameTest.resolve("kotlin"))
    }
}

val gametestSourceSet = extensions.getByType<SourceSetContainer>().named("gametest")
tasks.named<ProcessResources>("processGametestResources") {
    from(sharedGameTest.resolve("resources")) {
        exclude("fabric.mod.json")
    }
    from(rootProject.file("runtime/minecraft-fonts-lwjgl/src/test/resources/fonts/strata-test.ttf")) {
        into("assets/strata_font_test/font")
    }
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
    add("gametestImplementation", project(":runtime:minecraft-fonts-lwjgl"))
    add("gametestRuntimeOnly", libs.fabric.language.kotlin)
    add("productionRuntimeMods", libs.fabric.api262)
    add("productionRuntimeMods", libs.fabric.language.kotlin)
}

// Loom registers client setup tasks only after successful project evaluation.
val showcaseAssetDownload = providers.provider { tasks.named<DownloadAssetsTask>("downloadAssets").get() }
val showcaseMinecraftProvider = providers.provider {
    val extension = LoomGradleExtension.get(project)
    require(extension.customMinecraftMetadata.isPresent.not()) {
        "Default showcase assets require official Mojang metadata. Supply all four strata.showcase asset path properties for custom inputs."
    }
    extension.minecraftProvider
}
// Artifact locations are queried before execution; exposeShowcaseAsset retains the explicit content producer dependency.
val showcaseAssetsDirectory = showcaseAssetDownload.flatMap { task -> task.assetsDirectory.locationOnly }

/**
 * Exposes a read-only Loom asset to documentation consumers without exporting game classes or starting a client.
 * DownloadAssetsTask owns provisioning; these configurations neither copy the asset store nor publish Maven artifacts.
 */
fun exposeShowcaseAsset(configurationName: String, asset: Provider<File>) {
    val configuration = configurations.create(configurationName) {
        isCanBeConsumed = true
        isCanBeResolved = false
    }
    artifacts.add(configuration.name, asset) {
        builtBy(showcaseAssetDownload)
    }
}

exposeShowcaseAsset("showcaseClientJar", showcaseMinecraftProvider.map { provider -> provider.minecraftClientJar })
exposeShowcaseAsset(
    "showcaseAssetIndex",
    showcaseAssetsDirectory.zip(showcaseMinecraftProvider) { directory, provider ->
        val indexName = provider.versionInfo.assetIndex().fabricId(provider.minecraftVersion())
        directory.asFile.resolve("indexes/$indexName.json")
    },
)
exposeShowcaseAsset("showcaseAssetObjects", showcaseAssetsDirectory.map { directory -> directory.asFile.resolve("objects") })
exposeShowcaseAsset(
    "showcaseVersionManifest",
    showcaseMinecraftProvider.map { provider ->
        // Loom's Mojang metadata provider writes this file below its typed version working directory.
        provider.file("mojang_minecraft_info.json").also { manifest ->
            require(manifest.isFile) {
                "Loom's official Mojang version metadata is unavailable. Set strata.showcase.versionManifest to its verified read-only input."
            }
        }
    },
)

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
    jvmArgs.add(libs.versions.minecraft262.map { version -> "-Dstrata.minecraftVersion=$version" })
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
    programArgs.addAll(showcaseClientIdentityArguments)
    jvmArgs.add("-Dfabric.client.gametest")
    val verificationOutput = layout.buildDirectory.dir("minecraft-published-coordinate-parity")
    jvmArgs.add(verificationOutput.map { directory -> "-Dstrata.minecraftParityOutput=${directory.asFile.absolutePath}" })
    jvmArgs.add(libs.versions.minecraft262.map { version -> "-Dstrata.minecraftVersion=$version" })
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
        systemProperty("strata.minecraftVersion", libs.versions.minecraft262.get())
    }
}
