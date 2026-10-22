import dev.s7a.strata.gradle.fabric.CanvasVerification
import dev.detekt.gradle.extensions.DetektExtension
import groovy.json.JsonOutput
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.task.DownloadAssetsTask
import net.fabricmc.loom.task.prod.ClientProductionRunTask
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.util.Properties
import java.util.UUID

plugins {
    `java-library`
    alias(libs.plugins.fabricLoom)
}

private val canvasBackend = providers.gradleProperty("strata.canvas.backend").map(CanvasVerification::parseBackend)
private val canvasScope = providers.gradleProperty("strata.canvas.scope").map(CanvasVerification::parseScope).orElse(CanvasVerification.Scope.Full)

apply(from = rootProject.file("integration/minecraft-fabric-published-runtime.gradle.kts"))

evaluationDependsOn(":runtime:minecraft-fabric-26.3")
val runtimeFabricProject = project(":runtime:minecraft-fabric-26.3")
val runtimeFabricMain =
    runtimeFabricProject
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
val runtimeJar = runtimeFabricProject.tasks.named<Jar>("jar").flatMap { task -> task.archiveFile }

// Match Fabric's native stack requirement without changing the pinned Loom version: https://github.com/FabricMC/fabric-loom/pull/1624.
tasks.withType<JavaExec>().configureEach {
    jvmArgs("-XX:StackShadowPages=32")
}
tasks.withType<ClientProductionRunTask>().configureEach {
    jvmArgs.add("-XX:StackShadowPages=32")
}

extensions.configure<DetektExtension> {
    source.from(rootProject.file("integration/shared/font-parity/src/gametest/kotlin"), rootProject.file("integration/shared/minecraft-fabric/font-parity/gui-extractor/src/gametest/kotlin"), rootProject.file("integration/shared/minecraft-fabric/font-parity/renderpearl/src/gametest/kotlin"))
}

loom {
    accessWidenerPath.set(rootProject.file("integration/shared/minecraft-fabric/font-parity/gui-extractor/src/gametest/resources/strata-font-parity.accesswidener"))
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "strata-integration-minecraft-fabric-26-3"
        enableGameTests = false
        enableClientGameTests = false
        eula = true
    }
}

extensions.configure<SourceSetContainer> {
    named("gametest") {
        resources.srcDir(rootProject.file("integration/shared/font-parity/src/gametest/resources"))
        resources.srcDir(rootProject.file("integration/shared/minecraft-fabric/font-parity/gui-extractor/src/gametest/resources"))
    }
}

loom {
    runs.register("clientGameTest") {
        client()
        sourceSet.set("gametest")
        runDirectory.set(layout.buildDirectory.dir("run/clientGameTest"))
        generateRunConfig.set(false)
    }
}

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("gametest") {
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/runner/screenshot/renderpearl/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/runner/world/gui-holder/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/runner/pointer/delta/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/runner/standalone-client/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/screen/tooltip/template-enchantments/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/screen/gui-holder/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/canvas/terminal/gui-holder/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/runner/canvas-scope/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/canvas/tooltip-reference/gui-extractor/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/transport/paper-screens/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("examples/paper/src/shared/kotlin"))
        kotlin.srcDirs(
            rootProject.file("integration/shared/minecraft-fabric/canvas/gui-extractor/src/gametest/kotlin"),
            rootProject.file("integration/shared/minecraft-fabric/input/gui-extractor/src/gametest/kotlin"),
            rootProject.file("integration/shared/minecraft-fabric/lifecycle/gui-extractor/src/gametest/kotlin"),
            rootProject.file("integration/shared/minecraft-fabric/runner/gui-extractor/src/gametest/kotlin"),
            rootProject.file("integration/shared/minecraft-fabric/scenarios/gui-extractor/src/gametest/kotlin"),
            rootProject.file("integration/shared/minecraft-fabric/scenarios/gui-extractor/src/screen-examples/kotlin"),
            rootProject.file("integration/shared/minecraft-fabric/scenarios/native-parity/src/gametest/kotlin"),
            rootProject.file("integration/shared/minecraft-fabric/screen/gui-extractor/src/gametest/kotlin"),
            rootProject.file("integration/shared/minecraft-fabric/transport/gui-extractor/src/gametest/kotlin"),
        )
        kotlin.srcDir(rootProject.file("integration/shared/font-parity/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/font-parity/gui-extractor/src/gametest/kotlin"))
        kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/font-parity/renderpearl/src/gametest/kotlin"))
    }
}

