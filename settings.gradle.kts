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
val requestedTasks = gradle.startParameter.taskNames
val minecraftChecksOnly = ":ciMinecraftCheck" in requestedTasks && requestedTasks.all {
    it in setOf(":ciMinecraftCheck", ":integration:docs:checkMinecraftShowcaseParity")
}
val documentationChecksOnly = requestedTasks.isNotEmpty() && requestedTasks.all {
    it in setOf(":integration:docs:check", ":integration:docs:checkDokkaPagesStaging")
}
val minecraftCheckVersions =
    if (minecraftChecksOnly || documentationChecksOnly) {
        providers.gradleProperty("strata.minecraftVersions").getOrElse("").split(',').map(String::trim).filter(String::isNotEmpty).toSet()
    } else {
        emptySet()
    }
val webProjectPaths = setOf(
    ":api", ":runtime:core", ":runtime:web", ":runtime:headless", ":runtime:minecraft", ":runtime:minecraft-fonts-lwjgl", ":runtime:remote",
    ":integration:web", ":examples:web", ":quality:detekt-rules", ":quality:performance-testkit", ":quality:benchmarks", ":quality:component-benchmarks",
)
if (webOnly) {
    require(gradle.startParameter.taskNames.isNotEmpty() && gradle.startParameter.taskNames.all { task ->
        task.substringBeforeLast(':') in webProjectPaths &&
            (task.substringAfterLast(':') in setOf("check", "jsTest") ||
                (task.substringBeforeLast(':') == ":quality:performance-testkit" && task.substringAfterLast(':') in setOf("jvmTest", "compileKotlinJs", "publishToMavenLocal", "formatKotlin", "tasks", "updateKotlinAbi")) ||
                (task.substringBeforeLast(':') in setOf(":quality:benchmarks", ":quality:component-benchmarks") && task.substringAfterLast(':') in setOf("formatKotlin", "jmh", "jmhComponents", "captureComponentInventory")) ||
                (task.substringBeforeLast(':') == ":integration:web" && task.substringAfterLast(':') in setOf("formatKotlin", "measureWebPerformance")))
    }) { "strata.webOnly supports only fully qualified shared Web/JVM quality tasks; use the complete build for other work." }
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
        ":quality:benchmarks", ":quality:component-benchmarks",
        ":quality:performance-testkit",
        ":quality:detekt-rules",
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
