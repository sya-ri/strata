import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "dev.s7a.strata.release.consumer"
val strataVersion = providers.gradleProperty("strataVersion").get()
require(strataVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?"))) {
    "strataVersion must be an exact semantic release version."
}
version = strataVersion

kotlin {
    jvmToolchain(17)
}

sourceSets.main {
    kotlin.srcDir("../../integration/api/src/main/kotlin")
}

dependencies {
    compileOnly("dev.s7a.strata:strata-api:$strataVersion")
}

val representativeRuntimeCoordinates =
    listOf("dev.s7a.strata:strata-runtime-minecraft-fonts-lwjgl:$strataVersion") +
        providers
            .gradleProperty("strataRepresentativeMinecraftVersions")
            .get()
            .split(',')
            .map(String::trim)
            .also { versions ->
                require(versions.isNotEmpty() && versions.all { version -> version.matches(Regex("[0-9]+(?:\\.[0-9]+)*")) }) {
                    "strataRepresentativeMinecraftVersions must contain exact numeric Minecraft versions."
                }
                require(versions.distinct().size == versions.size) {
                    "strataRepresentativeMinecraftVersions must not contain duplicates."
                }
            }.map { minecraftVersion ->
                "dev.s7a.strata:strata-runtime-minecraft-fabric-$minecraftVersion:$strataVersion"
            }

val representativeRuntimes =
    configurations.create("representativeRuntimes") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = true
    }

dependencies {
    representativeRuntimeCoordinates.forEach { coordinate -> add(representativeRuntimes.name, coordinate) }
}

val verifyPublishedCoordinates =
    tasks.register("verifyPublishedCoordinates") {
        group = "verification"
        description = "Compiles the API-only fixture and resolves representative published runtimes without project dependencies."
        dependsOn("compileKotlin")
        doLast {
            val configurationsToVerify = listOf(configurations.compileClasspath.get(), representativeRuntimes)
            configurationsToVerify.forEach { configuration ->
                val resolutionResult = configuration.incoming.resolutionResult
                val rootComponentId = resolutionResult.rootComponent.get().id
                val projectComponents =
                    resolutionResult.allComponents
                        .map { component -> component.id }
                        .filterIsInstance<ProjectComponentIdentifier>()
                        .filterNot { componentId -> componentId == rootComponentId }
                check(projectComponents.isEmpty()) {
                    "Published consumer configuration ${configuration.name} contains project components: $projectComponents"
                }
                configuration.resolve()
            }
            val selectedRuntimeCoordinates =
                representativeRuntimes.incoming.resolutionResult.allComponents.map { component -> component.id.displayName }.toSet()
            representativeRuntimeCoordinates.forEach { coordinate ->
                check(coordinate in selectedRuntimeCoordinates) { "Published runtime coordinate did not resolve: $coordinate" }
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyPublishedCoordinates)
}