extensions.configure<DetektExtension> {
    source.from(layout.projectDirectory.dir("src/gametest/kotlin"), rootProject.file("integration/shared/minecraft-fabric/transport/paper-screens/src/gametest/kotlin"))
    source.from(rootProject.file("integration/shared/minecraft-fabric/scenarios/native-parity/src/gametest/kotlin"))
}

val gametestSourceSet = extensions.getByType<SourceSetContainer>().named("gametest")

loom {
    runs.register("manualPaperIme") {
        client()
        sourceSet.set("gametest")
        runDirectory.set(layout.buildDirectory.dir("manual-paper-ime-client"))
        generateRunConfig.set(false)
        systemProperties.put("strata.paper.ime.manual", "true")
        systemProperties.put("strata.paper.ime.output", layout.buildDirectory.dir("manual-paper-ime-evidence").get().asFile.absolutePath)
        providers.gradleProperty("strata.paper.address").orNull?.let { systemProperties.put("strata.paper.address", it) }
        providers.gradleProperty("strata.paper.run").orNull?.let { systemProperties.put("strata.paper.run", it) }
        programArguments.addAll("--width", "960", "--height", "540")
    }
    runs.register("manualIme") {
        client()
        sourceSet.set("gametest")
        runDirectory.set(layout.buildDirectory.dir("manual-ime-client"))
        generateRunConfig.set(false)
        systemProperties.put("strata.ime.manual", "true")
        systemProperties.put("strata.ime.output", layout.buildDirectory.dir("manual-ime-evidence").get().asFile.absolutePath)
        programArguments.addAll("--width", "960", "--height", "540")
    }
}
tasks.named<JavaExec>("runManualIme") {
    description = "Opens a normal client for manual OS IME verification; never enables simulated GameTest input."
    val runId = UUID.randomUUID().toString()
    doFirst { systemProperty("strata.ime.runId", runId) }
    doLast {
        val receipt = layout.buildDirectory.file("manual-ime-evidence/manual-os-ime.txt").get().asFile
        val proof = Properties().apply { receipt.reader(Charsets.UTF_8).use(::load) }
        check(proof.getProperty("runId") == runId) { "The OS IME receipt is missing or belongs to another invocation." }
        check(proof.getProperty("input") == "OS-keyboard-only" && proof.getProperty("editorIdentity") == "retained")
        check(0 < checkNotNull(proof.getProperty("updates")?.toIntOrNull()))
    }
}
tasks.named<JavaExec>("runManualPaperIme") {
    description = "Opens the real Paper remote editor for OS IME verification with normal native input callbacks."
    doLast {
        val receipt = layout.buildDirectory.file("manual-paper-ime-evidence/manual-client.properties").get().asFile
        check(receipt.isFile) { "The manual remote IME fixture did not complete." }
        val proof = Properties().apply { receipt.reader(Charsets.UTF_8).use(::load) }
        check(proof.getProperty("runId") == providers.gradleProperty("strata.paper.run").get()) { "The manual remote IME receipt belongs to another invocation." }
        check(proof.getProperty("input") == "OS-keyboard-only" && proof.getProperty("editorIdentity") == "retained")
        check(1 < checkNotNull(proof.getProperty("updates")?.toIntOrNull()))
    }
}
tasks.named<ProcessResources>("processGametestResources") {
    from(rootProject.file("integration/shared/minecraft-fabric/lifecycle/gui-extractor/src/gametest/resources")) {
        exclude("fabric.mod.json")
    }
    from(rootProject.file("runtime/minecraft-fonts-lwjgl/src/test/resources/fonts/strata-test.ttf")) {
        into("assets/strata_font_test/font")
    }
    val gameTestMixins = listOf("strata.canvas.tests.mixins.json", "strata.canvas.terminal.tests.mixins.json")
    inputs.property("version", project.version)
    inputs.property("minecraftVersion", libs.versions.minecraft263)
    inputs.property("integrationModId", "strata-integration-minecraft-fabric-26-3")
    inputs.property("runtimeModId", "strata")
    inputs.property("gameTestMixins", gameTestMixins)
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraftVersion" to libs.versions.minecraft263.get(),
            "integrationModId" to "strata-integration-minecraft-fabric-26-3",
            "runtimeModId" to "strata",
            "gameTestMixins" to JsonOutput.toJson(gameTestMixins),
        )
    }
}

