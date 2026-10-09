package dev.s7a.strata.performance

import com.google.gson.JsonObject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Preserves bounded JMH input and runtime archive bytes outside harness timing.
 */
internal object JmhEvidenceArchives {
    /**
     * Copies each previously hashed external input and returns its immutable receipt entry.
     */
    internal fun inputs(
        files: Map<String, Path>,
        hashes: Map<String, String>,
        destination: Path,
    ): JsonObject =
        JsonObject().apply {
            files.entries.forEachIndexed { index, (name, path) ->
                val filename = "input-$index.bin"
                val hash = hashes.getValue(name)
                copy(path.toUri().toString(), destination.resolve(filename), hash)
                add(
                    name,
                    JsonObject().apply {
                        addProperty("archive", filename)
                        addProperty("sha256", hash)
                    },
                )
            }
        }

    /**
     * Copies actual runtime module archives in their existing metadata order.
     */
    internal fun targets(
        runtime: JsonObject,
        destination: Path,
    ): JsonObject =
        JsonObject().apply {
            runtime.getAsJsonArray("modules").forEachIndexed { index, entry ->
                val module = entry.asJsonObject
                val archiveName = "target-$index.jar"
                val origin = module.getAsJsonObject("codeSource")
                copy(origin.get("url").asString, destination.resolve(archiveName), origin.get("sha256").asString)
                addProperty(module.get("module").asString, archiveName)
            }
        }

    /**
     * Copies one bounded regular archive and verifies its loaded hash before and after preservation.
     */
    internal fun copy(
        source: String,
        destination: Path,
        expectedHash: String,
    ) {
        val location = URI(source)
        require(LocalResourceProtocol.decode(location.scheme) == LocalResourceProtocol.File && location.rawAuthority == null)
        val path = Path.of(location)
        require(Files.isRegularFile(path) && Files.size(path) <= 64L * 1024 * 1024) { "JMH provenance requires an actual bounded, separate JAR: $path" }
        require(ArtifactIdentity.file(path) == expectedHash) { "JMH loaded archive changed before preservation" }
        Files.copy(path, destination)
        require(ArtifactIdentity.file(destination) == expectedHash) { "JMH archive snapshot differs from loaded bytes" }
    }
}
