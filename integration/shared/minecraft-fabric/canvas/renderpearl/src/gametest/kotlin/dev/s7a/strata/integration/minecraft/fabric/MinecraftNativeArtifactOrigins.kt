package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.LoadedArtifactMetadata
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Captures native functional-test origins from development directories and production archives.
 * Directory identities use the existing complete entry-tree hash and require the representative resource to belong to that directory.
 * Archive metadata and the benchmark collector's archive-only contract remain unchanged.
 */
internal object MinecraftNativeArtifactOrigins {
    /**
     * Resolves classes without initialization through the actual host loader and preserves explicit location failures.
     * Returns explicit unavailable entries for the caller's existing completeness check.
     */
    internal fun capture(
        loader: ClassLoader,
        representatives: Map<String, String>,
    ): JsonObject =
        LoadedArtifactMetadata.capture(loader, representatives, emptySet()).apply {
            addProperty(
                "scope",
                "Native functional-test representative class resources are resolved through the actual host loader. " +
                    "Production code-source files retain their archive hashes; development code-source directories " +
                    "use complete ArtifactIdentity.tree hashes and require the representative resource to belong to that directory. " +
                    "These identities describe local bytes, not transformed in-memory bytecode or substituted Maven archives.",
            )
            getAsJsonArray("modules").forEach { entry ->
                val module = entry.asJsonObject
                val source = module.getAsJsonObject("codeSource")
                val location = source?.get("url")?.takeUnless { it.isJsonNull }?.asString
                val directory = location?.let { runCatching { Path.of(URI(it)) }.getOrNull() }
                if (source != null && directory != null && Files.isDirectory(directory)) {
                    captureDirectory(module, source, directory)
                }
            }
        }

    private fun captureDirectory(
        module: JsonObject,
        source: JsonObject,
        directory: Path,
    ) {
        source.addProperty("kind", "directory-tree")
        source.addProperty("algorithm", "ArtifactIdentity.tree")
        runCatching {
            require(URI(source.get("url").asString).host.isNullOrEmpty()) { "The loaded directory is not local" }
            val resourceName = module.get("representativeClass").asString.replace('.', '/') + ".class"
            val resource = URI(module.getAsJsonObject("classResource").get("url").asString)
            require(Files.isSameFile(directory.resolve(resourceName), Path.of(resource))) { "The representative resource is outside its loaded directory" }
            source.addProperty("sha256", ArtifactIdentity.tree(directory))
            source.addProperty("status", "available")
            source.remove("reason")
        }.onFailure {
            source.addProperty("status", "unavailable")
            source.addProperty("reason", it.toString())
        }
    }
}
