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
    }
}

rootProject.name = "strata"

include(
    ":api",
    ":integration:api",
    ":quality:detekt-rules",
    ":runtime:core",
)
