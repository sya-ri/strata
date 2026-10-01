package dev.s7a.strata.performance

import com.google.gson.JsonObject
import java.net.URI
import java.nio.file.Path

/**
 * Compatibility views of verified loaded JVM artifacts for existing evidence contracts.
 * The representative inventory belongs to the consumer; archive and resource agreement belongs to the kit.
 */
public object JvmArtifactIdentity {
    /**
     * Captures legacy class-resource and archive-hash fields from the verified actual class loader.
     * Missing resources and class-origin disagreement fail preparation.
     */
    public fun capture(
        loader: ClassLoader,
        representatives: List<String>,
    ): JsonObject {
        require(representatives.isNotEmpty() && representatives.distinct().size == representatives.size)
        val metadata = LoadedArtifactMetadata.capture(loader, representatives.associateWith { it }, representatives.toSet())
        LoadedArtifactMetadata.verifyComplete(metadata)
        val classes = JsonObject()
        val archives = sortedMapOf<String, String>()
        metadata.getAsJsonArray("modules").forEach { entry ->
            val module = entry.asJsonObject
            val origin = module.getAsJsonObject("codeSource")
            val path = Path.of(URI(origin.get("url").asString)).toAbsolutePath().normalize()
            archives[path.toString()] = origin.get("sha256").asString
            classes.add(
                module.get("representativeClass").asString,
                JsonObject().apply {
                    add("class_resource", location(module.getAsJsonObject("classResource")))
                    add("code_source", location(origin))
                },
            )
        }
        return JsonObject().apply {
            add("strata_class_sha256", classes)
            add("strata_jar_sha256", JsonObject().apply { archives.forEach { (path, hash) -> addProperty(path, hash) } })
        }
    }

    private fun location(source: JsonObject): JsonObject =
        JsonObject().apply {
            addProperty("url", source.get("url").asString)
            addProperty("sha256", source.get("sha256").asString)
        }
}
