import org.gradle.api.initialization.resolve.RepositoriesMode

val releaseRepository = providers.gradleProperty("strata.releaseRepository")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        releaseRepository.orNull?.let { repositoryUrl ->
            exclusiveContent {
                forRepository {
                    maven {
                        name = "StrataRelease"
                        url = uri(repositoryUrl)
                        metadataSources {
                            gradleMetadata()
                            mavenPom()
                            artifact()
                        }
                    }
                }
                filter {
                    includeGroup("dev.s7a.strata")
                }
            }
        }
        exclusiveContent {
            forRepository {
                ivy {
                    name = "NodeDistributions"
                    url = uri("https://nodejs.org/dist/")
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
        exclusiveContent {
            forRepository {
                ivy {
                    name = "YarnDistributions"
                    url = uri("https://github.com/yarnpkg/yarn/releases/download/")
                    patternLayout { artifact("v[revision]/[artifact]-v[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.yarnpkg", "yarn") }
        }
        // Why: Minecraft's patched Intel macOS FreeType classifier is absent from the upstream Maven Central module.
        maven("https://repo.papermc.io/repository/maven-public/") {
            content {
                includeGroup("io.papermc.paper")
                includeGroup("com.velocitypowered")
                includeGroup("net.md-5")
            }
        }
        exclusiveContent {
            forRepository {
                maven {
                    name = "MinecraftFreeType"
                    url = uri("https://libraries.minecraft.net/")
                }
            }
            filter {
                includeModule("org.lwjgl", "lwjgl-freetype")
            }
        }
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://libraries.minecraft.net/")
        // Why: Loom publishes generated development artifacts and layered official mappings only into its own Gradle cache.
        exclusiveContent {
            forRepository {
                maven {
                    name = "LoomGeneratedGlobalMinecraft"
                    url = uri(gradle.gradleUserHomeDir.resolve("caches/fabric-loom/minecraftMaven"))
                }
            }
            filter {
                includeGroup("loom")
                includeModule("net.minecraft", "minecraft-merged")
                includeModule("net.minecraft", "minecraft-merged-deobf")
                includeModule("net.minecraft", "minecraft-merged-intermediary")
            }
        }
        // Why: auxiliary Loom source sets publish their generated hashed Minecraft artifact below this build's cache.
        exclusiveContent {
            forRepository {
                maven {
                    name = "LoomGeneratedAuxiliaryMinecraft"
                    url = uri(rootDir.resolve(".gradle/loom-cache/minecraftMaven"))
                }
            }
            filter {
                includeModuleByRegex("net\\.minecraft", "minecraft-merged-[0-9a-f]+")
            }
        }
        // Why: remap Loom resolves Fabric test mods through its generated local Maven layout, which settings repositories otherwise shadow.
        exclusiveContent {
            forRepository {
                maven {
                    name = "LoomGeneratedRemappedMods"
                    url = uri(rootDir.resolve(".gradle/loom-cache/remapped_mods"))
                }
            }
            filter {
                includeGroup("remapped.net.fabricmc")
                includeGroup("remapped.net.fabricmc.fabric-api")
            }
        }
    }
}

rootProject.name = "strata"

val webOnly = providers.gradleProperty("strata.webOnly").map(String::toBooleanStrict).getOrElse(false)
val jvmOnly = providers.gradleProperty("strata.jvmOnly").map(String::toBooleanStrict).getOrElse(false)
val completeIdeaModel = providers.systemProperty("idea.sync.active").map(String::toBoolean).getOrElse(false) ||
    providers.gradleProperty("strata.completeIdeaModel").map(String::toBoolean).getOrElse(false)
val requestedTasks = gradle.startParameter.taskNames
val minecraftChecksOnly = completeIdeaModel.not() && ":ciMinecraftCheck" in requestedTasks && requestedTasks.all {
    it in setOf(":ciMinecraftCheck", ":integration:docs:checkMinecraftShowcaseParity")
}
val documentationChecksOnly = completeIdeaModel.not() && requestedTasks.isNotEmpty() && requestedTasks.all {
    it in setOf(":integration:docs:check", ":integration:docs:checkDokkaPagesStaging")
}
val nativeBenchmarkOnly = requestedTasks.size == 1 &&
    requestedTasks.single().removePrefix(":") in setOf("benchmarkMinecraft", "benchmarkMinecraftQuick") &&
    completeIdeaModel.not()
val minecraftCheckVersions =
    if (minecraftChecksOnly || documentationChecksOnly || nativeBenchmarkOnly) {
        providers.gradleProperty("strata.minecraftVersions").getOrElse("").split(',').map(String::trim).filter(String::isNotEmpty).toSet()
    } else {
        emptySet()
    }
// This union closes the three JVM fixture source sets over their runtime, testkit and quality dependencies.
val jvmProjectPaths = setOf(
    ":api", ":runtime:core", ":runtime:headless", ":runtime:minecraft", ":runtime:minecraft-fonts-lwjgl", ":runtime:remote",
    ":quality:detekt-rules", ":performance-testkit", ":quality:benchmarks", ":quality:component-benchmarks", ":quality:remote-benchmarks",
)
val webProjectPaths = jvmProjectPaths + setOf(":runtime:web", ":integration:web", ":examples:web")
val benchmarkProjectPaths = setOf(":quality:benchmarks", ":quality:component-benchmarks", ":quality:remote-benchmarks")
val jvmMultiplatformProjectPaths = setOf(":api", ":runtime:core", ":performance-testkit")
val jvmPublishedProjectPaths = jvmProjectPaths - benchmarkProjectPaths - ":quality:detekt-rules"
val jvmCollectorTasks = setOf(
    ":quality:benchmarks:jmhHistorical", ":quality:component-benchmarks:jmhComponents",
    ":quality:remote-benchmarks:jmhRemote", ":performance-testkit:processEvidence",
)
if (webOnly || jvmOnly) {
    require((webOnly && jvmOnly).not()) { "Choose one scoped project model: strata.webOnly or strata.jvmOnly." }
    require(completeIdeaModel.not()) { "IDE/Qodana requires the complete project model; remove strata.webOnly and strata.jvmOnly." }
    require(providers.gradleProperty("strata.minecraftVersions").orNull.isNullOrBlank()) { "Minecraft target selection requires the complete project model." }
}
if (jvmOnly) {
    require(requestedTasks.isNotEmpty() && requestedTasks.all { task ->
        val owner = task.substringBeforeLast(':')
        val name = task.substringAfterLast(':')
        owner in jvmProjectPaths && (
            name in setOf("formatKotlin", "lintKotlin", "detekt", "classes") ||
                (owner in jvmPublishedProjectPaths && name in setOf("checkKotlinAbi", "updateKotlinAbi")) ||
                (owner in jvmMultiplatformProjectPaths && name in setOf("jvmTest", "jvmJar")) ||
                (owner in jvmProjectPaths - jvmMultiplatformProjectPaths && name in setOf("test", "jar")) ||
                task in jvmCollectorTasks ||
                (owner in benchmarkProjectPaths && name in setOf("jmhClasses", "jmhRunBytecodeGenerator", "jmhCompileGeneratedClasses"))
            )
    }) { "strata.jvmOnly supports only fully qualified JVM fixture preparation, tests and collection; run check, publication, Kover and inventory acceptance with the complete build." }
    include(*jvmProjectPaths.toTypedArray())
} else if (webOnly) {
    require(gradle.startParameter.taskNames.isNotEmpty() && gradle.startParameter.taskNames.all { task ->
        task.substringBeforeLast(':') in webProjectPaths &&
            ((task.substringAfterLast(':') in setOf("check", "jsTest") && task != ":quality:component-benchmarks:check") ||
                (task.substringBeforeLast(':') in setOf(":runtime:core", ":runtime:minecraft") && task.substringAfterLast(':') in setOf("formatKotlin", "updateKotlinAbi")) ||
                (task.substringBeforeLast(':') == ":performance-testkit" && task.substringAfterLast(':') in setOf("jvmTest", "compileKotlinJs", "publishToMavenLocal", "formatKotlin", "tasks", "updateKotlinAbi", "processEvidence")) ||
                (task.substringBeforeLast(':') in setOf(":quality:benchmarks", ":quality:component-benchmarks", ":quality:remote-benchmarks") && task.substringAfterLast(':') in setOf("formatKotlin", "jmh", "jmhHistorical", "jmhPortableTiles", "jmhRemote", "jmhComponents", "captureComponentInventory", "captureRuntimeSurfaceInventory", "captureHeadlessInventory", "captureRemoteInventory")) ||
                (task.substringBeforeLast(':') == ":quality:detekt-rules" && task.substringAfterLast(':') in setOf("formatKotlin")) ||
                (task.substringBeforeLast(':') == ":integration:web" && task.substringAfterLast(':') in setOf("formatKotlin", "measureWebPerformance")))
    }) { "strata.webOnly supports only fully qualified shared Web/JVM quality tasks; component check and published inventory acceptance require the complete build." }
    include(*webProjectPaths.toTypedArray())
} else {
    val commonProjectPaths = listOf(
        ":api",
        ":paper-api",
        ":velocity-api",
        ":examples:web",
        ":examples:paper",
        ":examples:velocity",
        ":integration:api",
        ":integration:docs",
        ":integration:web",
        ":integration:paper",
        ":integration:velocity",
        ":quality:benchmarks", ":quality:component-benchmarks", ":quality:remote-benchmarks",
        ":performance-testkit",
        ":quality:detekt-rules",
        ":detekt-rules",
        ":runtime:core",
        ":runtime:remote",
        ":runtime:paper",
        ":runtime:velocity",
        ":runtime:web",
        ":runtime:headless",
        ":runtime:minecraft",
        ":runtime:minecraft-fonts-lwjgl",
    )
    include(*commonProjectPaths.filter {
        minecraftCheckVersions.isEmpty() || it != ":integration:docs" || requestedTasks.any { task -> task.startsWith(":integration:docs:") }
    }.toTypedArray())

    val versionedMinecraftProjectName = Regex("minecraft-fabric-[0-9]+(?:\\.[0-9]+)*")
    val versionedMinecraftProjectPaths =
        listOf("integration", "runtime")
            .flatMap { parentName ->
                file(parentName)
                    .listFiles()
                    .orEmpty()
                    .filter { candidate ->
                        candidate.isDirectory &&
                            candidate.name.matches(versionedMinecraftProjectName) &&
                            candidate.resolve("build.gradle.kts").isFile
                    }.map { candidate -> ":$parentName:${candidate.name}" }
            }.sorted()
    val includedMinecraftProjects = versionedMinecraftProjectPaths.filter { path ->
        minecraftCheckVersions.isEmpty() || path.substringAfterLast("minecraft-fabric-") in minecraftCheckVersions ||
            (documentationChecksOnly && path.startsWith(":runtime:"))
    }
    include(*includedMinecraftProjects.toTypedArray())
}
