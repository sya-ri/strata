import groovy.json.JsonSlurper
import groovy.xml.XmlParser
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

// Why: the outer Fabric artifact nests every Strata common module and must publish only the separately installed Kotlin runtime mod.
listOf("api", "implementation").forEach { configurationName ->
    configurations.named(configurationName) {
        withDependencies {
            removeIf { dependency ->
                dependency.group == "org.jetbrains.kotlin" && dependency.name == "kotlin-stdlib"
            }
        }
    }
}

val generatedFabricPom = tasks.named<GenerateMavenPom>("generatePomFileForMavenPublication")
val generatedFabricModuleMetadata = tasks.named<GenerateModuleMetadata>("generateMetadataFileForMavenPublication")
val fabricLanguageKotlinVersion =
    extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("fabric-language-kotlin")
        .orElseThrow { IllegalStateException("The version catalog must define fabric-language-kotlin.") }
        .requiredVersion
val verifyFabricPublicationMetadata =
    tasks.register("verifyFabricPublicationMetadata") {
        group = "verification"
        description = "Verifies that published Fabric metadata exposes only Fabric Language Kotlin at runtime."
        dependsOn(generatedFabricPom, generatedFabricModuleMetadata)
        inputs.file(generatedFabricPom.map { task -> task.destination })
        inputs.file(generatedFabricModuleMetadata.flatMap { task -> task.outputFile })
        doLast {
            val expectedGroup = "net.fabricmc"
            val expectedModule = "fabric-language-kotlin"
            val expectedVersion = fabricLanguageKotlinVersion

            val pom = XmlParser().parse(generatedFabricPom.get().destination)
            val pomDependencies =
                pom.children()
                    .filterIsInstance<groovy.util.Node>()
                    .singleOrNull { node -> node.name().toString().substringAfterLast('}') == "dependencies" }
                    ?.children()
                    ?.filterIsInstance<groovy.util.Node>()
                    ?.map { dependency ->
                        fun value(name: String): String =
                            dependency
                                .children()
                                .filterIsInstance<groovy.util.Node>()
                                .single { child -> child.name().toString().substringAfterLast('}') == name }
                                .text()
                        listOf(value("groupId"), value("artifactId"), value("version"), value("scope"))
                    }.orEmpty()
            check(pomDependencies == listOf(listOf(expectedGroup, expectedModule, expectedVersion, "runtime"))) {
                "Fabric POM must expose only Fabric Language Kotlin at runtime: $pomDependencies"
            }

            val module = JsonSlurper().parse(generatedFabricModuleMetadata.get().outputFile.get().asFile) as Map<*, *>
            val variants = module["variants"] as? List<*> ?: error("Gradle module metadata must contain variants.")
            val dependenciesByVariant =
                variants.associate { rawVariant ->
                    val variant = rawVariant as? Map<*, *> ?: error("Gradle module variant must be an object: $rawVariant")
                    val name = variant["name"] as? String ?: error("Gradle module variant must have a name: $variant")
                    val dependencies =
                        (variant["dependencies"] as? List<*>)
                            .orEmpty()
                            .map { rawDependency ->
                                val dependency = rawDependency as? Map<*, *> ?: error("Gradle dependency must be an object: $rawDependency")
                                val version = dependency["version"] as? Map<*, *> ?: error("Gradle dependency must have a version: $dependency")
                                listOf(dependency["group"], dependency["module"], version["requires"])
                            }
                    name to dependencies
                }
            check(dependenciesByVariant["apiElements"].orEmpty().isEmpty()) {
                "Fabric API metadata must not expose nested dependencies: ${dependenciesByVariant["apiElements"]}"
            }
            check(
                dependenciesByVariant["runtimeElements"] ==
                    listOf(listOf(expectedGroup, expectedModule, expectedVersion)),
            ) {
                "Fabric runtime metadata must expose only Fabric Language Kotlin: ${dependenciesByVariant["runtimeElements"]}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyFabricPublicationMetadata)
}

val fabricJarTaskName = if ("remapJar" in tasks.names) "remapJar" else "jar"
val fabricJar = tasks.named<AbstractArchiveTask>(fabricJarTaskName)
tasks.matching { task -> task.name == "verifyFabricModArtifact" }.configureEach {
    inputs.file(fabricJar.flatMap { task -> task.archiveFile })
    doLast {
        val artifact = fabricJar.get().archiveFile.get().asFile
        val ownersByClass = sortedMapOf<String, MutableList<String>>()
        val lifecycleConfigurationOwners = ArrayList<String>()

        fun record(owner: String, path: String) {
            if (path == "strata.client.mixins.json") lifecycleConfigurationOwners += owner
            val isComparableClass =
                path.endsWith(".class") &&
                    path.substringAfterLast('/') != "module-info.class" &&
                    path.startsWith("META-INF/versions/").not()
            if (isComparableClass) {
                ownersByClass.getOrPut(path) { mutableListOf() } += owner
            }
        }

        ZipFile(artifact).use { archive ->
            val entries = archive.entries().asSequence().map { entry -> entry.name }.toList()
            check(entries.count { name -> name == "strata.client.mixins.json" } == 1) {
                "Fabric artifact must contain exactly one client resource lifecycle configuration."
            }
            check(entries.count { name -> name == "fabric.mod.json" } == 1) {
                "Fabric artifact must contain exactly one top-level mod metadata file."
            }
            val metadata = archive.getInputStream(archive.getEntry("fabric.mod.json")).bufferedReader().use { JsonSlurper().parse(it) as Map<*, *> }
            check(metadata["mixins"] == listOf("strata.client.mixins.json")) {
                "Fabric metadata must reference the required client resource lifecycle configuration exactly once."
            }
            val mixins = archive.getInputStream(archive.getEntry("strata.client.mixins.json")).bufferedReader().use { JsonSlurper().parse(it) as Map<*, *> }
            check(
                mixins ==
                    mapOf(
                        "required" to true,
                        "minVersion" to "0.8.7",
                        "package" to "dev.s7a.strata.runtime.minecraft.fabric.mixin",
                        "client" to listOf("FabricMinecraftResourceReloadMixin"),
                        "injectors" to mapOf("defaultRequire" to 1),
                    ),
            ) {
                "Resource lifecycle configuration must require the verified Mixin minimum and contain only its exact client hooks."
            }
            check(entries.count { name -> name == "dev/s7a/strata/runtime/minecraft/fabric/mixin/FabricMinecraftResourceReloadMixin.class" } == 1) {
                "Fabric artifact must package the resource-generation lifecycle mixin exactly once."
            }
            archive
                .entries()
                .asSequence()
                .filter { entry -> entry.isDirectory.not() }
                .map { entry -> entry.name }
                .sorted()
                .forEach { path -> record("outer", path) }

            archive
                .entries()
                .asSequence()
                .filter { entry ->
                    entry.isDirectory.not() &&
                        entry.name.startsWith("META-INF/jars/") &&
                        entry.name.endsWith(".jar")
                }.sortedBy { entry -> entry.name }
                .forEach { nestedJar ->
                    ZipInputStream(archive.getInputStream(nestedJar)).use { nestedArchive ->
                        generateSequence { nestedArchive.nextEntry }
                            .filter { entry -> entry.isDirectory.not() }
                            .map { entry -> entry.name }
                            .sorted()
                            .forEach { path -> record(nestedJar.name, path) }
                    }
                }
        }

        check(lifecycleConfigurationOwners == listOf("outer")) {
            "Fabric artifact must contain one lifecycle configuration across its outer and nested jars: $lifecycleConfigurationOwners"
        }
        val duplicates =
            ownersByClass
                .filterValues { owners -> 1 < owners.size }
                .mapValues { (_, owners) -> owners.sorted() }
        check(duplicates.isEmpty()) {
            "Fabric artifact must not contain duplicate class paths across its outer and nested jars: $duplicates"
        }
    }
}
