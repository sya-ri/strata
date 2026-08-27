import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "dev.s7a.strata.release.consumer"
version = "0.1.1"

kotlin {
    jvmToolchain(17)
}

sourceSets.main {
    kotlin.srcDir("../../integration/api/src/main/kotlin")
}

dependencies {
    compileOnly("dev.s7a.strata:strata-api:0.1.1")
}

val representativeRuntimeCoordinates =
    listOf(
        "dev.s7a.strata:strata-runtime-minecraft-fabric-1.20:0.1.1",
        "dev.s7a.strata:strata-runtime-minecraft-fabric-1.21.11:0.1.1",
        "dev.s7a.strata:strata-runtime-minecraft-fabric-26.2:0.1.1",
    )

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
