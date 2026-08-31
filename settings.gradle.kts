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
        // Why: Minecraft's patched Intel macOS FreeType classifier is absent from the upstream Maven Central module.
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

include(
    ":api",
    ":integration:api",
    ":integration:docs",
    ":quality:benchmarks",
    ":quality:detekt-rules",
    ":runtime:core",
    ":runtime:headless",
    ":runtime:minecraft",
    ":runtime:minecraft-fonts-lwjgl",
)

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
include(*versionedMinecraftProjectPaths.toTypedArray())
