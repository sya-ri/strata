package dev.s7a.strata.integration.performance

import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Declares real server configuration inputs without treating generated Properties comments as setting values.
 * The JDK owns Properties decoding and the kit owns file hashing; other formats retain exact byte identity.
 * Both values and original file bytes must stay unchanged throughout one invocation and later processing.
 */
public object ServerPerformanceInputs {
    /**
     * Returns detached controlled values, preserving exact identities for inputs without a standard Properties format.
     */
    public fun identity(files: Map<String, Path>): JsonObject =
        JsonObject().apply {
            files.forEach { (label, path) ->
                require(path.isAbsolute && Files.isRegularFile(path) && Files.size(path) <= 4L * 1024 * 1024)
                if (path.fileName.toString().endsWith(".properties")) {
                    val properties = Properties().also { values -> Files.newInputStream(path).use(values::load) }
                    add(label, JsonObject().apply { properties.stringPropertyNames().sorted().forEach { key -> addProperty(key, properties.getProperty(key)) } })
                } else {
                    addProperty(label, ArtifactIdentity.file(path))
                }
            }
        }

    /**
     * Preserves each actual source file's bytes independently of its controlled setting values.
     */
    public fun byteIdentity(files: Map<String, Path>): JsonObject = JsonObject().apply { files.forEach { (label, path) -> addProperty(label, ArtifactIdentity.file(path)) } }
}
