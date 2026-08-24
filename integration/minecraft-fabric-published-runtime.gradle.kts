import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.Sync
import java.nio.file.Files
import java.util.zip.ZipFile

val releaseRepository = providers.gradleProperty("strata.releaseRepository")

val minecraftVersion = project.name.removePrefix("minecraft-fabric-")
val runtimeArtifactId = "strata-runtime-minecraft-fabric-$minecraftVersion"
val runtimeCoordinate = "dev.s7a.strata:$runtimeArtifactId:${project.version}"
val canonicalRuntimeFileName = "$runtimeArtifactId-${project.version}.jar"
val publishedRuntimeConfiguration =
    configurations.detachedConfiguration(dependencies.create(runtimeCoordinate)).apply {
        isTransitive = false
    }
val publishedRuntimeFile = layout.buildDirectory.file("published-runtime/$canonicalRuntimeFileName")

val requirePublishedRuntimeRepository = tasks.register("requirePublishedRuntimeRepository") {
    group = "verification"
    description = "Requires the release repository used by the published-coordinate loaded-client smoke test."
    doLast {
        check(releaseRepository.isPresent) {
            "Select this release-only task with -Pstrata.releaseRepository=<Maven repository URL>."
        }
    }
}

tasks.register<Sync>("verifyPublishedRuntimeCoordinate") {
    group = "verification"
    description = "Resolves and verifies the canonical published Fabric runtime without project substitution."
    dependsOn(requirePublishedRuntimeRepository)
    inputs.property("runtimeCoordinate", runtimeCoordinate)
    from(publishedRuntimeConfiguration)
    into(publishedRuntimeFile.map { file -> file.asFile.parentFile })
    include(canonicalRuntimeFileName)
    doFirst {
        val projectComponents =
            publishedRuntimeConfiguration.incoming.resolutionResult.allComponents
                .map { component -> component.id }
                .filterIsInstance<ProjectComponentIdentifier>()
        check(projectComponents.isEmpty()) {
            "Published runtime resolution must not substitute a project component: $projectComponents"
        }

        val artifacts = publishedRuntimeConfiguration.incoming.artifacts.artifacts
        check(artifacts.size == 1) {
            "Published runtime resolution must produce exactly one primary jar: ${artifacts.map { artifact -> artifact.file }}"
        }
        val artifact = artifacts.single()
        val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier
        check(
            module?.group == "dev.s7a.strata" &&
                module.module == runtimeArtifactId &&
                module.version == project.version.toString(),
        ) {
            "Published runtime must resolve the requested external module, but resolved ${artifact.id.componentIdentifier}."
        }
        check(artifact.file.name == canonicalRuntimeFileName) {
            "Published runtime must use the canonical Maven file name, but resolved ${artifact.file.name}."
        }

        ZipFile(artifact.file).use { archive ->
            val entryNames = archive.entries().asSequence().map { entry -> entry.name }.toList()
            check(entryNames.count { name -> name == "fabric.mod.json" } == 1) {
                "Published runtime must contain exactly one top-level fabric.mod.json."
            }
            check("META-INF/LICENSE-strata" in entryNames) {
                "Published runtime must contain the Strata license."
            }
            val metadata =
                archive.getInputStream(archive.getEntry("fabric.mod.json")).bufferedReader().use { reader ->
                    JsonSlurper().parse(reader) as Map<*, *>
                }
            check(metadata["id"] == "strata") {
                "Published runtime must use the stable Fabric mod id."
            }
            check(metadata["version"] == project.version.toString()) {
                "Published runtime metadata must match the requested Strata version."
            }
            check(metadata["name"] == "Strata" && metadata["environment"] == "client" && metadata["license"] == "MIT") {
                "Published runtime must retain the shared client-only project metadata."
            }
            val contact = metadata["contact"] as? Map<*, *> ?: error("Published runtime must declare project contact metadata.")
            check(contact["homepage"] == "https://gh.s7a.dev/strata/") {
                "Published runtime must use the canonical documentation URL."
            }
            check(
                metadata["entrypoints"] ==
                    mapOf(
                        "client" to listOf("dev.s7a.strata.runtime.minecraft.fabric.StrataFabricClient"),
                    ),
            ) {
                "Published runtime must install the shared Strata client entrypoint."
            }
            val dependencies = metadata["depends"] as? Map<*, *> ?: error("Published runtime must declare Fabric dependencies.")
            check(dependencies["minecraft"] == minecraftVersion) {
                "Published runtime must target exactly Minecraft $minecraftVersion."
            }

            val nestedJars =
                entryNames
                    .filter { name -> name.startsWith("META-INF/jars/") && name.endsWith(".jar") }
                    .map { name -> name.substringAfterLast('/') }
                    .sorted()
            val expectedNestedJars =
                listOf(
                    "api-${project.version}.jar",
                    "core-${project.version}.jar",
                    "headless-${project.version}.jar",
                    "minecraft-${project.version}.jar",
                ).sorted()
            check(nestedJars == expectedNestedJars) {
                "Published runtime must contain exactly the four canonical common jars: $nestedJars"
            }
            val declaredNestedJars =
                (metadata["jars"] as? List<*>)
                    ?.map { rawEntry ->
                        val entry = rawEntry as? Map<*, *> ?: error("Published nested-jar metadata must contain objects.")
                        entry["file"] as? String ?: error("Published nested-jar metadata must contain file paths.")
                    }?.sorted()
            check(declaredNestedJars == expectedNestedJars.map { name -> "META-INF/jars/$name" }.sorted()) {
                "Published runtime metadata must reference exactly the four canonical common jars: $declaredNestedJars"
            }
        }
    }
    doLast {
        val source = publishedRuntimeConfiguration.singleFile.toPath()
        val staged = publishedRuntimeFile.get().asFile.toPath()
        check(Files.mismatch(source, staged) == -1L) {
            "The runtime staged for the loaded client must be byte-identical to the resolved Maven artifact."
        }
    }
}