dependencies {
    minecraft(libs.minecraft263)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api263)
    add("gametestImplementation", files(runtimeFabricMain.map { sourceSet -> sourceSet.output }))
    add("gametestImplementation", project(":runtime:headless"))
    add("gametestImplementation", project(":runtime:remote"))
    add("gametestImplementation", project(":runtime:minecraft"))
    add("gametestImplementation", project(":runtime:minecraft-fonts-lwjgl"))
    add("gametestRuntimeOnly", libs.fabric.language.kotlin)
    add("productionRuntimeMods", libs.fabric.api263)
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
    programArgs.addAll(canvasBackend.map { backend -> listOf("--graphicsBackend", backend.argument) }.orElse(emptyList()))
    providers.gradleProperty("strata.paper.address").orNull?.let {
        jvmArgs.add("-Dstrata.paper.address=$it")
    }
    providers.gradleProperty("strata.paper.run").orNull?.let { jvmArgs.add("-Dstrata.paper.run=$it") }
    providers.gradleProperty("strata.fabric.address").orNull?.let { jvmArgs.add("-Dstrata.fabric.address=$it") }
    providers.gradleProperty("strata.fabric.run").orNull?.let { jvmArgs.add("-Dstrata.fabric.run=$it") }
    jvmArgs.addAll(canvasBackend.map { backend -> listOf("-Dstrata.canvas.expectedBackend=${backend.argument}") }.orElse(emptyList()))
    val verificationOutput = layout.buildDirectory.dir(
        canvasScope.map { scope ->
            when (scope) {
                CanvasVerification.Scope.Full -> canvasBackend.orNull?.let { backend -> "minecraft-production-parity-${backend.argument}" } ?: "minecraft-production-parity"
                CanvasVerification.Scope.CanvasOnly -> "minecraft-production-canvas-parity-${canvasBackend.orNull?.argument ?: "default"}"
                CanvasVerification.Scope.TerminalOnly -> "minecraft-production-canvas-terminal-${canvasBackend.orNull?.argument ?: "default"}"
            }
        },
    )
    inputs.property("strataMinecraftParityOutput", verificationOutput.map { it.asFile.absolutePath })
    inputs.property("strataCanvasBackend", canvasBackend.map(CanvasVerification.Backend::argument).orElse("default"))
    inputs.property("strataCanvasSuiteScope", canvasScope.map(CanvasVerification.Scope::argument))
    jvmArgs.add(verificationOutput.map { directory -> "-Dstrata.minecraftParityOutput=${directory.asFile.absolutePath}" })
    jvmArgs.add(libs.versions.minecraft263.map { version -> "-Dstrata.minecraftVersion=$version" })
    val receipt = verificationOutput.map { directory -> directory.file("strata-canvas-terminal.properties") }
    CanvasVerification.requireTerminalReceipt(this, receipt, libs.versions.minecraft263.get(), canvasScope.get()) { key, value ->
        jvmArgs.add("-D$key=$value")
    }
}

val publishedCoordinateRunDirectory = layout.buildDirectory.dir("run/publishedCoordinateClientGameTest")
val deletePublishedCoordinateGameTestRunDir = tasks.register<Delete>("deletePublishedCoordinateGameTestRunDir") {
    delete(publishedCoordinateRunDirectory)
}
val publishedRuntimeJar =
    layout.buildDirectory.file(
        "published-runtime/strata-runtime-minecraft-fabric-26.3-${project.version}.jar",
    )
