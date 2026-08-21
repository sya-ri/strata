import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
}

plugins {
    // Why: Gradle cannot use version-catalog aliases in settings plugin requests; keep this in sync with versions.kover.
    id("org.jetbrains.kotlinx.kover.aggregation") version "0.9.9"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://libraries.minecraft.net/")
        // Why: no-remap Loom publishes this generated development artifact only into its own Gradle cache.
        exclusiveContent {
            forRepository {
                maven {
                    name = "LoomGeneratedMinecraft"
                    url = uri(gradle.gradleUserHomeDir.resolve("caches/fabric-loom/minecraftMaven"))
                }
            }
            filter {
                includeModule("net.minecraft", "minecraft-merged-deobf")
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
    }
}

rootProject.name = "strata"

include(
    ":api",
    ":integration:api",
    ":integration:docs",
    ":integration:minecraft-fabric-26.2",
    ":quality:detekt-rules",
    ":runtime:core",
    ":runtime:headless",
    ":runtime:minecraft",
    ":runtime:minecraft-fabric-26.2",
)
