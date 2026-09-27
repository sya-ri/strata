rootProject.name = "strata-release-tools"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}
