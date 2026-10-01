package dev.s7a.strata.performance

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Reads caller-declared external fixture files from a UTF-8 standard JDK properties manifest.
 * Gradle can register resolved control libraries and native archives without adding consumer hashing logic.
 */
public object JvmPerformanceInputs {
    /**
     * Returns detached labels and absolute file paths, rejecting duplicate labels and unbounded manifests.
     * The JMH adapter independently hashes and preserves each registered file outside measurement.
     */
    public fun read(manifest: Path): Map<String, Path> {
        require(Files.isRegularFile(manifest) && Files.size(manifest) <= 4L * 1024 * 1024) { "Missing or oversized fixture input manifest" }
        val values =
            object : Properties() {
                override fun put(
                    key: Any,
                    value: Any,
                ): Any? {
                    require(key is String && key.isNotBlank() && key.none(Char::isISOControl) && value is String && value.isNotBlank())
                    require(containsKey(key).not()) { "Duplicate fixture input label: $key" }
                    require(size < 16_384) { "Oversized fixture input inventory" }
                    return super.put(key, value)
                }
            }
        Files.newBufferedReader(manifest, Charsets.UTF_8).use(values::load)
        return values.stringPropertyNames().associateWith { label ->
            val path = Path.of(values.getProperty(label))
            require(path.isAbsolute && Files.isRegularFile(path)) { "Fixture input must name an absolute regular file: $label" }
            path.normalize()
        }
    }
}
