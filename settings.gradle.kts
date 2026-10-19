import org.gradle.api.Task
import org.gradle.api.initialization.resolve.RepositoriesMode
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.tasks.JavaExec
import org.gradle.language.base.plugins.LifecycleBasePlugin

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
val minecraftSourceOnly = providers.gradleProperty("strata.minecraftSourceOnly").map(String::toBooleanStrict).getOrElse(false)
val minecraftSourceVersions =
    if (minecraftSourceOnly) {
        require((webOnly || jvmOnly).not()) { "Choose one scoped source project model." }
        require(completeIdeaModel.not()) { "IDE/Qodana requires the complete project model; remove strata.minecraftSourceOnly." }
        val selected = providers.gradleProperty("strata.minecraftVersions").getOrElse("").split(',').map(String::trim)
        require(selected.all(String::isNotEmpty) && selected.distinct().size == selected.size) { "Source preparation requires nonempty distinct Minecraft versions." }
        selected.toSet()
    } else {
        emptySet()
    }
val requestedTasks = gradle.startParameter.taskNames
val minecraftChecksOnly = completeIdeaModel.not() && ":ciMinecraftCheck" in requestedTasks && requestedTasks.all {
    it in setOf(":ciMinecraftCheck", ":integration:docs:checkMinecraftShowcaseParity")
}
val documentationChecksOnly = completeIdeaModel.not() && requestedTasks.isNotEmpty() && requestedTasks.all {
    it in setOf(":integration:docs:check", ":integration:docs:checkDokkaPagesStaging", ":integration:docs:checkDocumentationLinks", ":integration:docs:checkStrataSkill")
}
val nativeBenchmarkOnly = requestedTasks.size == 1 &&
    requestedTasks.single().removePrefix(":") in setOf("benchmarkMinecraft", "benchmarkMinecraftQuick") &&
    completeIdeaModel.not()
val fabricAcceptanceTask = Regex("^:integration:minecraft-fabric-([0-9.]+):(runProductionServerUiTest|runClientGameTest|runProductionClientGameTest)$")
val fabricAcceptanceVersion = requestedTasks.singleOrNull()?.let(fabricAcceptanceTask::matchEntire)?.groupValues?.get(1)
    ?.takeIf { providers.gradleProperty("strata.fabric.run").isPresent && completeIdeaModel.not() }
val minecraftCheckVersions =
    if (minecraftSourceOnly) {
        minecraftSourceVersions
    } else if (fabricAcceptanceVersion != null) {
        setOf(fabricAcceptanceVersion)
    } else if (minecraftChecksOnly || documentationChecksOnly || nativeBenchmarkOnly) {
        providers.gradleProperty("strata.minecraftVersions").getOrElse("").split(',').map(String::trim).filter(String::isNotEmpty).toSet()
    } else {
        emptySet()
    }
// This union closes the three JVM fixture source sets over their runtime, testkit and quality dependencies.
val sharedJvmProjectPaths = setOf(
    ":api", ":runtime:core", ":runtime:headless", ":runtime:minecraft", ":runtime:minecraft-fonts-lwjgl", ":runtime:remote",
    ":quality:detekt-rules", ":performance-testkit", ":quality:benchmarks", ":quality:component-benchmarks", ":quality:remote-benchmarks",
)
val jvmProjectPaths = sharedJvmProjectPaths + setOf(":integration:api", ":velocity-api", ":runtime:velocity")
val webProjectPaths = sharedJvmProjectPaths + setOf(":runtime:web", ":integration:web", ":examples:web")
if (webOnly || jvmOnly) {
    require((webOnly && jvmOnly).not()) { "Choose one scoped project model: strata.webOnly or strata.jvmOnly." }
    require(completeIdeaModel.not()) { "IDE/Qodana requires the complete project model; remove strata.webOnly and strata.jvmOnly." }
    require(providers.gradleProperty("strata.minecraftVersions").orNull.isNullOrBlank()) { "Minecraft target selection requires the complete project model." }
}

/** Identifies complete-build acceptance from resolved Gradle task metadata, never CLI option tokens. */
fun requiresCompleteJvmModel(name: String, group: String?): Boolean =
    name == "check" || (name.startsWith("kover") && group == LifecycleBasePlugin.VERIFICATION_GROUP) || group in setOf("publishing", "release") ||
        name in setOf(
            "ciMinecraftCheck", "verifyPublishedPerformanceInventory", "verifyPublishedHostInventory",
            "capturePublishedHostInventory", "verifyOfflineFontParity",
        )

/** Refuses complete acceptance and loaded-game execution through resolved scoped task metadata. */
fun installScopedSourceBoundary(property: String, minecraftProjects: Set<String> = emptySet(), scopedAcceptance: (Task) -> Boolean = { false }) {
    fun requiresCompleteModel(task: Task): Boolean =
        (requiresCompleteJvmModel(task.name, task.group) && scopedAcceptance(task).not()) ||
            task.name in setOf("benchmarkMinecraft", "benchmarkMinecraftQuick") ||
            (minecraftProjects.isNotEmpty() &&
                (task.name in setOf("runClientGameTest", "runProductionClientGameTest", "runPublishedCoordinateClientGameTest") ||
                    (task is JavaExec && task.project.path in minecraftProjects)))

    gradle.projectsEvaluated {
        rootProject.allprojects.forEach { project ->
            project.tasks.configureEach {
                if (requiresCompleteModel(this)) {
                    // Invalid acceptance graphs must be reconfigured to preserve refusal before task execution.
                    notCompatibleWithConfigurationCache("$property requires the complete build for acceptance.")
                    doFirst { error("$property requires the complete build for this acceptance task.") }
                }
            }
        }
    }
    // Gradle resolves task selectors and their options; only actual complete-acceptance tasks are refused.
    gradle.taskGraph.whenReady {
        val completeAcceptance = allTasks.filter(::requiresCompleteModel)
        require(completeAcceptance.isEmpty()) {
            "$property cannot run complete acceptance tasks ${completeAcceptance.map { it.path }}; " +
                "run check, publication, Kover, published inventory and native acceptance with the complete build."
        }
    }
}

if (jvmOnly) {
    include(*jvmProjectPaths.toTypedArray())
    installScopedSourceBoundary("strata.jvmOnly")
} else if (webOnly) {
    include(*webProjectPaths.toTypedArray())
    installScopedSourceBoundary("strata.webOnly") { task ->
        (task.name == "check" && task.project.path in webProjectPaths && task.project.path != ":quality:component-benchmarks") ||
            (task.project.path == ":performance-testkit" && (task.name == "publishToMavenLocal" || task is PublishToMavenLocal))
    }
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
    if (minecraftSourceOnly) {
        val availableVersions = versionedMinecraftProjectPaths.map { it.substringAfterLast("minecraft-fabric-") }.toSet()
        val unsupportedVersions = minecraftSourceVersions - availableVersions
        require(unsupportedVersions.isEmpty()) { "Unsupported source-preparation Minecraft versions: $unsupportedVersions" }
    }
    val includedMinecraftProjects = versionedMinecraftProjectPaths.filter { path ->
        minecraftCheckVersions.isEmpty() || path.substringAfterLast("minecraft-fabric-") in minecraftCheckVersions ||
            (documentationChecksOnly && path.startsWith(":runtime:"))
    }
    include(*includedMinecraftProjects.toTypedArray())
    if (minecraftSourceOnly) installScopedSourceBoundary("strata.minecraftSourceOnly", includedMinecraftProjects.toSet())
}