tasks.register<ClientProductionRunTask>("runPublishedCoordinateClientGameTest") {
    group = "verification"
    description = "Runs the loaded client against the externally resolved Minecraft 26.3 Strata runtime."
    dependsOn(deletePublishedCoordinateGameTestRunDir, "verifyPublishedRuntimeCoordinate")
    mods.from(publishedRuntimeJar)
    runDir.set(publishedCoordinateRunDirectory)
    programArgs.addAll(showcaseClientIdentityArguments)
    programArgs.addAll(canvasBackend.map { backend -> listOf("--graphicsBackend", backend.argument) }.orElse(emptyList()))
    jvmArgs.addAll(canvasBackend.map { backend -> listOf("-Dstrata.canvas.expectedBackend=${backend.argument}") }.orElse(emptyList()))
    val verificationOutput = layout.buildDirectory.dir(
        canvasBackend.map { backend -> "minecraft-published-coordinate-parity-${backend.argument}" }.orElse("minecraft-published-coordinate-parity"),
    )
    inputs.property("strataCanvasBackend", canvasBackend.map(CanvasVerification.Backend::argument).orElse("default"))
    jvmArgs.add(verificationOutput.map { directory -> "-Dstrata.minecraftParityOutput=${directory.asFile.absolutePath}" })
    jvmArgs.add(libs.versions.minecraft263.map { version -> "-Dstrata.minecraftVersion=$version" })
    val receipt = verificationOutput.map { directory -> directory.file("strata-canvas-terminal.properties") }
    CanvasVerification.requireTerminalReceipt(this, receipt, libs.versions.minecraft263.get(), CanvasVerification.Scope.Full) { key, value ->
        jvmArgs.add("-D$key=$value")
    }
}

tasks.named("check") {
    dependsOn("runClientGameTest", runProductionClientGameTest)
}

tasks.matching { task -> task.name == "koverGenerateArtifact" }.configureEach {
    dependsOn("gametestClasses")
}

tasks.named<JavaExec>("runClientGameTest") {
    args(showcaseClientIdentityArguments)
    providers.gradleProperty("strata.paper.address").orNull?.let {
        systemProperty("strata.paper.address", it)
    }
    providers.gradleProperty("strata.paper.run").orNull?.let { systemProperty("strata.paper.run", it) }
    providers.gradleProperty("strata.fabric.address").orNull?.let { systemProperty("strata.fabric.address", it) }
    providers.gradleProperty("strata.fabric.run").orNull?.let { systemProperty("strata.fabric.run", it) }
    val parityOutput = layout.buildDirectory.dir(
        canvasScope.map { scope ->
            when (scope) {
                CanvasVerification.Scope.Full -> canvasBackend.orNull?.let { backend -> "minecraft-parity-${backend.argument}" } ?: "minecraft-parity"
                CanvasVerification.Scope.CanvasOnly -> "minecraft-canvas-parity-${canvasBackend.orNull?.argument ?: "default"}"
                CanvasVerification.Scope.TerminalOnly -> "minecraft-canvas-terminal-${canvasBackend.orNull?.argument ?: "default"}"
            }
        },
    )
    inputs.property("strataMinecraftParityOutput", parityOutput.map { it.asFile.absolutePath })
    inputs.property("strataCanvasBackend", canvasBackend.map(CanvasVerification.Backend::argument).orElse("default"))
    inputs.property("strataCanvasSuiteScope", canvasScope.map(CanvasVerification.Scope::argument))
    val receipt = parityOutput.map { directory -> directory.file("strata-canvas-terminal.properties") }
    CanvasVerification.requireTerminalReceipt(this, receipt, libs.versions.minecraft263.get(), canvasScope.get()) { key, value ->
        systemProperty(key, value)
    }
    doFirst {
        systemProperty("strata.minecraftParityOutput", parityOutput.get().asFile.absolutePath)
        systemProperty("strata.minecraftVersion", libs.versions.minecraft263.get())
        canvasBackend.orNull?.let { backend ->
            args("--graphicsBackend", backend.argument)
            systemProperty("strata.canvas.expectedBackend", backend.argument)
        }
    }
}
