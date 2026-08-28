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
    ":integration:minecraft-fabric-1.20",
    ":integration:minecraft-fabric-1.20.1",
    ":integration:minecraft-fabric-1.20.2",
    ":integration:minecraft-fabric-1.20.3",
    ":integration:minecraft-fabric-1.20.4",
    ":integration:minecraft-fabric-1.20.5",
    ":integration:minecraft-fabric-1.20.6",
    ":integration:minecraft-fabric-1.21",
    ":integration:minecraft-fabric-1.21.1",
    ":integration:minecraft-fabric-1.21.2",
    ":integration:minecraft-fabric-1.21.3",
    ":integration:minecraft-fabric-1.21.4",
    ":integration:minecraft-fabric-1.21.5",
    ":integration:minecraft-fabric-1.21.6",
    ":integration:minecraft-fabric-1.21.7",
    ":integration:minecraft-fabric-1.21.8",
    ":integration:minecraft-fabric-1.21.10",
    ":integration:minecraft-fabric-1.21.9",
    ":integration:minecraft-fabric-1.21.11",
    ":integration:minecraft-fabric-26.1",
    ":integration:minecraft-fabric-26.2",
    ":quality:benchmarks",
    ":quality:detekt-rules",
    ":runtime:core",
    ":runtime:headless",
    ":runtime:minecraft",
    ":runtime:minecraft-fonts-lwjgl",
    ":runtime:minecraft-fabric-1.20",
    ":runtime:minecraft-fabric-1.20.1",
    ":runtime:minecraft-fabric-1.20.2",
    ":runtime:minecraft-fabric-1.20.3",
    ":runtime:minecraft-fabric-1.20.4",
    ":runtime:minecraft-fabric-1.20.5",
    ":runtime:minecraft-fabric-1.20.6",
    ":runtime:minecraft-fabric-1.21",
    ":runtime:minecraft-fabric-1.21.1",
    ":runtime:minecraft-fabric-1.21.2",
    ":runtime:minecraft-fabric-1.21.3",
    ":runtime:minecraft-fabric-1.21.4",
    ":runtime:minecraft-fabric-1.21.5",
    ":runtime:minecraft-fabric-1.21.6",
    ":runtime:minecraft-fabric-1.21.7",
    ":runtime:minecraft-fabric-1.21.8",
    ":runtime:minecraft-fabric-1.21.10",
    ":runtime:minecraft-fabric-1.21.9",
    ":runtime:minecraft-fabric-1.21.11",
    ":runtime:minecraft-fabric-26.1",
    ":runtime:minecraft-fabric-26.2",
)
